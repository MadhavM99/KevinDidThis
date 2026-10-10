# Real TTS: Qualcomm PiperTTS-EN (VoiceAI TTS)

Speech output uses the **Qualcomm VoiceAI TTS SDK** (`voiceai_tts`, v1.1.1.0) with the
`pipertts_en` model from AI Hub (MIT). Piper-on-NPU references: the AI Hub model card
`pipertts_en`, the `qai-appbuilder` sample `pipertts_en` ("runs fully on the Snapdragon
NPU (HTP)"), and the Audio Analytics container docs (Whisper / MeloTTS / PiperTTS all on
the NPU; `piper-tts-en` @ 22050 Hz). Same rule as ASR: every detail below is read from the
shipped SDK or observed on the device, never guessed.

## Sources of truth

| What | Where |
|---|---|
| Android API guide | `melo_sdk/doc/npu/rpc/80-15314-101_AA_TTS_SDK_for_Android_Developer_Guide.pdf` |
| Java API | `tts-sdk.jar` (`javap`) |
| Lifecycle / model loading | `melo_sdk/src/npu/rpc/tts_wrapper/src/TTSImpl.cpp`, `TTSModel.cpp` |
| Model container format | `notebook/melo/npu/model_conversion_scripts/melo/py/qnn_model_generation.py` |
| Sample app | `melo_sdk/sampleapp/npu_rpc_android_sample/TTSSample/` |

## Setup

```bash
# 1. one-time: install the SDK (needs sudo + QPM login)
sudo -E qpm-cli --install voiceai_tts -v 1.1.1.0

# 2. copy tts-sdk.jar + libtts.so + libtts_jni.so into app/libs/ (~6.6 MB, git-ignored)
./scripts/fetch-tts-sdk.sh

# 3. build the combined model from the AI Hub download (see below)
./scripts/build-piper-model.sh models/pipertts_en_8gen3

# 4. push model + DSP libs, then install
./scripts/push-tts-model.sh models/pipertts_en_8gen3/piper_en.64_bit.qnn_v2.50.0.qnn
./gradlew :app:installDebug
```

No extra QNN install is needed — the model's `config.json` asks for `qairt 2.50.0` and QAIRT
**2.50.40** is already on the machine (same 2.50 series).

## The model-format trap (the one real gotcha)

`TTS.setModelPath()` looks like it takes a directory — the guide's example even passes
`"vendor/etc/tts"`. **It does not.** `TTSModel::loadModel()`:

```cpp
int TTSModel::loadModel(const char *model_file) {
    std::ifstream fp(model_file, std::ios::binary | std::ios::ate);   // one file, read whole
    ...
    buffer_addr = rpcmem_alloc(RPCMEM_HEAP_ID_SYSTEM, ..., this->buffer_size);
    fp.read((char*)buffer_addr, this->buffer_size);
    ...
    input_config.model_buffer_fd = this->tts_model->getFd();          // fd handed to DSP
```

So the SDK wants **one combined blob**. It is `[TTS.setSkelLibPath]` that takes a directory —
`TTS.java` appends it to `ADSP_LIBRARY_PATH`.

AI Hub's "Qualcomm Voice AI" download ships the Piper sub-models **separately**:

```
t5_encoder.bin  t5_decoder.bin  encoder.bin  flow.bin  decoder.bin  sdp.bin  config.json
```

`scripts/build-piper-model.sh` recombines them using the piper conversion script from the
VoiceAI_TTS package (a `qnn_model_generation.py` in the piper notebook dir; exact flag
names get confirmed against the shipped script on the build machine — `PIPER_GEN_ARGS`
overrides them). Pi-simple, pure stdlib, no torch — runs in seconds:

```
piper_en.64_bit.qnn_v2.50.0.qnn   (combined TTS container, magic of the VoiceAI format)
```

Argument mapping (AI Hub name → script flag):

| AI Hub file | `--flag` (build-piper-model.sh defaults; confirm on device) |
|---|---|
| `t5_encoder.bin` | `--t5_encoder_model` |
| `t5_decoder.bin` | `--t5_decoder_model` |
| `encoder.bin` | `--vits_encoder_model` |
| `flow.bin` | `--vits_flow_model` |
| `decoder.bin` | `--vits_decoder_model` |
| `sdp.bin` | `--sdp_model` |

## API used

```kotlin
val tts = TTS.newInstance(callback)
tts.setLanguage("English")     // "English"/"Chinese"/"Spanish", not "en"
tts.setModelPath(modelFile)    // FILE
tts.setAudioEncoding("0")      // LINEAR16
tts.setSpeechRate("1.0"); tts.setPitch("0.0"); tts.setVolumeGain("0.0")
tts.setSampleRate("22050")     // Piper EN (MeloTTS used 44100; the SDK honours the asset)
tts.setSkelLibPath(dir)        // DIRECTORY -> ADSP_LIBRARY_PATH
tts.init(); tts.start(text); tts.stop(); tts.deInit()
```

Callback (parameter names from the sample's `TTSManager.java`, not invented):

```java
void onStart(int sampleRateInHz, int audioFormat, int channelCount);
void onAudioAvailable(byte[] buff, int offset, int size);
void onDone();
void onError(int errorCode);
default void onPerformanceResult(int processingTimeMs, int audioLengthInMs, float rtf);
```

The SDK only *produces* PCM — playback is the caller's job, so `VoiceAiTtsSynthesizer`
creates the `AudioTrack` from the values in `onStart` rather than hardcoding the rate.

The guide recommends keeping the engine initialised between utterances (model load is
expensive), so `init()` happens once on a worker thread and `deInit()` only in `shutdown()`.

## Device-side files (not in the APK)

Pushed by `scripts/push-tts-model.sh` into
`/sdcard/Android/data/com.megathon.incidentassistant/files/tts/` (no root needed, unlike the
sample's `/vendor/etc/tts/`):

- `piper_en.64_bit.qnn_v2.50.0.qnn` — built by `scripts/build-piper-model.sh`
- `libtts_impl_skel.so` — from `melo_sdk/libs/npu/rpc_libraries/cdsp/`
- `libQnnHtpV75.so` + `libQnnSystem.so` — from `${QNN}/lib/hexagon-v75/unsigned/`
  (V75 = SM8650 / Snapdragon 8 Gen 3; note these are the **DSP-side** builds, not the
  `*Stub.so` host-side ones that go in the APK)

Debug DSP logging: `adb shell "echo 0x1f > <dir>/com.example.tts.farf"`

## Current state

- `tts-sdk.jar`, `libtts.so`, `libtts_jni.so` fetched into `app/libs/`
- `MeloTtsSpeechSynthesizer` implements `SpeechToText`-style seam `SpeechSynthesizer`
- `AppContainer` picks MeloTTS when `TtsModels.resolve()` finds the model, else Android system TTS
- combined model built; **not yet pushed or tested on hardware** (device disconnected)
