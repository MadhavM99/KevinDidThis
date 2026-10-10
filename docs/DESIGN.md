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
│  │  UI (Compose)         │    │                  Session          (state machine)           ││
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
│  FormState (schema-driven) ──▶ ReportJson    ──▶ ReportRepo: local save + proxy POST           │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Data flow per turn

1. Mic frames (PCM16, 16 kHz, 20 ms) → VAD.
2. Utterance ends on ~500 ms trailing silence → `AsrBackend.finalize()`.
3. Final transcript → `IncidentViewModel.onUtterance(text)`.
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

- One session actor (`IncidentViewModel`) on a single-threaded dispatcher → turns never interleave.
- ASR, LLM, TTS run on `Dispatchers.Default`; UI observes `StateFlow<UiState>`.
- Audio capture on its own thread (AudioRecord); VAD frames fed via channel.
- Barge-in: VAD speech onset → `TtsBackend.stop()` immediately (flush AudioTrack).

---

## 3. Form model (user-defined)

### 3.1 Form definition — dynamic JSON (server-delivered)

The form is **data, not code**, and the app ships none of it. Every field carries its
full metadata — mandatory flag, answer type, options (with labels + aliases), the
question to ask, grouping, and **keywords** — and the app generates the LLM extraction
prompt from it at runtime: field ids, labels, `keywords` and option labels/aliases become
the model's extraction vocabulary. Nothing is hardcoded in the app: substitute the JSON
and the extraction vocabulary changes with it. Any model (Qwen, Llama, …) and any
use-case works with zero app changes; admins manage definitions on the server, the app
only fetches them (§8.1, `docs/PROXY_API.md`). JSON is the wire format (an XML parser can
be swapped in later if ever needed).

The example below documents the format contract. At build time nothing form-specific is
compiled into the app; during development the mock server (`tools/mock_proxy.py`) serves
the fixtures in `tools/forms/`.

