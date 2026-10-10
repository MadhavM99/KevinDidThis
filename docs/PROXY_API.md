# Server API contract v1

The app is a **pure client**. Against the server you point it at, it makes exactly two
kinds of request:

1. **Fetch the form definition** — optional. If `AppConfig.SERVER_BASE_URL` is set, the
   app calls `GET /api/v1/forms/{id}` on every app start and on **Refresh form**; if the
   server is unreachable (or no URL is set) the **bundled sample form** loads instead —
   the demo works with zero configuration, fully offline.
2. **Submit the finished report** — `POST /api/v1/reports` when the user confirms.

Form **management** — creating, editing, versioning definitions — happens on your side
(admin tooling, CMS, database, whatever). The app never writes forms. To change what the
app asks users, update the definition on the server; the next start/refresh picks it up.
No app release required.

Everything else (ASR, LLM, TTS) stays on-device.

| | |
|---|---|
| Base URL | optional: `AppConfig.SERVER_BASE_URL`. Blank = the bundled sample form auto-loads (fully offline) |
| Content type | `application/json` |
| Auth | none in v1 — add `Authorization: Bearer <token>` if needed (one header in `net/ProxyApi.kt`) |
| Local server | `python3 tools/mock_proxy.py` plays this role until the real one exists |

---

## 1. Fetch the form

### `GET /api/v1/forms/{id}`

The app calls this at startup and when the user taps **Refresh form**. On any failure
(timeout, `404`, invalid JSON) it shows an error and waits for a retry — by design there
is no local fallback (DESIGN.md §3.5), so the form on screen is always the one the server
sent.

```bash
curl http://127.0.0.1:8787/api/v1/forms/workplace_incident
```

Response: the form definition (DESIGN.md §3.1) — fields with `id`, `label`, `type`,
`required`, `question`, `options` + aliases, `keywords`, `group`, plus `question_groups`
and `behavior`. Optional per-field conversation hints (all backwards-compatible):

| Key | Effect |
|---|---|
| `prefill: "reporter"` | The app fills this field with the signed-in user (managed configuration `reporter_name`) and never asks it. If no user is provisioned, it's asked like any other field. |
| `ask_phrase` | Clause used when several missing open-answer fields are asked in one question, e.g. `"where it happened"`. Without it, `"the <label>"` is used. |
| `ask_alone: true` | Never combine this field with others (safety-critical, or needs a focused answer). Enum, boolean and number fields are always asked on their own. |

Example (truncated):

```json
{
  "id": "workplace_incident",
  "title": "Workplace Incident Report",
  "version": "1.0",
  "fields": [
    {
      "id": "severity_classification",
      "label": "Severity Classification",
      "type": "enum",
      "required": true,
      "question": "How serious was it — near miss, first aid only, medical treatment, restricted duty, lost time, or fatality?",
      "options": [
        { "value": "near_miss", "label": "Near miss (no injury or damage)", "aliases": ["no injury", "close call"] },
        { "value": "first_aid", "label": "First aid only", "aliases": ["minor", "first aid"] }
      ]
    }
  ],
  "question_groups": [
    { "id": "followup", "prompt": "Was any equipment or asset involved, ...?" }
  ]
}
```

`404` → `{"error": "unknown form '<id>'"}`

### `GET /api/v1/forms` *(optional)*

Lists definitions; useful later for a form picker in the app.

```json
[ { "id": "incident_report", "title": "Incident Report", "version": "1.0" } ]
```

### Recommended validation when you publish a form

The app parses definitions leniently, but a malformed definition degrades the experience.
Suggested checks on your side before publishing (mirrors the app's parser):

| Rule | Why |
|---|---|
| `fields` non-empty; field `id`s unique and `^[a-z0-9_]+$` | ids are used as JSON keys and in prompts |
| `type` ∈ `text, textarea, enum, datetime, string_list, number, boolean` | the app renders/normalizes by type |
| `enum` fields have `options`, each with a non-empty `value` | unmatched answers get re-asked |
| `question_groups[]` entries have `id` + `prompt` | combined optional questions are spoken from these |
| Bump `version` when changing a form | the app refetches per start/refresh; version helps trace reports back to a definition |

## 2. Submit the report

### `POST /api/v1/reports`

Sent when the user confirms submission, with the full report JSON (DESIGN.md §8):

- header `Idempotency-Key: {report_id}` — safe to retry,
- body `application/json`.

```bash
curl -X POST http://127.0.0.1:8787/api/v1/reports \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: RPT-ABC12345' \
  --data-binary '{
    "report_id": "RPT-ABC12345",
    "form_id": "workplace_incident",
    "status": "submitted",
    "fields": { "location": { "value": "Line 3, Bay 7" } }
  }'
```

Response:

```json
{ "ok": true, "report_id": "RPT-ABC12345", "received_at": "2026-10-10T14:32:10.123456+00:00" }
```

Behaviour:

| Server response | App behaviour |
|---|---|
| `200` | success; the report is confirmed on the Done screen |
| `400` | treated as "not accepted"; the local copy is kept and surfaced to the user |
| timeout / `5xx` | local copy kept; delivery retried (outbox, DESIGN.md §8.1) |

The app **always saves the report locally first** — delivery is best-effort, data is never
lost.

---

## 3. Mock server (until the real one exists)

```bash
python3 tools/mock_proxy.py --port 8787
# optional: --forms tools/forms --reports /tmp/incident_reports
```

- Serves the fixture definitions from `tools/forms/` (the `--forms` directory; the app
  bundles no definitions) and stores submissions as JSON files (`POST /api/v1/reports`).
- **Simulating an admin update:** edit the JSON file in the `--forms` directory, then tap
  *Refresh form* in the app (or restart it).
- Mock-only conveniences, not part of the client contract: `GET /api/v1/reports` and
  `GET /api/v1/reports/{id}` to inspect submissions without opening the files.

Connect the app — optional: without it the app runs on the bundled sample form. Set
`AppConfig.SERVER_BASE_URL` to use the server:

| Target | `AppConfig.SERVER_BASE_URL` |
|---|---|
| Android emulator | `http://10.0.2.2:8787` |
| Device on the LAN | `http://<your-ip>:8787` |
| USB-connected QIDK | `adb reverse tcp:8787 tcp:8787`, then `http://127.0.0.1:8787` |

## 4. App-side mapping

| App code | Request |
|---|---|
| `net/HttpFormSource.fetchForm(id)` | `GET /api/v1/forms/{id}` (no fallback; errors surface) |
| `net/HttpReportSubmitter.submit(json, id)` | `POST /api/v1/reports` |
