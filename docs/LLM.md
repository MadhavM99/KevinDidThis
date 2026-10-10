# On-device LLM: Gemma 4 E2B via Qualcomm GenieX

Extraction runs on **Gemma-4-E2B-it** (Google, Apache-2.0), Q4_0 GGUF, through
**Qualcomm GenieX** on its **llama.cpp runtime**. This doc covers how that was verified and
how the voice loop is put together. Same rule as `ASR.md` / `TTS.md`: everything comes from
Qualcomm's published sources or the shipped binaries, nothing is assumed.

## Feasibility: what Qualcomm actually ships for Gemma 4 E2B

| Question | Answer | Source |
|---|---|---|
| Exact model | `google/gemma-4-E2B-it`, instruction-tuned, "2B (effective)", 256K max context, Apache-2.0 | `qai_hub_models/models/gemma_4_e2b_it/manifest.yaml`, `__init__.py` (`HF_REPO_NAME`) |
| AI Hub asset | **one** asset: runtime `geniex_llamacpp`, precision `q4_0`, chipset "universal" → `google/gemma-4-E2B-it-qat-q4_0-gguf/gemma-4-E2B_q4_0-it.gguf` (3.04 GB) | `release-assets.yaml`, `manifest.yaml` (`orchestrator_runtimes: [geniex_llamacpp]`, `supported_precisions: [q4_0]`) |
| QAIRT / Genie context-binary bundle | **None.** The E2B folder has no `export.py` / `model.py` (E4B has them), so `python -m qai_hub_models.models.gemma_4_e2b_it.export` does not exist | `ai-hub-models` repo tree, `src/qai_hub_models/models/gemma_4_e2b_it/` |
| QAIRT variant planned? | Requested in qualcomm/ai-hub-models#330 ("Add GenieX-QAIRT support for gemma_4_e2b_it"); maintainers "will consider", then closed as *not planned* (22 Sep 2026) | GitHub issue #330 |
| Snapdragon 8 Gen 3 (SM8650) listed? | **No.** `supported_chipsets`: 8 Elite (for Galaxy), 8 Elite Gen 5 (for Galaxy), X Elite, X Plus, X2 Elite, QCS8275/5121/7181/8750/9075. The AI Hub page also says "currently not supported on any All Models chipset" | `perf.yaml`, aihub.qualcomm.com/models/gemma_4_e2b_it |
| GenieX validated on SM8650? | No. Validated: SM8750, SM8850 (Android); docs say it "may run on other Snapdragon parts" | geniex.aihub.qualcomm.com → Platforms |
| GenieX binaries for SM8650? | **Yes for llama.cpp:** the `geniex-android:0.7.1` AAR contains `libggml-hexagon.so` + `libggml-htp-v73/v75/v79/v81.so` (v75 = SM8650), `libggml-opencl.so` (Adreno) and `libggml-cpu.so`. **No for QAIRT:** its QNN HTP libs are V79/V81 only | `unzip -l geniex-android-0.7.1.aar` |
| Reference numbers (not our device) | Galaxy S25 (8 Elite), llama.cpp, ctx 4096: NPU 1411 prefill tok/s, 27.5 decode tok/s; GPU 225 / 18.8; CPU 91 / 20.2 | `perf.yaml` (`tool_versions.geniex: v0.8.0`) |

**Conclusion.** The design's original route (AI Hub `genie_bundle` → GenieDialog JNI, as in
the QIDK AI-Assistant sample) **cannot load Gemma 4 E2B**: Qualcomm does not produce that
bundle for it. The supported route for this exact model is GenieX + llama.cpp + the Q4_0
GGUF. On SM8650 this route is *expected* to work (the v75 Hexagon kernels, OpenCL and CPU
backends ship in the AAR) but is **not validated by Qualcomm and not yet run on our QIDK**.
No other model was substituted. If Gemma proves unusable on the device, the fallback is a
model Qualcomm ships as a QAIRT bundle (e.g. `ai-hub-models/Qwen3-4B-Instruct-2507`, NPU,
GenieX `qairt` runtime). That is a one-line change in `AppContainer`, but it is a decision to make, not a default.