```jsonc
{
  "id": "incident_report",
  "title": "Incident Report",
  "version": "1.0",
  "fields": [
    {
      "id": "incident_type", "label": "Incident Type", "type": "enum", "required": true,
      "question": "What kind of incident is it?",
      "keywords": ["incident type", "kind of incident"],
      "options": [
        { "value": "water_leak",        "label": "Water leak",        "aliases": ["leak", "flood", "water damage", "pipe burst"] },
        { "value": "fire",              "label": "Fire",              "aliases": ["smoke", "burning"] },
        { "value": "electrical",        "label": "Electrical",        "aliases": ["power", "short circuit", "sparking"] },
        { "value": "chemical_spill",    "label": "Chemical spill",    "aliases": ["spill", "hazardous material"] },
        { "value": "equipment_failure", "label": "Equipment failure", "aliases": ["machine down", "broken"] },
        { "value": "slip_trip_fall",    "label": "Slip / trip / fall", "aliases": ["fell", "tripped"] },
        { "value": "security",          "label": "Security",          "aliases": ["theft", "intruder"] },
        { "value": "medical",           "label": "Medical",           "aliases": ["injury", "hurt"] },
        { "value": "other",             "label": "Other",             "aliases": [] }
      ]
    },
    {
      "id": "severity", "label": "Severity", "type": "enum", "required": true,
      "question": "How severe is it — low, medium, high, or critical?",
      "options": [
        { "value": "low",      "label": "Low",      "aliases": ["minor", "slight", "not serious"] },
        { "value": "medium",   "label": "Medium",   "aliases": ["moderate"] },
        { "value": "high",     "label": "High",     "aliases": ["major", "serious", "severe"] },
        { "value": "critical", "label": "Critical", "aliases": ["emergency", "urgent", "life threatening"] }
      ]
    },
    { "id": "location",          "label": "Location",               "type": "text",        "required": true,  "question": "Where did it happen?" },
    { "id": "occurred_at",       "label": "Date & Time",            "type": "datetime",    "required": true,  "question": "When did it happen?" },
    { "id": "description",       "label": "Description",            "type": "textarea",    "required": true },
    { "id": "immediate_actions", "label": "Immediate Actions Taken","type": "textarea",    "required": true,  "question": "What has been done so far to handle it?" },
    { "id": "persons_involved",  "label": "Persons Involved",       "type": "string_list", "required": false, "ask_once": true, "group": "people" },
    { "id": "equipment_involved","label": "Equipment / Asset",      "type": "string_list", "required": false, "ask_once": true, "group": "followup" },
    { "id": "witnesses",         "label": "Witness Information",    "type": "string_list", "required": false, "ask_once": true, "group": "people" },
    { "id": "recommended_actions","label": "Recommended Follow-up", "type": "textarea",    "required": false, "ask_once": true, "group": "followup" },
    {
      "id": "action_status", "label": "Current Action Status", "type": "enum", "required": false,
      "group": "followup", "ask_once": true, "default": "open",
      "options": [
        { "value": "open",        "label": "Open",        "aliases": [] },
        { "value": "in_progress", "label": "In progress", "aliases": ["being handled", "ongoing"] },
        { "value": "monitoring",  "label": "Monitoring",  "aliases": ["watching"] },
        { "value": "resolved",    "label": "Resolved",    "aliases": ["fixed", "closed", "done"] }
      ]
    }
  ],
  "question_groups": [
    { "id": "people",   "prompt": "Were any other people involved, or any witnesses you'd like to note?" },
    { "id": "followup", "prompt": "Was any equipment or asset involved, any follow-up actions you'd recommend, and is the situation still open, in progress, monitoring, or resolved?" }
  ],
  "behavior": {
    "ask_optional_once": true,
    "optional_mode": "combined",
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

### 3.5 Form source (server-only)

- The app ships **no form definitions**. The only source is
  `GET {server}/api/v1/forms/{form_id}` (contract: `docs/PROXY_API.md`), fetched at every
  app start and when the user taps **Refresh form**.
- **No local fallback**: if the fetch fails the app shows an error and waits for a retry —
  the form on screen is always the one the server sent. Nothing hardcoded, ever.
- Admins manage definitions on the server; the app only reads them. A refresh — or the
  next app start — picks up changes and starts a fresh draft.
- Any JSON with the §3.1 shape works — publishing a new form needs **zero app changes**.
- Dev fixtures live in `tools/forms/` and are served by `tools/mock_proxy.py`.

---

## 4. LLM extraction + function calling

### 4.1 Model recommendation

> **Implemented (see `docs/LLM.md`):** Gemma-4-E2B-it Q4_0 GGUF on Qualcomm GenieX
> (llama.cpp runtime, `ai/GemmaGenieXLlm.kt`). Qualcomm ships no Genie/QAIRT bundle for
> Gemma 4 E2B, so the `genie_bundle` route below does not apply to it. The table is the
> original proposal.

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
   (user's example: 10 fields, 6 required, 4 filled by the first utterance → the assistant
   asks the remaining 2 required fields one by one before moving on.)
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

Built by `ReportJson` (§9.2) and delivered by `ReportRepository` (local save, source of
truth) + `ReportSubmitter` (proxy POST, §8.1).

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
The exact requests the app makes are documented in `docs/PROXY_API.md`; form definitions
are managed on the server side, never by the app.

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

- Fetch the form at start and on **Refresh form**; failures show an error the user can
  retry. There are no bundled definitions — the server is the only source (§3.5).
- Save locally **before** POSTing; the local copy is the source of truth, the POST is
  delivery. Failed deliveries stay in the outbox and are retried.
- No auth in v1; a bearer-token header is a one-line extension if the real server needs
  it.

---

## 9. Code design

### 9.1 Repository layout (current, upgrade in place)

```
KevinDidThis/                                  single Android Studio project
├── docs/
│   ├── DESIGN.md                              ← this document
│   └── PROXY_API.md                           server contract: form fetch + report submit
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml            (RECORD_AUDIO + INTERNET for the server)
│       │   └── java/com/megathon/incidentassistant/
│       │       ├── AppContainer.kt            manual DI + AppConfig (proxy URL, placeholder switch)
│       │       ├── MainActivity.kt            Compose host, 3-step navigation
│       │       ├── ai/                        SpeechToText / IncidentExtractor / SpeechSynthesizer
│       │       │                              (placeholders now; Whisper / Genie / MeloTTS later)
│       │       ├── form/                      FormSchema, FormState, FieldNormalizer,
│       │       │                              ToolCallParser, ExtractionPromptBuilder  [dynamic form core]
│       │       ├── net/                       ProxyApi: form fetch + report submit (§8.1)
│       │       ├── data/                      ReportRepository (in-memory → Room + outbox later)
│       │       ├── domain/                    report/tool models (being folded into form/)
│       │       └── ui/                        Capture / Review / Done screens, ViewModel, Theme
│       └── test/java/com/megathon/incidentassistant/   JVM unit tests
├── tools/
│   ├── mock_proxy.py                          stand-in server: serves forms + accepts reports
│   ├── forms/                                 dynamic form fixtures served by the mock (§3.5)
│   ├── export_llm.sh                          AI Hub export recipes (Qwen3-4B / Llama-3.2-3B)
│   ├── export_asr_tts.md                      QPM Voice AI ASR/TTS package + notebook steps
│   └── push_models.sh                         adb push bundle → /data/local/tmp + env setup
└── gradle/ + gradlew                          wrapper (Gradle 8.7)
```

The earlier `core/` + `android/` split is retired — the pipeline lives inside the app as
`form/` (pure Kotlin, JVM-testable) plus `ai/` seams, per the in-place decision (§14).
Naming map from earlier drafts: session = `IncidentViewModel`; report build/emit =
`ReportJson` + `ReportRepository`/`ReportSubmitter`.

### 9.2 Core interfaces (design code)

Target API surface for the in-place upgrade. The placeholder shell already provides the
Compose UI, state machine and seams; the `form/` + `net/` types below are what gets added
next (all pure Kotlin/JVM-testable except `net/`).

```kotlin
// form/FormSchema.kt — the dynamic form definition (§3.1); parsed from what the server
// or the proxy. No field is ever hardcoded in the app.
@Serializable enum class FieldType { TEXT, TEXTAREA, ENUM, DATETIME, STRING_LIST, NUMBER, BOOLEAN }

