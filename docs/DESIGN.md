# Incident Assistant — Complete Design (Plan of Record)

> Voice-driven incident reporting on the Qualcomm QIDK (Snapdragon 8 Gen 3, Android).
> Pipeline: **speech → text → LLM extraction → mandatory-field gate → function/tool
> calling → form fill → screen + TTS → JSON out (pluggable into any API)**.
> Everything runs on-device; no cloud calls are required for normal operation.

Status: **DRAFT for review** — implementation starts after sign-off.
Last updated: 2026-10-10.

---

## 1. Goals, non-goals, constraints

### 1.1 Goals

1. A Siri-like, push-to-talk / open-mic assistant that takes natural spoken incident
   reports and auto-fills a structured form.
2. Extraction of incident fields from free speech using an **on-device LLM with
   function/tool calling**, not a fixed grammar of sentences.
3. A **mandatory-field gate**: the assistant detects missing required fields and asks
   targeted follow-up questions conversationally until the form is complete.
4. **User-defined forms**: any form is described by a JSON schema asset; prompts, tools,
   validation, UI and output all derive from that schema. No code changes to add a form.
5. Output is a single **report JSON** (values + confidence + evidence + transcript +
   metrics) that can be POSTed to any API, saved, or shared.
6. Fully local on QIDK: on-device ASR, on-device LLM (NPU), on-device TTS, offline.
7. Latency target: median turn < 2.5 s, p95 < 4 s (end of speech → assistant starts
   speaking the next question/confirmation).
8. **Widget-style one-tap flow**: a home-screen widget opens the assistant and **starts
   recording immediately** — the user speaks once, the pipeline runs, the screen shows
   "Done" and TTS says the report is filed.
9. **Proxy-provided forms**: the form schema is fetched from a configurable proxy API at
   runtime and the finished report is POSTed back to the same proxy (§8.1) — the app
   stays generic and can be pointed at any backend.

### 1.2 Non-goals (for v1)

- No fine-tuning of models (prompt + tool protocol only).
- No backend service we own: the app fetches the form from a user-supplied proxy and
  POSTs the finished report back (§8.1). No other network use.
- No multi-user auth, no database.
- No image/video evidence, no GPS/geolocation.
- No cloud fallback in production flow (a debug-only "OpenAI-compatible" backend exists
  for laptop development, never shipped enabled).
- Not a general chat assistant: the LLM only performs extraction + tool calls.

### 1.3 Constraints verified from Qualcomm sources

| Item | Fact |
|---|---|
| Device | QIDK, Android target platform, Linux host for development |
| SoC | Snapdragon 8 Gen 3 = SM8650 → Hexagon HTP **v75** (`libQnnHtpV75Stub.so`, `hexagon-v75/unsigned`) |
| Runtime | QAIRT (Qualcomm AI Runtime, a.k.a. QNN). Genie = LLM runtime inside QAIRT |
| LLM deploy | AI Hub `qai_hub_models` export → `genie_bundle` (ctx-bins + tokenizer + `genie_config.json`) → `adb push /data/local/tmp/` or app files dir; QAIRT version in bundle must match installed QAIRT |
| QIDK LLM sample | `AI-Assistant` uses Genie C++ API via JNI (`GenieDialog_*`), Llama-3.2-3B, streaming callback |
| QIDK ASR/TTS sample | `ASR-LLM-TTS` uses Whisper (Voice AI ASR) + MeloTTS (Voice AI TTS, `tts-sdk.jar` + `libtts.so` + `melo_*.qnn`) |
| Licensing | QIDK sample code: BSD-3-Clause-Clear (reuse with attribution) |

> **Note:** QIDK samples are verified on 8 Gen 2 / 8 Gen 3 / 8 Elite / 8 Elite Gen 5.
> 8 Gen 3 uses the **v75** DSP arch, not v79/v81.

---

## 2. System architecture

