#!/usr/bin/env bash
# Puts an HTML form page on the device; the app uses it instead of the bundled one.
# Then tap "Refresh form" in the app (or restart it) — no rebuild needed.
#
#   ./scripts/push-form.sh path/to/form.html     # use this form
#   ./scripts/push-form.sh --clear               # back to the bundled tools/forms/ page
set -euo pipefail
PKG=com.megathon.incidentassistant
DEST="/sdcard/Android/data/$PKG/files/forms"
adb get-state >/dev/null || { echo "no device" >&2; exit 1; }

if [[ "${1:-}" == "--clear" ]]; then
  adb shell "rm -rf '$DEST'"
  echo "removed $DEST: the bundled form is used again"
  exit 0
fi
SRC="${1:?usage: push-form.sh <form.html> | --clear}"
[[ -f "$SRC" ]] || { echo "no such file: $SRC" >&2; exit 1; }

# One page at a time: the app takes incident_report.html, else the only .html present.
adb shell "rm -rf '$DEST' && mkdir -p '$DEST'"
adb push "$SRC" /data/local/tmp/incident_form.html >/dev/null
adb shell "cp /data/local/tmp/incident_form.html '$DEST/incident_report.html' && rm /data/local/tmp/incident_form.html"
echo "pushed $SRC -> $DEST/incident_report.html  (tap 'Refresh form' in the app)"
