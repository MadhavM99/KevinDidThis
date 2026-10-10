#!/usr/bin/env python3
"""Stand-in form/report server for the Incident Assistant (contract: docs/PROXY_API.md).

The Android app is a pure client:

    1. GET  the form definition on every app start / refresh
    2. POST the finished report when the user confirms

In production that server is owned by whoever runs the workflow — the app never writes
form definitions. Until that server exists, this script plays its role using the fixture
definitions in `tools/forms/` (the app bundles no forms).

Usage:
    python3 tools/mock_proxy.py --port 8787
    python3 tools/mock_proxy.py --forms /tmp/forms --reports /tmp/incident_reports

Served (client contract):
    GET  /health                liveness probe
    GET  /api/v1/forms          list available form definitions
    GET  /api/v1/forms/<id>     the form definition the app loads
    POST /api/v1/reports        receive finished reports

Mock-only conveniences (the app does not call these):
    GET  /api/v1/reports        list stored reports
    GET  /api/v1/reports/<id>   fetch a stored report

Simulating an admin updating a form: edit the JSON in the --forms directory (or point
--forms at another folder). The app picks the change up on its next start/refresh.

Point the app at it (AppConfig.SERVER_BASE_URL):
    Emulator:            http://10.0.2.2:8787
    Device on the LAN:   http://<your-ip>:8787
    USB-connected QIDK:  adb reverse tcp:8787 tcp:8787  ->  http://127.0.0.1:8787
"""
import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HEALTH = re.compile(r"^/(health|api/v1/health)/?$")
FORMS_LIST = re.compile(r"^/api/v1/forms/?$")
FORM_ITEM = re.compile(r"^/api/v1/forms/([A-Za-z0-9_\-]+)$")
REPORTS_LIST = re.compile(r"^/api/v1/reports/?$")
REPORT_ITEM = re.compile(r"^/api/v1/reports/([A-Za-z0-9_\-]+)$")


class Handler(BaseHTTPRequestHandler):
    forms_dir = "tools/forms"
    reports_dir = "/tmp/incident_reports"

    # ---------- helpers ----------

    def _send(self, status, payload):
        body = json.dumps(payload, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json_body(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        return json.loads(raw) if raw else None

    # ---------- GET ----------

    def do_GET(self):
        if HEALTH.match(self.path):
            self._send(200, {"status": "ok", "time": datetime.now(timezone.utc).isoformat()})
            return

        if FORMS_LIST.match(self.path):
            self._send(200, self._list_forms())
            return

        match = FORM_ITEM.match(self.path)
        if match:
            path = os.path.join(self.forms_dir, f"{match.group(1)}.json")
            if os.path.isfile(path):
                with open(path) as fh:
                    self._send(200, json.load(fh))
            else:
                self._send(404, {"error": f"unknown form '{match.group(1)}'"})
            return

        if REPORTS_LIST.match(self.path):
            self._send(200, self._list_reports())
            return

        match = REPORT_ITEM.match(self.path)
        if match:
            path = os.path.join(self.reports_dir, f"{match.group(1)}.json")
            if os.path.isfile(path):
                with open(path) as fh:
                    self._send(200, json.load(fh))
            else:
                self._send(404, {"error": f"unknown report '{match.group(1)}'"})
            return

        self._send(404, {"error": "not found"})

    # ---------- POST: receive a finished report ----------

    def do_POST(self):
        if not REPORTS_LIST.match(self.path):
            self._send(404, {"error": "not found"})
            return

        try:
            report = self._read_json_body()
        except json.JSONDecodeError as exc:
            self._send(400, {"error": f"invalid JSON: {exc}"})
            return
        if not isinstance(report, dict):
            self._send(400, {"error": "body must be a JSON object"})
            return
        report_id = report.get("report_id") or self.headers.get("Idempotency-Key")
        if not report_id:
            self._send(400, {"error": "report_id (or Idempotency-Key header) is required"})
            return

        os.makedirs(self.reports_dir, exist_ok=True)
        stored = os.path.join(self.reports_dir, f"{report_id}.json")
        with open(stored, "w") as fh:
            json.dump(report, fh, indent=2)
        print(f"[mock-server] report received: {report_id} -> {stored}")
        self._send(
            200,
            {"ok": True, "report_id": report_id, "received_at": datetime.now(timezone.utc).isoformat()},
        )

    # ---------- listing helpers ----------

    def _list_forms(self):
        forms = []
        if os.path.isdir(self.forms_dir):
            for name in sorted(os.listdir(self.forms_dir)):
                if not name.endswith(".json"):
                    continue
                try:
                    with open(os.path.join(self.forms_dir, name)) as fh:
                        definition = json.load(fh)
                    forms.append(
                        {
                            "id": definition.get("id", name[:-5]),
                            "title": definition.get("title", ""),
                            "version": definition.get("version", "1.0"),
                        }
                    )
                except (OSError, json.JSONDecodeError):
                    continue
        return forms

    def _list_reports(self):
        reports = []
        if os.path.isdir(self.reports_dir):
            for name in sorted(os.listdir(self.reports_dir)):
                if not name.endswith(".json"):
                    continue
                path = os.path.join(self.reports_dir, name)
                try:
                    with open(path) as fh:
                        report = json.load(fh)
                    reports.append(
                        {
                            "report_id": report.get("report_id", name[:-5]),
                            "form_id": report.get("form_id", ""),
                            "status": report.get("status", ""),
                            "received_at": datetime.fromtimestamp(
                                os.path.getmtime(path), timezone.utc
                            ).isoformat(),
                        }
                    )
                except (OSError, json.JSONDecodeError):
                    continue
        return reports

    def log_message(self, fmt, *args):
        print(f"[mock-server] {self.address_string()} {fmt % args}")


def main():
    parser = argparse.ArgumentParser(description="Stand-in form/report server for the Incident Assistant")
    parser.add_argument("--port", type=int, default=8787, help="port to listen on (default 8787)")
    parser.add_argument("--host", default="0.0.0.0", help="bind address (default 0.0.0.0)")
    parser.add_argument(
        "--forms", default="tools/forms", help="directory with form definition JSON files"
    )
    parser.add_argument(
        "--reports", default="/tmp/incident_reports", help="directory to store submitted reports"
    )
    args = parser.parse_args()

    Handler.forms_dir = args.forms
    Handler.reports_dir = args.reports
    if not os.path.isdir(args.forms):
        print(f"warning: forms dir '{args.forms}' not found (run from the repo root?)", file=sys.stderr)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[mock-server] serving forms from {args.forms}")
    print(f"[mock-server] storing reports in {args.reports}")
    print(f"[mock-server] listening on http://{args.host}:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock-server] bye")
        server.server_close()


if __name__ == "__main__":
    main()