@Serializable
data class FieldOption(val value: String, val label: String = "", val aliases: List<String> = emptyList())

@Serializable
data class FormField(
    val id: String, val label: String = "", val type: FieldType = FieldType.TEXT,
    val required: Boolean = false, @SerialName("ask_once") val askOnce: Boolean = false,
    val question: String? = null, @SerialName("default") val defaultValue: String? = null,
    val options: List<FieldOption> = emptyList(),
    val keywords: List<String> = emptyList(),          // ← LLM extraction vocabulary
    val group: String? = null,
) {
    /** id + label + keywords + every option label/alias: the words the model watches for. */
    fun vocabulary(): List<String>
}

@Serializable data class QuestionGroup(val id: String, val prompt: String, val fields: List<String> = emptyList())
@Serializable data class FormBehavior(
    @SerialName("ask_optional_once") val askOptionalOnce: Boolean = true,
    @SerialName("optional_mode") val optionalMode: String = "combined",
    @SerialName("confirm_before_submit") val confirmBeforeSubmit: Boolean = true,
    @SerialName("allow_unknown_required") val allowUnknownRequired: Boolean = true,
)
@Serializable data class FormSchema(
    val id: String, val title: String = "", val version: String = "1.0",
    val fields: List<FormField> = emptyList(),
    @SerialName("question_groups") val questionGroups: List<QuestionGroup> = emptyList(),
    val behavior: FormBehavior = FormBehavior(),
) {
    fun field(id: String): FormField?
    val requiredFields: List<FormField>
}
object FormSchemaParser { fun parse(text: String): FormSchema }   // kotlinx.serialization

// form/FormState.kt — the running form + deterministic gate (§3.3/§3.4)
enum class FieldStatus { EMPTY, FILLED, UNKNOWN, AMBIGUOUS }
enum class UpdateStatus { ANSWERED, INFERRED, UNKNOWN }
data class FieldValue(val value: String = "", val confidence: Double = 0.0,
                      val evidence: String = "", val status: FieldStatus = FieldStatus.EMPTY,
                      val utterance: Int = 0)
class FormState(schema: FormSchema) {
    fun beginUtterance()
    fun get(fieldId: String): FieldValue
    fun text(fieldId: String): String
    fun entries(): Map<String, FieldValue>
    fun apply(fieldId: String, rawValue: String, confidence: Double,
              evidence: String?, status: UpdateStatus): ApplyResult   // normalize + merge (§3.3)
    fun markUnknown(fieldId: String, evidence: String? = null): ApplyResult
    fun setDirect(fieldId: String, value: String)                     // Review-screen edit
    fun missingRequired(): List<FormField>
    fun gatePasses(): Boolean
}
object FieldNormalizer {   // enums match value/label/aliases; garbage is rejected → re-asked
    fun normalize(field: FormField, raw: String): Normalization       // Ok | Rejected(allowed)
}

// form/FormToolCall.kt + form/ToolCallParser.kt — the model proposes, the app executes (§4.2)
sealed interface FormToolCall {
    data class RecordFields(val updates: List<FieldUpdate>) : FormToolCall
    data class AskUser(val fieldId: String?, val question: String) : FormToolCall
    data class SubmitReport(val summary: String?) : FormToolCall
}
data class FieldUpdate(val fieldId: String, val value: String, val confidence: Double = 0.5,
                       val evidence: String? = null, val status: UpdateStatus = UpdateStatus.INFERRED)