```
┌───────────────────────────── QIDK (Android, on-device, offline) ─────────────────────────────┐
│                                                                                              │
│  ┌─────────┐   PCM16    ┌──────────────┐  utterance   ┌────────────────────┐                  │
│  │ Audio   │  16 kHz    │ VAD +        │  audio       │  ASR backend       │  final text      │
│  │ capture │ ─────────▶ │ endpointing  │ ───────────▶ │  (Whisper / text)  │ ────────────┐    │
│  │ (mic)   │            │ (Silero/EN)  │              │  partials → UI     │             │    │
│  └─────────┘            └──────┬───────┘              └────────────────────┘             │    │
│                                │ speech during TTS → barge-in (stop playback)            │    │
│                                ▼                                                         ▼    │
│  ┌───────────────────────┐    ┌──────────────────────────────────────────────────────────────┐│
│  │  UI (Compose)         │    │                  IncidentSession  (state machine)           ││
│  │  • live transcript    │◀───│  fast intents → ExtractionEngine → ToolExecutor →           ││
│  │  • form auto-fill     │    │  DialogPolicy → (AskField | Confirm | Submit) →             ││
│  │  • JSON view + submit │    │  TtsBackend.speak(text)                                     ││
│  └───────────────────────┘    └───────────────┬──────────────────────────┬──────────────────┘│
│                                               │                          │                   │
│                        ┌──────────────────────▼─────┐          ┌─────────▼─────────────┐     │
│                        │ LLM backend (Genie JNI,    │          │ TTS backend (MeloTTS  │     │
│                        │ NPU, streaming callback)   │          │ NPU / Android TTS)    │     │
│                        └────────────────────────────┘          └───────────────────────┘     │
│                                                                                              │
│  FormState (schema-driven) ──▶ ReportBuilder ──▶ ReportSink: file | share | HTTP POST         │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Data flow per turn

1. Mic frames (PCM16, 16 kHz, 20 ms) → VAD.
2. Utterance ends on ~500 ms trailing silence → `AsrBackend.finalize()`.
3. Final transcript → `IncidentSession.onUtterance(text)`.
4. Fast-intent check (yes/no/skip/stop/repeat) before any LLM cost.
5. `ExtractionEngine`: build prompt (schema digest + current form state + pending
   question + last utterance) → `LlmBackend.generate()` → parse tool calls.
6. `ToolExecutor` applies `record_fields` with normalize/validate/merge rules
   (deterministic; the model never writes the form directly).
7. `DialogPolicy`: decide next speech = missing-required question, clarification,
   confirmation, or completion.
8. `TtsBackend.speak()` starts; UI updates simultaneously (form + transcript).
9. When required fields are complete → confirm → user "yes" → build report JSON →
   emit to sinks.

### 2.2 Latency budget (targets, on 8 Gen 3 NPU)

| Stage | Budget | Notes |
|---|---|---|
| VAD endpoint | 400–600 ms | trailing-silence config; push-to-talk mode = on release |
| ASR final (≤ 8 s utterance) | 150–500 ms | Whisper-Base/Small quantized, 30 s padded window |
| Extraction LLM | 500–1500 ms | 3–4B w4a16, ≤ 160 new tokens, low temp |
| Policy + form update | < 10 ms | pure Kotlin |
| TTS first audio | 100–300 ms | sentence-chunked MeloTTS; Android TTS fallback |
| **Turn total** | **1.2–2.8 s** | partials + speculative extraction are optional levers |

### 2.3 Threading model

- One `IncidentSession` actor on a single-threaded dispatcher → turns never interleave.
- ASR, LLM, TTS run on `Dispatchers.Default`; UI observes `StateFlow<UiState>`.
- Audio capture on its own thread (AudioRecord); VAD frames fed via channel.
- Barge-in: VAD speech onset → `TtsBackend.stop()` immediately (flush AudioTrack).

---

## 3. Form model (user-defined)

### 3.1 Schema asset — `assets/forms/incident_report.json`

```jsonc
{
  "id": "incident_report",
  "title": "Incident Report",
  "version": "1.0",
  "fields": [
    {
      "id": "incident_type", "label": "Incident Type", "type": "enum", "required": true,
      "prompt": "What kind of incident is it?",
      "enum": [
        { "value": "water_leak",      "label": "Water leak",      "aliases": ["leak", "flood", "water damage", "pipe burst"] },
        { "value": "fire",            "label": "Fire",            "aliases": ["smoke", "burning"] },
        { "value": "electrical",      "label": "Electrical",      "aliases": ["power", "short circuit", "sparking"] },
        { "value": "chemical_spill",  "label": "Chemical spill",  "aliases": ["spill", "hazardous material"] },
        { "value": "equipment_failure","label": "Equipment failure","aliases": ["machine down", "broken"] },
        { "value": "slip_trip_fall",  "label": "Slip / trip / fall","aliases": ["fell", "tripped"] },
        { "value": "security",        "label": "Security",        "aliases": ["theft", "intruder"] },
        { "value": "medical",         "label": "Medical",         "aliases": ["injury", "hurt"] },
        { "value": "other",           "label": "Other",           "aliases": [] }
      ]
    },
    {
      "id": "severity", "label": "Severity", "type": "enum", "required": true,
      "prompt": "How severe is it — low, medium, high, or critical?",
      "enum": [
        { "value": "low",      "label": "Low",      "aliases": ["minor", "small", "not serious"] },
        { "value": "medium",   "label": "Medium",   "aliases": ["moderate", "moderate severity"] },
        { "value": "high",     "label": "High",     "aliases": ["major", "serious", "severe"] },
        { "value": "critical", "label": "Critical", "aliases": ["emergency", "urgent", "life threatening"] }
      ]
    },
    { "id": "location",       "label": "Location",                "type": "string", "required": true,  "prompt": "Where did it happen?" },
    { "id": "occurred_at",    "label": "Date & Time",             "type": "datetime","required": true, "prompt": "When did it happen?" },
    { "id": "description",    "label": "Description",             "type": "text",   "required": true },
    { "id": "immediate_actions","label": "Immediate Actions Taken","type": "text",  "required": true,  "prompt": "What has been done so far to handle it?" },
    { "id": "persons_involved","label": "Persons Involved",       "type": "string_list", "required": false, "ask_once": true, "group": "people" },
    { "id": "equipment_involved","label": "Equipment / Asset",    "type": "string_list", "required": false, "ask_once": true, "group": "followup" },
    { "id": "witnesses",      "label": "Witness Information",     "type": "string_list", "required": false, "ask_once": true, "group": "people" },
    { "id": "recommended_actions","label": "Recommended Follow-up","type": "text",   "required": false, "ask_once": true, "group": "followup" },
    {
      "id": "action_status", "label": "Current Action Status", "type": "enum", "required": false,
      "group": "followup", "ask_once": true, "default": "open",
      "enum": [
        { "value": "open",        "label": "Open",         "aliases": [] },
        { "value": "in_progress", "label": "In progress",  "aliases": ["being handled", "ongoing"] },
        { "value": "monitoring",  "label": "Monitoring",   "aliases": ["watching"] },
        { "value": "resolved",    "label": "Resolved",     "aliases": ["fixed", "closed", "done"] }
      ]
    }
  ],
  "question_groups": [
    {
      "id": "people",
      "prompt": "Were any other people involved, or any witnesses you'd like to note?"
    },
    {
      "id": "followup",
      "prompt": "Was any equipment or asset involved, any follow-up actions you'd recommend, and is the situation still open, in progress, monitoring, or resolved?"
    }
  ],
  "behavior": {
    "ask_optional_once": true,
    "optional_mode": "combined",        // combined: one natural question per group | individual
    "confirm_before_submit": true,
    "allow_unknown_required": true
  }
}
```

### 3.2 Field types and normalization rules

| Type | Normalization | Rejection behavior |
|---|---|---|
| `string` | trim, collapse spaces, preserve proper nouns | empty → reject |
| `text` | trim, keep sentence(s) | empty → reject |
| `enum` | case-insensitive match on value/label/aliases, then substring ("medium severity" → `medium`) | no match → **do not guess**; policy asks with options |
| `datetime` | parse `HH:mm am/pm`, ISO, "today/yesterday at X", "around X" (drop hedge word) → ISO-8601, combine time-only with session date | unparsable → keep raw + `needsReview` |
| `string_list` | split on `,` / `;` / " and " / " plus " | empty → reject |
| `number` | `int`/`float` parse | non-numeric → reject |

### 3.3 Merge / conflict policy (deterministic)

- New value with confidence ≥ existing − 0.05 → replace; record history.
- A value produced while its field was the **pending question** is treated as explicit
  (`status = ANSWERED`, confidence floor 0.8).
- Explicit user corrections ("actually, make it 11") always win (higher recency +
  explicit intent from the model with confidence ≥ 0.5).
- Never overwrite a value with a different value at confidence < 0.4; instead mark
  `AMBIGUOUS` and ask a clarification question.
- Every write stores `evidence` (quote) and `utteranceIndex` for provenance.

### 3.4 Mandatory-field gate

- `required: true` fields must be either `FILLED` or explicitly `UNKNOWN` (user said
  "I don't know / skip"). Both satisfy presence; `UNKNOWN` is flagged in the report.
- Gate is enforced by the app, **not** by the model: `ToolExecutor.submit_report` is
  only honored when `state.gatePasses()` and the user has confirmed.
- Completeness of required fields is guaranteed by the **required-field loop** (§5.2):
  the assistant keeps asking until every required field is filled (or explicitly marked
  "not specified" after an audible confirmation), so a report is always submittable.

### 3.5 Form source & caching (proxy)

- Primary source: `GET {proxy}/api/v1/forms/{form_id}` (§8.1) at app start / on demand.
- Cache: the last good schema is persisted (`filesDir/forms/<id>.json`); the bundled
  `assets/forms/incident_report.json` is the always-available fallback (demo-safe even
  offline). Cache refreshes when `version` changes upstream.
- A schema change never affects a running session; the next session picks it up.
- Any JSON with the §3.1 shape works — adding a form to the proxy needs **zero app
  changes**.

---

## 4. LLM extraction + function calling

### 4.1 Model recommendation

| Role | Model | Why |
|---|---|---|
| Primary LLM | **Qwen3-4B-Instruct-2507** (w4a16 QAIRT bundle or GGUF Q4_0) | strong instruction following + JSON reliability, thinking mode can be disabled; supported on 8 Gen 3 |
| Fallback LLM | **Llama-3.2-3B-Instruct** (w4a16) | exactly what the QIDK `AI-Assistant` sample ships and validates |
| ASR | **Whisper-Base** (fast) / **Whisper-Small** or **Large-V3-Turbo-Quantized** (accurate) | AI Hub pre-compiled; Voice AI ASR SDK on QIDK |
| VAD | Silero VAD (ONNX via onnxruntime-android) or energy VAD | tiny, CPU, reliable endpointing |
| TTS | **MeloTTS EN** (Voice AI TTS SDK, NPU) | QIDK ASR-LLM-TTS sample stack |
| TTS fallback | Android system TTS (offline) | zero setup, guarantees demo path |

### 4.2 Function/tool calling protocol (app-defined, strict)

Small on-device models are not reliable with free-form agent loops. Instead:

- **One exchange per user utterance** — the model never drives multi-step logic.
- The model must emit **exactly one JSON object** describing a tool call; the app parses,
  validates and executes it. This *is* function calling: declared tools, typed arguments,
  execution in app code, results reflected in the form and in the next prompt.

Three tools (OpenAI-compatible JSON schemas; also used verbatim as `tools=` for
GenieX/OpenAI-style runtimes):

```jsonc
// 1) record_fields — the workhorse
{ "name": "record_fields",
  "parameters": { "updates": [ {
      "field": "<field_id from schema>",
      "value": "<string; canonical enum value for enums>",
      "confidence": 0.0,
      "evidence": "<short quote from the user's words>",
      "status": "answered | inferred | unknown"
  } ] } }

