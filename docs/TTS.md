# Real TTS: Qualcomm MeloTTS (VoiceAI TTS)

Speech output uses the **Qualcomm VoiceAI TTS SDK** (`voiceai_tts`, v1.1.1.0) with the
`melotts_en` model from AI Hub. Same rule as ASR: every detail below is read from the shipped
SDK or observed on the device, never guessed.

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
./scripts/build-melo-model.sh models/melotts_en_8gen3

# 4. push model + DSP libs, then install
./scripts/push-tts-model.sh models/melotts_en_8gen3/melo_en.64_bit.qnn_v2.50.0.qnn
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

AI Hub's "Qualcomm Voice AI" download ships the 8 sub-models **separately**:

```
encoder.bin  flow.bin  decoder.bin  bert_wrapper.bin
t5_encoder.bin  t5_decoder.bin  bert_en_tokenizer.bin  bert_normalizer.bin  config.json
```

`scripts/build-melo-model.sh` recombines them using Qualcomm's own
`qnn_model_generation.py`. That script is pure stdlib (`struct`, `argparse`, `math`) and the
pronunciation dictionaries it needs (`en_dict_generation/`, `en_g2p_dict_generation/`) ship
**pre-generated**, so no notebooks, no torch, no MeloTTS checkout — it runs in seconds:

```
melo_en.64_bit.qnn_v2.50.0.qnn   312 MB   magic "0LEM" / "MEL0MODE"
```

Argument mapping (AI Hub name → script flag):

| AI Hub file | `--flag` |
|---|---|
| `bert_wrapper.bin` | `--bert_model` |
| `bert_en_tokenizer.bin` | `--bert_tokenizer` |
| `bert_normalizer.bin` | `--bert_normalizer` |
| `encoder.bin` | `--melo_encoder_model` |
| `flow.bin` | `--melo_flow_model` |
| `decoder.bin` | `--melo_decoder_model` |
| `t5_encoder.bin` | `--g2p_enc_model` |
| `t5_decoder.bin` | `--g2p_dec_model` |

## API used

```kotlin
val tts = TTS.newInstance(callback)
tts.setLanguage("English")     // "English"/"Chinese"/"Spanish", not "en"
tts.setModelPath(modelFile)    // FILE
tts.setAudioEncoding("0")      // LINEAR16
tts.setSpeechRate("1.0"); tts.setPitch("0.0"); tts.setVolumeGain("0.0")
tts.setSampleRate("44100")
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

The SDK only *produces* PCM — playback is the caller's job, so `MeloTtsSpeechSynthesizer`
creates the `AudioTrack` from the values in `onStart` rather than hardcoding 44100.

The guide recommends keeping the engine initialised between utterances (model load is
expensive), so `init()` happens once on a worker thread and `deInit()` only in `shutdown()`.

## Device-side files (not in the APK)

Pushed by `scripts/push-tts-model.sh` into
`/sdcard/Android/data/com.megathon.incidentassistant/files/tts/` (no root needed, unlike the
sample's `/vendor/etc/tts/`):

- `melo_en.64_bit.qnn_v2.50.0.qnn`
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