object ToolCallParser {
    fun parse(rawModelText: String): Result   // OpenAI tool_calls | <tool_call> | fenced | bare JSON + repair
}

// form/ExtractionPromptBuilder.kt — the prompt is generated from the form, so any model works
object ExtractionPromptBuilder {
    fun systemPrompt(schema: FormSchema, state: FormState, nowIso: String): String
    fun userPrompt(pendingField: FormField?, utterance: String): String
}

// form/ReportJson.kt — the §8 output contract (pluggable into any API)
object ReportJson { fun build(schema: FormSchema, state: FormState, reportId: String,
                              nowIso: String, status: String = "submitted"): String }

// ai/ — the three swappable seams (Echo/placeholder today, QIDK backends later)
interface SpeechToText { val partialTranscript: StateFlow<String>; suspend fun prepare();
                         suspend fun startListening(); suspend fun stopListening(): String; fun release() }
interface LlmBackend { val id: String; suspend fun generate(prompt: String): String }  // model-agnostic
interface SpeechSynthesizer { fun speak(text: String); fun stop(); fun shutdown() }

// net/ProxyApi.kt — server-only form source + report submit (§8.1); no bundled fallback
interface FormSource { suspend fun fetchForm(formId: String): FormSchema }
class HttpFormSource(baseUrl: String) : FormSource
interface ReportSubmitter { suspend fun submit(reportJson: String, reportId: String): SubmitResult }
class HttpReportSubmitter(baseUrl: String) : ReportSubmitter

// AppContainer.kt — the single place placeholders are swapped for real models
class AppContainer(context: Context) {
    val speechToText: SpeechToText; val llm: LlmBackend; val synthesizer: SpeechSynthesizer
    val formSource: FormSource; val reportSubmitter: ReportSubmitter; val repository: ReportRepository
}
```

### 9.3 Android app design

- **UI (Compose, single activity)**:
  - Header: assistant state pill (`Listening / Thinking / Speaking`), backend chips
    (`NPU: Qwen3-4B • Whisper-Base • MeloTTS`).
  - Main: `FormCard` list — each field shows label, normalized value, confidence dot
    (green ≥0.8, amber 0.5–0.8, red <0.5 / ambiguous), status chip (`answered`,
    `inferred`, `unknown`), tap → evidence/utterance.
  - Transcript strip: partial in grey, final in black; conversation bubbles below.
  - Controls: big single-shot record button (tap to start, stops on silence) — PTT and
    open-mic stay optional; text input (debug); `Submit` button enabled iff
    `gatePasses()`; overflow → `View JSON`, `New report`, `Settings`.
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
  e.g. `/storage/emulated/0/Android/data/com.megathon.incidentassistant/files/{asr,tts}/`.
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

## 14. Open decisions

1. **LLM choice**: Qwen3-4B-Instruct-2507 primary + Llama-3.2-3B fallback (proposed),
   or start with Llama-3.2-3B only (single model, matches QIDK sample exactly)?
2. **Real proxy**: contract is fixed (§8.1) and a mock ships (`tools/mock_proxy.py`);
   drop in the production URL/contract when your team provides it (an auth header is a
   one-line extension).

### Decided

- **Required fields + "I don't know"** (§5.2): **approved** — one rephrase retry, then an
  audible confirmation records `UNKNOWN` (flagged under `missing_information`), so a
  report is always submittable. This is now a contract, not an open question.
- **Optional fields**: **approved** — asked as combined, natural, schema-defined
  questions per `question_groups` (§3.1, §5.2 item 3), not one field per turn; whole
  groups are skippable in one phrase and unanswered optionals are marked `skipped`.
- **Upgrade strategy**: in-place in the existing `:app` module (no separate `core/`
  module); the placeholder-code commit is the base.
- **Package name**: keep `com.megathon.incidentassistant` (no rename).
- **UI toolkit**: Jetpack Compose (already in the codebase).
- **Interaction**: widget tap → record → single-shot pipeline → TTS "done"
  (PTT / open-mic remain secondary modes).
- **Forms**: fully dynamic JSON metadata (fields, `required`, `type`, `options`,
  `keywords`, questions, groups), fetched/updatable via the proxy API; the LLM prompt
  is generated from the form, so models and use-cases are swappable without app edits.
  XML is not needed now (a parser swap later if it ever is).
- **Form sourcing**: server-only — the app bundles no form definitions and no hardcoded
  keywords; fixtures live in `tools/forms/` and are served by the mock (§3.5).

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