// 2) ask_user — optional clarification (app policy may override)
{ "name": "ask_user",
  "parameters": { "field": "<field_id>", "question": "<one short spoken question>" } }

// 3) submit_report — only honored when the mandatory gate passes AND user confirmed
{ "name": "submit_report", "parameters": { "summary": "<optional one-liner>" } }
```

Canonical wire format in prompts (works on plain Genie without any tool API):

```
<tool_call>{"tool":"record_fields","arguments":{"updates":[{"field":"incident_type",
"value":"water_leak","confidence":0.95,"evidence":"there was a water leak"}]}}</tool_call>
```

Parser accepts, in priority order:
1. OpenAI `tool_calls` objects (if the runtime returns them),
2. `<tool_call>…</tool_call>` blocks (GenieX / Qwen style),
3. bare JSON object / fenced ```json block,
4. multiple objects (all parsed, applied in order).

Hardening: strip markdown fences and prose; bracket-balance; drop trailing commas;
one automatic "return only JSON" retry; then fall back to storing the utterance text in
`description` so user data is never lost.

Optional extra hardening for llama.cpp-based runtimes (GenieX `llama_cpp`): ship a
`grammar.gbnf` generator (schema-driven, union of the three tools) so decoding is
constrained. Genie/QAIRT has no grammar knob today → rely on few-shots + parser.

### 4.3 System prompt (assembled per turn)

```
You are the extraction engine of an incident-reporting assistant. You never chat.
You receive: FORM SCHEMA, CURRENT FORM STATE, LAST QUESTION ASKED (maybe none),
RECENT UTTERANCES, and the USER'S LATEST UTTERANCE.

Rules:
1. Extract only facts stated or unambiguously implied. Never invent values.
2. Enums: output the canonical `value`, not the label. Use aliases.
3. Date/time: resolve relative phrases using NOW = {now}; time-only → today.
4. If a field was just asked and the answer maps to it, set status "answered".
5. "I don't know / skip" for a field → status "unknown".
6. Confidence: 0.9+ explicit; 0.6–0.9 implied; <0.6 ambiguous.
7. Output exactly ONE JSON object, no prose, no markdown:
   <tool_call>{"tool":"record_fields","arguments":{"updates":[…]}}</tool_call>
8. Only use "ask_user" if you cannot record anything and one clarification is essential.
9. Only use "submit_report" if the user explicitly confirms submission.

FEW-SHOT:
USER: There was a water leak near conference room 2 around 10am. Maintenance has already isolated the area.
OUTPUT: <tool_call>{"tool":"record_fields","arguments":{"updates":[
 {"field":"incident_type","value":"water_leak","confidence":0.95,"evidence":"water leak","status":"inferred"},
 {"field":"location","value":"Conference Room 2","confidence":0.9,"evidence":"conference room 2","status":"inferred"},
 {"field":"occurred_at","value":"2026-10-10T10:00:00","confidence":0.7,"evidence":"around 10am","status":"inferred"},
 {"field":"immediate_actions","value":"Area isolated by maintenance","confidence":0.9,"evidence":"maintenance has already isolated the area","status":"inferred"},
 {"field":"description","value":"Water leak near Conference Room 2","confidence":0.8,"evidence":"…","status":"inferred"}]}}</tool_call>
```

