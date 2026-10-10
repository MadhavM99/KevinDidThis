# Real ASR: Qualcomm Whisper (VoiceAI)

Speech-to-Text is implemented against the **Qualcomm VoiceAI ASR SDK** (`VoiceAI_ASR`, v2.7.1.0)
using the `whisper_small` model from [AI Hub](https://aihub.qualcomm.com/models/whisper_small?runtime=voice_ai).

Everything below was read from the shipped SDK, not written from memory.

## Sources of truth

| What | Where |
|---|---|
| Android API guide | `/opt/qcom/VoiceAI_ASR/2.7.1.0/whisper_sdk/doc/npu/rpc/80-15314-12_AE_Whisper_SDK_for_Android_Developer_Guide.pdf` (Rev. AE, June 2026) |
| Java API surface | `whisper-sdk.jar` (`javap`) |
| Lifecycle internals | `whisper_sdk/src/npu/rpc/core/whisper_sdk/java/android_sdk/.../Whisper.java` |
| VAD / buffering / stop semantics | `whisper_sdk/src/npu/rpc/core/whisper_sdk/cpp/whisper_function/src/TranscriptionManager.cpp` |
| Sample app | `whisper_sdk/sampleapp/npu_rpc_android_sample/java/` |
| AI-HUB model file notes | `/opt/qcom/VoiceAI_ASR/2.7.1.0/README-AIHUB.txt` |

## Setup

```bash
# 1. one-time: install the SDK (needs sudo + QPM login)
qpm-cli --install VoiceAI_ASR -v 2.7.1.0

# 2. copy the Java API + native libs into app/libs/ (~32 MB, git-ignored)
./scripts/fetch-whisper-sdk.sh

# 3. build
./gradlew :app:assembleDebug

# 4. push model files (see below), then install
./scripts/push-whisper-models.sh <dir>
./gradlew :app:installDebug
```

## Model files are NOT bundled

`Whisper.init(path)` lists one directory and auto-detects, in this order
(`Whisper.java:203-221`):

- `vocab.bin`
- `encoder_model_htp.bin` + `decoder_model_htp.bin`
- falls back to `encoder_model_cached.dlc` / `.ggml` variants if the `.bin` pair is absent

`path` is also passed as the CDSP path and appended to `DSP_LIBRARY_PATH`
(`TranscriptionManager.cpp:69-77`), so the DSP-side skel lives there too:

- `libQnnHtpVxxSkel.so`

Two locations are probed (`WhisperModels.resolve`), in order:

1. `/sdcard/Android/data/com.megathon.incidentassistant/files/whisper` — no root needed
2. `/vendor/etc/whisper` — what Qualcomm's sample uses; needs `adb root && adb remount`

### Where the files come from

Per `README-AIHUB.txt`:

> Only `encoder_model_htp.bin` and `decoder_model_htp.bin` can be downloaded from AI-HUB,
> while `vocab.bin` needs to be generated through notebook.

So the AI Hub page's **Download Model** button on the `runtime=voice_ai` tab is *not* what you
want. Instead:

1. https://aihub.qualcomm.com/models/whisper_small → Download Model
2. runtime **"Qualcomm AI Runtime"**, device = your target phone
3. download **HfWhisperEncoder** and **HfWhisperDecoder**, rename to
   `encoder_model_htp.bin` / `decoder_model_htp.bin`
4. note the **QNN (QAIRT) version** shown under *TorchScript → Qualcomm AI Runtime → See more
   metrics* — the QNN package version must match
5. generate `vocab.bin`:
   ```bash
   cd /opt/qcom/VoiceAI_ASR/2.7.1.0/notebook/whisper/npu/whisper_vocab
   python3 -m pip install openai-whisper
   python generate_whisper_vocab.py   # -> output/vocab.bin
   ```

### HTP skel version by SoC

From `sampleapp/npu_rpc_android_sample/java/README.md`:

| SoC | HTP |
|---|---|
| SM8975 | V85 |
| SM8850 / SM8845 | V81 |
| SM8750 | V79 |
| SM8650 | V75 |
| QCS8550 / SM8550 / SM8635 / SM7750 / SM7635 | V73 |
| QCM6490 | V68 |

The matching **host-side** stub (`libQnnHtpVxxStub.so`) ships in the APK for every version above,
so one APK covers all of them; only the device-side skel is per-SoC.

## API contract used

```kotlin
whisper = Whisper.newInstance()
whisper.setListener(listener)
whisper.enablePartialTranscriptions(true)     // default false
whisper.enableContinuousTranscription(false)  // default false: one utterance per tap
whisper.setNoStartSpeechTimeout(15_000)
whisper.setEndOfSpeechTimeoutMs(1_200)        // SDK default
whisper.setPartialBufferMs(1_000)             // SDK default 3000
whisper.init(modelDir)                        // NO_ERROR / INVALID_PARAMS / UNKNOWN
whisper.start(inputStream)                    // SDK pulls 1280-byte frames
whisper.stop()
whisper.deInit()
```

Listener callbacks (`WhisperResponseListener`):

```java
void onTranscription(String text, String language, boolean isFinal, int code, Bundle extras);
void onError(int errorCode, Exception ex);
void onRecordingStopped();
void onSpeechStart();
void onSpeechEnd();
default void onFinished();
```

`code` is one of `VALID_TRANSCRIPTION(0)`, `INVALID_SPEECH_TOKEN(1)`,
`INVALID_LANGUAGE_TOKEN(2)`, `PROGRESS_INDICATOR(3)`. Only `VALID_TRANSCRIPTION` is text worth
keeping. Errors: `NOT_INITIALIZED_ERROR(-2)`, `INVALID_PARAMETERS_ERROR(-3)`,
`NO_SPEECH_TIMEOUT(-4)`.

**Final segments are not cumulative.** `processFullBuffer()` posts only the current buffer, and
the sample app accumulates them itself (`MainActivity.java`: `mFinalAccum.append(...)`), so
`WhisperSpeechToText` maintains its own `finalText` accumulator. A later final does *not*
contain earlier ones (guide §1.1.1).

## The `stop()` trap

This is the one non-obvious thing, and it cost a re-read of `TranscriptionManager::runnable()`:

In **non-continuous mode** a final transcription is only produced when the VAD reports
end-of-speech (`command_end_detected`). At that point the SDK calls `processFullBuffer()`,
flushes, stops itself and fires `EVENT_ON_FINISHED`.

If instead you call `whisper.stop()` early:

- `stopAsync()` sets `mIsRunning = false`
- the runnable breaks out of the loop
- **only the audio-file branch** processes a remaining buffer
  (`if (getUseAudioFile() && getStopped())`), live-stream audio is dropped

So calling `stop()` mid-sentence silently loses the tail. `WhisperSpeechToText.stopListening()`
therefore:

1. flips the mic stream to silence (`goSilent()`), so the VAD observes end-of-speech naturally
2. waits up to 4 s for `onFinished()`
3. only then calls `whisper.stop()` as a fallback, and waits up to 2 s more
4. returns whatever final segments accumulated

## Audio

Required (guide §2.5): **16 kHz, mono, PCM 16-bit**. The SDK reads 1280-byte (40 ms) frames from
the `InputStream` you pass to `start()`, on its own worker thread, and treats `read() <= 0` as
end-of-stream. `MicInputStream` implements this: blocking `AudioRecord.read(..., READ_BLOCKING)`
until `goSilent()`/`release()`.

## Manifest

```xml
<application ...>
    <uses-native-library android:name="libcdsprpc.so" android:required="true" />
</application>
```

**Gotcha:** AAPT's schema declares `AndroidManifestUsesNativeLibrary` with
`parent="AndroidManifestApplication"`, so this must be a child of `<application>` — placing it
under `<manifest>` (as the guide's snippet suggests) fails the build with
`unexpected element <uses-native-library> found in <manifest>`.

## Current state

- Compiles; `assembleDebug` succeeds; `./gradlew test` passes.
- APK contains the Whisper Java classes (dex) and all 23 native libs under `lib/arm64-v8a/`.
- `AppContainer` picks `WhisperSpeechToText` **only if** `WhisperModels.resolve()` finds the
  three files; otherwise it keeps `PlaceholderSpeechToText` so the app still runs on a phone
  that has never had models pushed to it.
- Untested on hardware — no device was connected during development. First run should check
  `adb logcat -s WhisperSTT`.
- Next placeholder to remove: `PlaceholderLlm` (the LLM extraction stage).
