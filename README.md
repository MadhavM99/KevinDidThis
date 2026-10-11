# Incident Report Assistant (Optimus Prime) — Megathon 2026 · Qualcomm PS

A fully **on-device, voice-driven incident-report assistant** for the Qualcomm QIDK
(Snapdragon 8 Gen 3 / SM8650, Android). Speak a report in natural language; the app
transcribes it, extracts the incident fields with an on-device LLM using **function/tool
calling**, conversationally asks for anything missing, and outputs a structured
JSON report.

> **Pipeline:** speech → text (Whisper, NPU) → LLM extraction (Gemma, NPU) →
> mandatory-field loop (tool calling) → form fill → screen + TTS (complementary) →
> report JSON (optional server). **Everything runs on-device — no cloud calls.**

---

## ✨ Features

- **Siri-style capture UI** — immersive recording stage with live caption, waveform and
  status pills; one-tap **home-screen widget** launches and starts recording immediately.
- **On-device AI (no cloud)**:
  - **ASR** — Qualcomm Whisper SDK on the Hexagon NPU, with VAD-based hands-free turn taking.
  - **LLM** — Gemma 4 E2B (Q4_0 GGUF) through Qualcomm **GenieX** on the NPU, with strict
    tool-calling: the model can only `record_fields`; `ask_user`/`submit_report` are
    ignored — the app's dialog policy owns questions, and submission happens only on the
    user's explicit confirmation.
  - **TTS** — Piper (VITS) via sherpa-onnx on the CPU (faster than real time; the NPU stays
    free for ASR + LLM), with Android system TTS as fallback. TTS is complementary —
    **never blocks** the text flow.
- **Any form works — zero hardcoding**:
  - A form is a plain **HTML `<form>` page** (or JSON schema): normalizer → one shared
    form engine. Labels, `required`/`aria-required`, `<select>`/radio options and types are
    read from the page itself; nothing domain-specific is baked in.
  - **Mandatory-field gate**: the assistant detects missing required fields and asks
    targeted follow-ups until the form is complete — then confirms before submitting.
- **Report JSON out** — values + confidence + evidence + transcript + turn metrics,
  POSTed to an optional server or the bundled mock (`tools/mock_proxy.py`).
- **Hands-free loop** — the assistant re-listens automatically after each reply; fully
  usable by voice alone, and always usable via text if speech fails.

## 🧠 AI models & external resources

| Model | Purpose | Runs on | License | Link |
|---|---|---|---|---|
| Qualcomm **Whisper** (quantized, +VAD) | Speech-to-text | Hexagon NPU (VoiceAI ASR SDK) | QPM license | https://aihub.qualcomm.com/models/whisper_small · https://qpm.qualcomm.com |
| **Gemma 4 E2B-it** (`gemma-4-E2B_q4_0-it.gguf`) | Extraction + tool calling | Hexagon NPU (GenieX) | Apache-2.0 | https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf |
| **Piper en_US** (`vits-piper-en_US-lessac-medium`) | Speech synthesis | CPU (sherpa-onnx) | MIT | https://github.com/k2-fsa/sherpa-onnx |

| SDK / library | Version | Purpose | Link |
|---|---|---|---|
| VoiceAI ASR SDK (`voiceai_asr`) | latest on QPM | Whisper NPU runtime + VAD | https://qpm.qualcomm.com |
| GenieX Android | 0.7.1 | NPU LLM runtime (GGUF/QAIRT) | https://geniex.aihub.qualcomm.com |
| sherpa-onnx | 1.13.8 | Piper TTS engine (Android AAR) | https://github.com/k2-fsa/sherpa-onnx |
| Jetpack Compose / Material 3 | BOM 2024.06 | UI + widget | https://developer.android.com/jetpack/compose |
| kotlinx-serialization-json | 1.7.1 | JSON schema ↔ form model | https://github.com/Kotlin/kotlinx.serialization |
| **jsoup** | 1.18.1 | HTML `<form>` → dynamic form schema | https://jsoup.org |
| Platform HTTP (`HttpURLConnection`) | — | optional form fetch / report POST | https://developer.android.com |
| mock proxy (`tools/mock_proxy.py`) | — | stand-in form/report server | `docs/PROXY_API.md` |

Full attributed report: **`docs/REPORT.md`**.

## 📦 Setup (build machine + QIDK)

Prereqs: Android SDK (compileSdk 34, minSdk 27), JDK 17, a QIDK (Snapdragon 8 Gen 3)
device with models pushed.

```bash
# 1. Fetch on-device SDKs (git-ignored, lands in app/libs/)
./scripts/fetch-whisper-sdk.sh            # VoiceAI ASR SDK (JAR + DSP RPC libs + VAD)
./scripts/fetch-piper-tts.sh              # sherpa-onnx AAR + Piper voice, pushed to device

# 2. Push AI models to the device
./scripts/push-whisper-models.sh          # quantized Whisper models
./scripts/push-gemma-model.sh             # Gemma 4 E2B Q4_0 GGUF (~3 GB, Apache-2.0)
./scripts/push-form.sh                    # bundled form pages (tools/forms/)

# 3. Build & test
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest          # unit tests: session, policy, extraction,
                                          # tool-call parser, HTML form parser, hands-free loop
```

Optional server mode: set `SERVER_BASE_URL` in `AppContainer.kt` (or `FORM_URL` for a
published HTML form page). Run the mock: `python3 tools/mock_proxy.py` — contract in
`docs/PROXY_API.md`.

## 🗂 Repo layout

```
app/src/main/java/com/megathon/incidentassistant/
  ai/       Whisper STT · Gemma/GenieX LLM · Piper (sherpa-onnx) TTS + Android fallback
  session/  IncidentSession · DialogPolicy · ExtractionEngine · ToolExecutor · FastIntents
  form/     FormSchema/FormState · ToolCallParser · HtmlFormParser (jsoup) · ReportJson
  net/      optional HTTP form source & report submitter (ProxyApi.kt)
  ui/       CaptureScreen (immersive) · ReviewScreen · home-screen widget · ViewModel
  data/     report repository, local profile, form reading store
scripts/    SDK fetch + model push scripts        tools/   mock_proxy.py · form examples
docs/       ASR · LLM · TTS · DESIGN · PROXY_API · REPORT
```

## 📚 Docs

- `docs/DESIGN.md` — plan of record (architecture, constraints, latency targets)
- `docs/ASR.md` · `docs/LLM.md` · `docs/TTS.md` — model runbooks (device-verified)
- `docs/PROXY_API.md` — optional server contract
- `docs/REPORT.md` — models & external-resources report with links

## 📄 License

MIT — see [LICENSE](LICENSE) (© 2026 Optimus Prime).