Prompt budget: ≤ ~1.2k tokens in, ≤ 200 out. Keep the schema digest compact (ids + type +
enum values only, not full JSON).

### 4.4 Golden example (acceptance test)

Input utterance:
> "There was a water leak near conference room 2 around 10am. Maintenance has already
> isolated the area."

Expected state: `incident_type=water_leak`, `location="Conference Room 2"`,
`occurred_at=2026-10-10T10:00`, `immediate_actions="Area isolated by maintenance"`,
`description` = cleaned sentence. Missing required: `severity` → assistant asks
"How severe is it — low, medium, high, or critical?" Then "medium" → gate passes →
confirmation → submit.

This exact scenario ships as an automated core unit test (scripted LLM outputs) and as
the on-device demo script.

---

## 5. Dialog & session state machine

### 5.1 States

```
IDLE ──start──▶ LISTENING ──speech──▶ CAPTURING ──endpoint──▶ TRANSCRIBING
   ▲                                     │                        │
   │                              barge-in │                        ▼
   │                                     ▼                   EXTRACTING
   │                                  SPEAKING ◀──ASKING ◀──(missing required)
   │                                     │                        │
   │                                     │                  gate passes
   │                                     ▼                        ▼
   └──────────────────────────── CONFIRMING ──"yes"──▶ SUBMITTING ──▶ DONE
```

### 5.2 Policy (deterministic, app-side)