## Runtime integration

`ai/GemmaGenieXLlm.kt` implements the existing `LlmBackend` seam (prompt in, raw text out).
Nothing else in the app knows the model or runtime exists.

| Step | GenieX API (0.7.1, verified with `javap` + the tagged Kotlin sources) |
|---|---|
| Init | `GenieXSdk.getInstance().init(context, callback)` registers `libgeniex_plugin_llama_cpp.so` |
| Import side-loaded weights | `ModelManagerWrapper.pullFlow(ModelPullInput(model_name="local/gemma-4-e2b-it-q4_0", hub=HubSource.LOCALFS, local_path=<dir>, model_type=ModelType.LLM))`. `ModelType.LLM` = text only, no mmproj |
| Load | `LlmWrapper.builder().llmCreateInput(LlmCreateInput(model_path, config=ModelConfig(nCtx=4096), runtime_id="llama_cpp", compute_unit=…)).build()`, tried with `npu` → `gpu` → `cpu` until one succeeds |
| Per turn | `reset()` (drop the previous turn's KV cache) → `applyChatTemplate([user message], tools=null, enableThinking=false)` (the GGUF's own Gemma template) → `generateStreamFlow(formattedText, GenerationConfig(...))` |
| Cancel | the native `generate()` blocks, so a cancelled turn calls `stopStream()` from a watcher coroutine |
| Release | `destroy()` (from `IncidentViewModel.onCleared`, same as Whisper's `release()`) |

Lifecycle guarantees: loaded once at start-up (`prepare()`, off the main thread), reused for
every turn, one generation at a time (mutex), load failure goes to `LlmStatus.Failed`
(shown on screen) instead of crashing. Logs carry token counts and timings only, never
prompts, outputs or transcripts.

### Inference configuration

| Setting | Value | Why |
|---|---|---|
| Context | 4096 | prompt is ~1–1.5k tokens for the incident form; one of AI Hub's two benchmarked context lengths |
| Max output tokens | 320 | a `record_fields` call for ~6 fields is ~250 tokens |
| Sampling | `topK=1`, `temperature=0.1` | greedy: repeatable extraction |
| Stop words | `</tool_call>` | stop right after the one call we need |
| Thinking | off (`enableThinking=false`) | no reasoning tokens to wait for or parse around |
| Timeout | 30 s per call (`ExtractionEngine`) | covers a first call still waiting on the model load |
| Compute unit | `npu` → `gpu` → `cpu` | first that loads; the chosen one is shown on screen and in logcat |

No grammar/GBNF constraint yet (`SamplerConfig.grammarString` exists in GenieX). A
schema-generated grammar is the next reliability lever, but only once it can be tested on
the device.

### Tool-call protocol

GenieX's Android SDK has no `tools=` path to the model's native tool calling, so the
app-defined protocol from DESIGN §4.2 is used unchanged: the model returns one
`<tool_call>{"tool":"record_fields","arguments":{"updates":[…]}}</tool_call>`, and
`ToolCallParser` reads it (it also accepts fenced or bare JSON and OpenAI-style
`tool_calls`). The prompt (`ExtractionPromptBuilder`) is generated from the form: field
ids, labels, types, required flags, the schema's questions, option ids with their labels and
aliases, current values with their evidence, the pending question, and the last four
conversation lines. No field id is hardcoded anywhere in the pipeline.

## The voice loop

```
Kevin's ASR (Whisper) ──final transcript──▶ user reviews/edits ──Continue──▶ IncidentViewModel
   ▲                                                                            │
   │                                                                  IncidentSession.onUtterance
   │                                    fast intents (yes/no/skip/don't know/cancel/repeat) ─┐
   │                                    exact enum answer ("Medium.") ───────────────────────┤ no LLM
   │                                    ExtractionEngine → Gemma → ToolCallParser            │
   │                                    ToolExecutor: validate ids/types/enums, merge ───────┤
   │                                    DialogPolicy: mandatory-field gate → next action ◀───┘
   │                                                                            │
   └── user taps Start ◀── Kevin's TTS speaks the reply ◀── SessionReply ◀──────┘
```

* **Session** (`session/IncidentSession.kt`, pure Kotlin): one per draft; turns serialised
  by a mutex; a reply for a draft that was reset meanwhile is dropped by the view model.
* **Policy** (`session/DialogPolicy.kt`): required fields in schema order, one per turn → an
  unsure value is read back for a yes/no → after an unanswered ask and one rephrase, it offers
  "not specified" (only if `allow_unknown_required`) → optional `ask_once` fields as one
  combined question per `question_group` (catch-all for ungrouped ones), each asked once →
  spoken summary + "Should I file it?".
* **Unknown vs skip vs correction**: "I don't know"/"skip that" for a required field gets an
  audible confirmation before it becomes `unknown` (flagged in `missing_information`). For
  optional questions, skip/none/no closes the group. Corrections ("actually 11 AM") are
  extracted with status `answered` and overwrite the earlier value under the merge policy.
* **Model failure**: one repair retry, then the turn is recoverable. An answer to a
  free-text question is kept as the answer. Otherwise the user is asked again. Nothing is
  written to a field that already holds a different value.
* **Audio**: the ASR and TTS code is unchanged. `SpeechSynthesizer.speak()` has no
  completion callback, so the loop keeps the existing recorder contract: the reply is spoken
  and shown, and the user taps Start to answer. Recording already calls
  `synthesizer.stop()`, so mic and speaker never overlap. Hands-free auto-listen after TTS
  would need an `onDone` signal from the TTS seam.

### Submission safety

* Filing happens only on an explicit user "yes" in the confirmation phase, or the Review
  screen's Submit tap. A model `submit_report` call is ignored, and so is `ask_user`.
* The mandatory gate is re-checked at submit time, including for the Submit button.
* The report id is created once per draft and reused (idempotency key), and a second "yes"
  gets "already filed".
* The local save happens first. If it fails, the user hears that it was not filed. If the
  server POST fails, the user hears "saved on this device, but the server did not accept
  it", never "filed".

## Setup on the QIDK

```bash
./scripts/push-gemma-model.sh        # downloads gemma-4-E2B_q4_0-it.gguf (~3 GB) and adb-pushes it
./gradlew :app:installDebug
adb logcat -s GemmaLlm IncidentVM    # load time + compute unit, then per-turn timings
```

Without the GGUF on the device the app keeps the scripted `PlaceholderLlm` and shows the
demo banner.

`minSdk` is now 27 (the GenieX AAR's minimum). The AAR also ships `libQnnHtp.so`,
`libQnnSystem.so`, `libQnnHtpV79Stub.so` and `libQnnHtpV81Stub.so`, as does
`scripts/fetch-whisper-sdk.sh`. `pickFirsts` keeps one copy of each, and llama.cpp doesn't
use them. Confirm that Whisper still initialises after this change (see the device checklist).

## On-device acceptance (still to run on the QIDK)

1. GGUF imports and loads: note the compute unit that loads (`npu`/`gpu`/`cpu`), the load
   time and RAM.
2. The golden utterance spoken through Whisper fills five fields, and the severity question
   is spoken.
3. "Medium" gets the optional questions and then the spoken summary. "Yes" files the report.
4. "Actually it was 11 AM" updates `occurred_at`.
5. Airplane mode: the whole flow works. No LLM traffic at any point (the only network use is
   the form server).
6. Per-turn timings from `IncidentVM` logs against the §2.2 budget (median < 2.5 s end of
   speech → reply).
7. Extraction quality on the ten scenario utterances (`ExtractionScenariosTest`), spoken.
8. Whisper and MeloTTS still initialise with the GenieX AAR in the APK.