1. **Required-field loop (guaranteed completeness).** After tool execution, while any
   required field is missing the turn always ends in a question — never in submission,
   never silently:
   - missing → `AskField(next missing required)` in schema order, one question per turn;
   - unmatched enum answer → `Clarify` re-asking with the option labels;
   - answer with no usable info → one rephrase retry;
   - "I don't know / not sure" → after an audible confirmation ("I'll mark this as not
     specified — okay?") the field becomes `UNKNOWN`, counts as present, and is flagged
     in the report under `missing_information` (**approved behavior** — required fields
     never hard-block submission);
   - if one utterance answers several missing fields, all are applied at once and only
     the remaining ones are asked.
   (10 fields, 6 required, 4 filled by the first utterance → the assistant asks the
   remaining 2 required fields one by one before moving on.)
2. If a required enum got an unmatchable answer → re-ask with option labels.
3. Required complete → remaining optional `ask_once` fields are asked as **one natural,
   combined question per `question_group`** (schema-defined), never field-by-field —
   e.g. people + witnesses in one sentence, then equipment + follow-up + status in
   another. The single answer is extracted into all matching fields at once; optional
   fields the user doesn't address are marked `skipped` and never re-asked. Whole-group
   skip phrases ("skip", "no", "nothing to add") close that group in one turn.
   If a schema defines no groups, all remaining optionals are bundled into one natural
   catch-all question (`optional_mode: "combined"`, the default; "individual" restores
   old behavior). v1 uses the schema-defined wording — deterministic and demo-safe;
   LLM-rephrased phrasing is a later enhancement.
4. All done → spoken summary + "Should I file this report?" → fast-intent yes/no.
5. On yes → report JSON built, saved locally, POSTed to the proxy, spoken confirmation
   ("Report filed. Reference rpt-…"), and the screen + home-screen widget show **Done**.
6. Corrections anytime: extraction updates win per merge policy; then re-evaluate.

### 5.3 Fast intents (no LLM call)

| Phrases | Action |
|---|---|
| yes / yeah / correct / file it / submit | confirm / submit if in CONFIRMING |
| no / not yet / wait | stay in CONFIRMING, ask what to change |
| skip / unknown / no idea / not sure | answer pending field as `UNKNOWN` |
| stop / cancel / abort | cancel pending question or submission (2-step for abort) |
| repeat / say again | re-speak last assistant message |
| start over / new report | reset FormState (with confirmation if fields filled) |

Only if no fast intent matches does the utterance go to the extraction LLM.

### 5.4 Session events (UI/TTS contract)

```kotlin
sealed interface SessionEvent {
    data class PartialTranscript(val text: String) : SessionEvent
    data class FinalTranscript(val text: String) : SessionEvent
    data class FieldUpdated(val fieldId: String, val value: String, val confidence: Double,
                            val evidence: String?, val status: FieldStatus) : SessionEvent
    data class AssistantMessage(val text: String, val kind: MsgKind) : SessionEvent // ASK, CONFIRM, INFO, ERROR
    data class StateChanged(val state: SessionState) : SessionEvent
    data class ReportReady(val json: String) : SessionEvent
    data class Error(val message: String, val recoverable: Boolean) : SessionEvent
}
```

---

## 6. ASR design (streaming, on-device)

### 6.1 Pipeline

```
AudioRecord 16 kHz mono PCM16 → ring buffer → VAD (Silero ONNX, 512-sample frames)
    → speech onset: start utterance buffer, emit UI "listening"
    → trailing silence ≥ 500 ms: finalize → Whisper window → transcript
    → optional: every ~1.0 s while speaking, re-decode the rolling window → partials
```

- **Utterance window**: pad/trim to Whisper's 30 s input; utterances > 28 s are chunked
  with 1 s overlap and stitched on word boundaries.
- **Partials** are UX sugar (live transcript). They are cheap only if the encoder is
  fast; default ON with Whisper-Base, OFF with larger models (`Config.partials`).
- **Push-to-talk mode**: press-and-hold mic → no VAD needed, finalize on release.
  This is the demo-critical mode (reliable in noisy halls).
- **Barge-in**: any VAD speech onset while TTS is playing → `TtsBackend.stop()` and
  transition to CAPTURING.

### 6.2 Backends behind `AsrBackend`

| Backend | Status | Notes |
|---|---|---|
| `WhisperQnnAsr` | primary on device | port of QIDK ASR-LLM-TTS `Whisper_ASR.java` + `native-lib.cpp` + `jniLibs` (Voice AI ASR SDK); BSD-3-Clause-Clear |
| `TextAsr` | always available | typed utterance from a debug input field; used in unit tests/CI and as demo fallback |
| `FunAsrConformerAsr` | optional stretch | true streaming CTC on AI Hub ("real-time" tag), lower latency partials |

---

## 7. TTS design

- Sentence-chunked synthesis: split assistant text on `. ? !` and speak chunks as they
  synthesize → low first-audio latency; no need to stream model output token-by-token.
- Assistant utterances are deliberately short (one question/confirmation per turn).
- `TtsBackend`: `speak(text)` suspends until done or stopped; `stop()` flushes instantly.
- Backends:

| Backend | Status | Notes |
|---|---|---|
| `MeloTtsQnn` | primary on device | QIDK ASR-LLM-TTS `TTSManager.java` + `tts-sdk.jar` + `libtts*.so` + `melo_en.64_bit.qnn` |
| `AndroidTts` | fallback / dev | system offline TTS; zero setup; used until MeloTTS is pushed |
| `NoTts` | debug | speak = print (unit tests, CI) |

---

## 8. Output contract (the "plug into any API" JSON)

Built by `ReportBuilder`, emitted by `ReportSink` (`FileSink`, `ShareSink`, `HttpSink`).

```jsonc
{
  "report_id": "rpt_20261010_143210_a1b2c3",
  "schema_version": "1.0",
  "form_id": "incident_report",
  "status": "submitted",                    // draft | ready | submitted
  "created_at": "2026-10-10T14:32:10+05:30",
  "fields": {
    "incident_type":  { "value": "water_leak", "label": "Water leak", "status": "answered",
                        "confidence": 0.95, "evidence": "water leak", "utterance": 1 },
    "severity":       { "value": "medium", "label": "Medium", "status": "answered",
                        "confidence": 0.9, "evidence": "medium", "utterance": 2 },
    "location":       { "value": "Conference Room 2", "status": "answered", "confidence": 0.9,
                        "evidence": "near conference room 2", "utterance": 1 },
    "occurred_at":    { "value": "2026-10-10T10:00:00", "status": "inferred", "confidence": 0.7,
                        "evidence": "around 10am", "utterance": 1 },
    "description":    { "value": "Water leak near Conference Room 2.", "status": "answered",
                        "confidence": 0.8, "evidence": "…", "utterance": 1 },
    "immediate_actions": { "value": "Area isolated by maintenance", "status": "answered",
                        "confidence": 0.9, "evidence": "maintenance has already isolated the area", "utterance": 1 },
    "persons_involved":  { "value": null, "status": "unknown", "confidence": 0.0, "evidence": "not stated", "utterance": 3 },
    "equipment_involved":{ "value": null, "status": "unknown", "confidence": 0.0, "evidence": "skipped", "utterance": 4 },
    "witnesses":      { "value": null, "status": "unknown", "confidence": 0.0, "evidence": "not stated", "utterance": 5 },
    "recommended_actions": { "value": "Schedule plumbing inspection", "status": "answered",
                        "confidence": 0.8, "evidence": "…", "utterance": 6 },
    "action_status":  { "value": "in_progress", "label": "In progress", "status": "answered",
                        "confidence": 0.8, "evidence": "isolated", "utterance": 1 }
  },
  "missing_required": [],
  "transcript": [ { "index": 1, "text": "There was a water leak near conference room 2 around 10am. Maintenance has already isolated the area.", "ts_ms": 173321 } ],
  "conversation": [ { "role": "assistant", "text": "…" }, { "role": "user", "text": "…" } ],
  "metrics": { "asr_ms": 310, "llm_ms": 980, "tts_first_audio_ms": 210, "turn_total_ms": 2410 },
  "device": { "model": "QIDK", "soc": "SM8650", "runtime": "QAIRT" },
  "extras": {}
}
```

### 8.1 Proxy API contract (forms in, reports out)

The proxy is any HTTP service implementing the two calls below; the base URL is
configurable in `Settings → Proxy URL`. Everything else stays on-device. A reference
implementation ships in `tools/mock_proxy.py` (Python, stdlib) for development and demo.

**Fetch form**

```
GET  {base}/api/v1/forms/{form_id}
200  → the §3.1 schema JSON (Content-Type: application/json)
404  → unknown form id
```

Optional form list: `GET {base}/api/v1/forms` → `[{"id": "...", "title": "..."}]`
(enables a form picker later).

**Submit report**

```
POST {base}/api/v1/reports
Header: Idempotency-Key: {report_id}
Body:   the §8 report JSON (Content-Type: application/json)
200  → {"ok": true, "report_id": "…", "received_at": "…"}
4xx  → ingestion rejected (report kept locally, surfaced to the user)
timeout / 5xx → outbox retry with backoff
```

**App-side rules**

- Fetch at start; on failure use the cached schema; on first run without network use the
  bundled default — the demo never depends on the network.
- Save locally **before** POSTing; the local copy is the source of truth, the POST is
  delivery. Failed deliveries stay in the outbox and are retried.
- No auth in v1; a bearer-token header is a one-line extension if the real proxy needs it.

---

## 9. Code design

### 9.1 Repository layout

```
KevinDidThis/
├── docs/
│   ├── DESIGN.md              ← this document
│   ├── TOOL_PROTOCOL.md       (extracted from §4 for quick reference)
│   └── QIDK_SETUP.md          (extracted from §10, step-by-step)
├── core/                      pure Kotlin/JVM library — no Android deps, unit-tested on host
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/qidk/incident/core/
│       │   ├── form/          FormSchema, FieldDef, FormState, FieldNormalizer, FieldStatus
│       │   ├── tools/         ToolDefs, ToolCall, ToolCallParser, ToolExecutor
│       │   ├── prompt/        ExtractionPrompt (build), SchemaDigest, Grammar (optional GBNF)
│       │   ├── engine/        ExtractionEngine, LlmBackend, LlmOutput
│       │   ├── dialog/        DialogPolicy, FastIntents, SessionState, SessionEvent
│       │   ├── session/       IncidentSession
│       │   └── report/        ReportBuilder, ReportSink
│       ├── main/resources/forms/incident_report.json
│       └── test/kotlin/…      JUnit tests incl. the golden water-leak scenario
├── android/                   Android Studio project (QIDK app)
│   ├── settings.gradle.kts    includes :app and :core (../core)
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── assets/forms/incident_report.json      (same schema)
│           ├── assets/prompts/…                       (few-shots, chat templates)
│           ├── java/com/qidk/incident/
│           │   ├── MainActivity.kt / ui/…             Compose UI
│           │   ├── ui/IncidentWidget.kt               home-screen widget (1-tap record)
│           │   ├── audio/AudioCapture.kt, EnergyVad.kt
│           │   ├── net/ProxyApi.kt                    form fetch + report submit (§8.1)
│           │   ├── backend/GenieLlm.kt   (JNI, port of AI-Assistant pattern)
│           │   ├── backend/WhisperAsr.kt (JNI, port of ASR-LLM-TTS pattern)
│           │   ├── backend/MeloTts.kt    (JNI/AAR, ASR-LLM-TTS pattern)
│           │   ├── backend/AndroidTts.kt, TextAsr.kt, EchoLlm.kt  (fallbacks)
│           │   └── sink/HttpSink.kt, FileSink.kt, ShareSink.kt
│           └── cpp/ native glue for Genie/Whisper (from QIDK samples, BSD-3-Clause-Clear)
├── tools/
│   ├── export_llm.sh          AI Hub export recipes (Qwen3-4B / Llama-3.2-3B, snapdragon-8-gen-3)
│   ├── export_asr_tts.md      QPM Voice AI ASR/TTS package + notebook steps
│   ├── push_models.sh         adb push bundle → /data/local/tmp + env setup
│   ├── mock_proxy.py          local proxy: serves forms (§3.1) + accepts reports (§8.1)
│   └── prompt_lab.py          host-side prompt tuning vs any OpenAI-compatible server
└── README.md                  run/demo instructions once implemented
```

### 9.2 Core interfaces (design code)

```kotlin
// form/FormSchema.kt
enum class FieldType { STRING, TEXT, ENUM, DATETIME, STRING_LIST, NUMBER, BOOLEAN }
data class EnumValue(val value: String, val label: String, val aliases: List<String> = emptyList())
data class FieldDef(val id: String, val label: String, val type: FieldType,
                    val required: Boolean = false, val askOnce: Boolean = false,
                    val prompt: String? = null, val enumValues: List<EnumValue> = emptyList(),
                    val default: String? = null)
data class FormSchema(val id: String, val title: String, val version: String,
                      val fields: List<FieldDef>, val behavior: Behavior = Behavior())
class FormSchemaLoader { fun load(json: String): FormSchema ; fun loadFromAssets(...) }

// form/FormState.kt
enum class FieldStatus { EMPTY, FILLED, AMBIGUOUS, UNKNOWN, CONFIRMED }
data class FieldValue(val value: String?, val confidence: Double, val evidence: String?,
                      val utterance: Int, val status: FieldStatus)
class FormState(schema: FormSchema) {
    fun apply(u: FieldUpdate): ApplyResult          // normalize + merge (§3.3)
    fun missingRequired(): List<FieldDef>
    fun gatePasses(): Boolean
    fun snapshotForPrompt(): String
    fun toJson(): JsonObject
}

// tools/ToolProtocol.kt
enum class UpdateStatus { ANSWERED, INFERRED, UNKNOWN }
data class FieldUpdate(val field: String, val value: String, val confidence: Double,
                       val evidence: String? = null, val status: UpdateStatus = UpdateStatus.INFERRED)
sealed interface ToolCall {
    data class RecordFields(val updates: List<FieldUpdate>) : ToolCall
    data class AskUser(val field: String?, val question: String) : ToolCall
    data class SubmitReport(val summary: String?) : ToolCall
}
object ToolCallParser {
    fun parse(rawModelText: String): ParseResult        // §4.2, 4 strategies + repair
    fun parseOpenAiToolCalls(...): ParseResult
}
object ToolDefs { fun jsonSchemas(schema: FormSchema): List<JsonObject> }

// engine/ExtractionEngine.kt  — pure, backend-agnostic
interface LlmBackend { val id: String; suspend fun generate(prompt: String, maxTokens: Int): LlmOutput }
class ExtractionEngine(schema: FormSchema, llm: LlmBackend, clock: Clock) {
    suspend fun process(utterance: String, state: FormState,
                        pending: FieldDef?, recent: List<Turn>): ExtractionResult
}
data class ExtractionResult(val prompt: String, val rawOutput: String,
                            val calls: List<ToolCall>, val applied: List<ApplyResult>,
                            val llmMs: Long)

// dialog/DialogPolicy.kt
sealed interface AssistantAction {
    data class AskField(val field: FieldDef, val text: String) : AssistantAction
    data class Clarify(val field: FieldDef, val text: String) : AssistantAction
    data class Confirm(val text: String) : AssistantAction
    data class Submit(val text: String) : AssistantAction
    data class Info(val text: String) : AssistantAction
}
class DialogPolicy(schema: FormSchema) {
    fun next(state: FormState, lastExtraction: ExtractionResult?): AssistantAction
    fun questionFor(field: FieldDef): String
}

// session/IncidentSession.kt
class IncidentSession(schema: FormSchema, llm: LlmBackend, policy: DialogPolicy) {
    val events: Flow<SessionEvent>
    suspend fun start(): AssistantAction
    suspend fun onUtterance(text: String): List<SessionEvent>   // one full turn
    suspend fun confirmSubmit(): ReportReadyEvent                // gate-checked
}

// report/ReportBuilder.kt + ReportSink.kt
class ReportBuilder(schema: FormSchema) { fun build(state: FormState, transcript: List<Turn>, metrics: Metrics): JsonObject }
interface ReportSink { suspend fun emit(reportJson: String): SinkResult }
```

### 9.3 Android app design

- **UI (Compose, single activity)**:
  - Header: assistant state pill (`Listening / Thinking / Speaking`), backend chips
    (`NPU: Qwen3-4B • Whisper-Base • MeloTTS`).
  - Main: `FormCard` list — each field shows label, normalized value, confidence dot
    (green ≥0.8, amber 0.5–0.8, red <0.5 / ambiguous), status chip (`answered`,
    `inferred`, `unknown`), tap → evidence/utterance.
  - Transcript strip: partial in grey, final in black; conversation bubbles below.
  - Controls: big PTT mic button (hold to talk) + open-mic toggle; text input (debug);
    `Submit` button enabled iff `gatePasses()`; overflow → `View JSON`, `New report`,
    `Settings` (backend modes, HTTP sink URL).
- **Widget (home screen)**: `AppWidgetProvider` + `RemoteViews` — one big "Report"
  button and a status line. Tap → `PendingIntent` → `MainActivity` with
  `EXTRA_AUTO_LISTEN=true`; recording starts immediately (no extra tap). After
  submission the status line shows "Filed 14:32" via `AppWidgetManager`.
- **Interaction modes**: `SINGLE_SHOT` (default; records until silence, then runs the
  whole pipeline and says "done") · `PUSH_TO_TALK` · `OPEN_MIC`. The widget always uses
  `SINGLE_SHOT`.
- **DI**: manual factory (`AppGraph`) chosen by `Settings` — modes:
  `LLM = GENIE | ECHO`, `ASR = WHISPER_QNN | TEXT`, `TTS = MELO_QNN | ANDROID | NONE`.
  Default on device: GENIE + WHISPER_QNN + MELO_QNN; defaults degrade gracefully if a
  native model is missing, with a visible banner rather than a crash.
- **Settings persisted** in `SharedPreferences`; sink URL default empty.

### 9.4 Host-side development & CI

- `core/` builds and tests on any JVM (`./gradlew test`, JDK 17/21) — no Android SDK.
- `EchoLlm` (deterministic, rule/regex based) + `TextAsr` + `NoTts` make the entire
  pipeline demoable and testable on the Linux host before device bring-up.
- `tools/prompt_lab.py` runs the same system prompt against a local OpenAI-compatible
  server (Ollama / llama.cpp `--port`) on the host to tune few-shots and measure
  extraction quality against a real small model before deploying to QIDK.

---

## 10. On-device model setup (QIDK, SM8650)

> Full step-by-step commands go to `docs/QIDK_SETUP.md` (derived from this section).

### 10.1 Version matrix (must match)

| Component | Version rule | 8 Gen 3 value |
|---|---|---|
| Hexagon arch | SM8650 → v75 | `hexagon-v75/unsigned`, `libQnnHtpV75Stub.so` |
| QAIRT | match `qairt_version.txt` from the AI Hub export / QPM package | e.g. 2.45.x / 2.47.x |
| Genie libs | `libGenie.so`, `libQnnHtp.so`, `libQnnHtpV75Stub.so`, `libQnnSystem.so` → `jniLibs/arm64-v8a` | from matching QAIRT |
| LLM bundle | AI Hub `qai_hub_models` export for `qualcomm-snapdragon-8-gen-3` | w4a16 ctx-bins + tokenizer + `genie_config.json` |
| Whisper | Voice AI ASR (QPM) or AI Hub precompiled | Whisper-Base/Small, 16 kHz |
| MeloTTS | Voice AI TTS (QPM) MeloTTS notebook output | `melo_en.64_bit.qnn_v2.33.0.qnn` |

### 10.2 LLM bundle (host)

```bash
pip install "qai-hub-models[qwen3-4b-instruct-2507]"      # or llama-v3-2-3b-instruct
python -m qai_hub_models.models.qwen3_4b_instruct_2507.export \
    --chipset qualcomm-snapdragon-8-gen-3 \
    --skip-profiling --context-length 2048 \
    --output-dir genie_bundle
# verify: ls genie_bundle  (ctx-bins, tokenizer.json, genie_config.json, qairt_version.txt)
```

(Exact model module id / chipset string to be confirmed against the installed
`qai-hub-models` version — `--help` lists chipset names.)

### 10.3 Push to device

```bash
adb shell mkdir -p /data/local/tmp/genie_bundle
adb push genie_bundle/*            /data/local/tmp/genie_bundle/
adb push $QAIRT/lib/hexagon-v75/unsigned/* /data/local/tmp/genie_bundle/
adb push $QAIRT/lib/aarch64-android/*      /data/local/tmp/genie_bundle/
# app sets ADSP_LIBRARY_PATH=/data/local/tmp/genie_bundle (see GenieLlm.kt)
```

### 10.4 App-side env/lifecycle

- `GenieLlm` loads config JSON from `/data/local/tmp/genie_bundle/genie_config.json`,
  sets `ADSP_LIBRARY_PATH`/`LD_LIBRARY_PATH`, `GenieDialog_create`, then per-turn
  `GenieDialog_query` with streaming callback (port of QIDK `native-lib.cpp`).
- Whisper/MeloTTS models under the app's external files dir (as in the QIDK samples),
  e.g. `/storage/emulated/0/Android/data/com.qidk.incident/files/{asr,tts}/`.
- First-run self-check screen shows which backends loaded; missing model → fallback +
  banner.

---

## 11. Testing & evaluation

### 11.1 Host (CI-able, no device)

- Unit: schema load, normalizer (`"around 10am"` → ISO; severity/type aliases; lists),
  parser (fenced/tagged/prose/multi/malformed → repair), merge policy, gate,
  fast intents, policy question order.
- Golden end-to-end with `EchoLlm` + scripted outputs: water-leak scenario → asserts
  full form + question text + report JSON.
- Property tests: random field updates never violate gate/merge invariants.

### 11.2 On-device acceptance

| # | Test | Pass criteria |
|---|---|---|
| A1 | LLM loads on NPU | Genie dialog created; tokens/s ≥ 8 |
| A2 | Golden utterance (spoken) | 5 fields filled, severity question asked |
| A3 | Follow-up "medium" | gate passes; confirmation spoken |
| A4 | "yes" | report JSON emitted; spoken report id |
| A5 | Correction "actually 11am" | `occurred_at` updated, provenance kept |
| A6 | Barge-in | TTS stops < 300 ms after speech onset |
| A7 | Airplane mode | full flow works, no network |
| A8 | Push-to-talk in noise | transcript matches utterance ≥ 80% (manual) |

### 11.3 Metrics to report

- Per-turn latency P50/P95 per stage (logs + report `metrics`).
- Extraction accuracy on a 20-utterance test set (field-level precision/recall).
- ASR WER on the demo utterances (Whisper-Base vs Small).
- TTS first-audio latency.

---

## 12. Risks & fallbacks

| Risk | Likelihood | Mitigation / fallback |
|---|---|---|
| Genie/QAIRT version mismatch on device | Medium | pin QAIRT per `qairt_version.txt`; document exact versions; test `genie-t2t-run` CLI before app |
| Small LLM emits malformed JSON | Medium | strict few-shots + parser repair + one retry + `description` fallback; optional GBNF on llama.cpp runtimes |
| Whisper model export friction (Voice AI SDK) | Medium | AI Hub precompiled Whisper; worst case `TextAsr` keeps pipeline demoable |
| MeloTTS setup friction (QPM license/model push) | Medium | `AndroidTts` fallback; upgrade path documented |
| VAD endpointing unreliable in noisy demo room | Medium | Push-to-talk default at demo; energy VAD tuned, thresholds configurable |
| Latency worse than budget | Low-Med | cap `max_new_tokens`; smaller Whisper; disable partials; shorter prompts |
| Enum normalization misses | Medium | alias tables in schema; policy re-ask with explicit options |
| Model quality on field extraction | Medium | prompt_lab tuning before device; Qwen3-4B primary, Llama-3.2-3B fallback; per-field confidence surfaced to user |

---

## 13. Milestones (implementation plan)

| M | Deliverable | Acceptance |
|---|---|---|
| M0 | Repo scaffold: `core/` Gradle JVM lib + tests + schema asset | `./gradlew test` green on host |
| M1 | Full pipeline with `EchoLlm`/`TextAsr`/`NoTts`, CLI harness + golden test | water-leak scenario end-to-end in tests |
| M2 | Android app shell (Compose UI, home-screen widget, ProxyApi + mock proxy, session wiring, TextAsr, AndroidTts, EchoLlm) | app runs on QIDK; widget tap starts recording; form fetched from proxy; typed/scripted input fills it; report POSTs back; TTS says "done" |
| M3 | Genie LLM on NPU (JNI) + real prompt/tool calling | spoken/typed utterance → extracted fields on device |
| M4 | Whisper ASR on device (VAD + PTT + partials) | spoken golden utterance transcribed & extracted |
| M5 | MeloTTS + barge-in + report sinks + polish | demo script runs start-to-finish offline |
| M6 | Stretch: partials on/off tuning, FunASR streaming, extra form schema | second schema works with zero code changes |

---

## 14. Open decisions (need sign-off)

1. **App UI toolkit**: Jetpack Compose (proposed) vs XML Views (matches QIDK samples).
2. **Demo interaction**: push-to-talk default at demo, or open-mic with VAD? (proposed:
   both, PTT default).
3. **LLM choice**: Qwen3-4B-Instruct-2507 primary + Llama-3.2-3B fallback (proposed),
   or start with Llama-3.2-3B only (single model, matches QIDK sample exactly)?
4. **Proxy**: contract is defined in §8.1 and a mock ships in `tools/mock_proxy.py`.
   Do you have a real proxy URL/contract yet, or should the demo run against the mock
   (host machine or a tiny service on the QIDK)?
5. **App name/package**: `com.qidk.incident` / "Incident Assistant" — confirm or rename.

### Decided

- **Required fields + "I don't know"** (§5.2): **approved** — one rephrase retry, then an
  audible confirmation records `UNKNOWN` (flagged under `missing_information`), so a
  report is always submittable. This is now a contract, not an open question.
- **Optional fields**: **approved** — asked as combined, natural, schema-defined
  questions per `question_groups` (§3.1, §5.2 item 3), not one field per turn; whole
  groups are skippable in one phrase and unanswered optionals are marked `skipped`.

---

## Appendix A — Why this pipeline shape

- **Deterministic gates around a probabilistic core** is the only reliable way to ship
  a 3–4B on-device model as a forms assistant: the LLM does extraction (fuzzy, natural),
  the app does validation/merge/gating/order (exact, auditable).
- **One tool-call per turn** keeps latency and failure modes bounded; multi-step agent
  loops with small models fail unpredictably and are hard to debug on device.
- **Schema-driven everything** means new forms (IT ticket, safety audit, QC report) are
  a JSON drop-in, which also satisfies "fields defined by user".
- **Ports from QIDK samples** for Genie/Whisper/MeloTTS minimize device-bring-up risk;
  our engineering effort goes where the PS asks: the voice pipeline and tool calling.

## Appendix B — Reference commands / sources

- GenieX docs: https://geniex.aihub.qualcomm.com (runtimes, Python/Android SDK, server)
- AI Hub LLM on Genie export: https://github.com/quic/ai-hub-apps/tree/main/tutorials/llm_on_genie
- QIDK AI-Assistant (Genie JNI sample): https://github.com/qualcomm/qidk/tree/master/GenAI-Solutions/AI-Assistant
- QIDK ASR-LLM-TTS (Whisper + MeloTTS sample): https://github.com/qualcomm/qidk/tree/master/GenAI-Solutions/ASR-LLM-TTS
- QAIRT download: https://qpm.qualcomm.com → Qualcomm AI Runtime SDK
