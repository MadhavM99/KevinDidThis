#!/usr/bin/env bash
# Puts a form (an HTML page, or JSON) on the device; the app uses it instead of the bundled
# forms/form1.html.
# Then tap "Reload the form" in the app's user menu (or restart it) — no rebuild needed.
# One form at a time: pushing a new one replaces the previous one.
#
#   ./scripts/push-form.sh forms/form3.html   # use this form
#   ./scripts/push-form.sh --clear            # back to the bundled form1
set -euo pipefail
PKG=com.megathon.incidentassistant
DEST="/sdcard/Android/data/$PKG/files/forms"
adb get-state >/dev/null || { echo "no device" >&2; exit 1; }

if [[ "${1:-}" == "--clear" ]]; then
  adb shell "rm -rf '$DEST'"
  echo "removed $DEST: the bundled form is used again"
  exit 0
fi
SRC="${1:?usage: push-form.sh <form.html|form.json> | --clear}"
[[ -f "$SRC" ]] || { echo "no such file: $SRC" >&2; exit 1; }
case "$SRC" in
  *.html|*.json) ;;
  *) echo "not a form file (.html or .json): $SRC" >&2; exit 1 ;;
esac
NAME=$(basename "$SRC")

adb shell "rm -rf '$DEST' && mkdir -p '$DEST'"
# Staged in /data/local/tmp: adb cannot push into nested Android/data dirs directly.
adb push "$SRC" "/data/local/tmp/$NAME" >/dev/null
adb shell "cp '/data/local/tmp/$NAME' '$DEST/$NAME' && rm '/data/local/tmp/$NAME'"
echo "pushed $SRC -> $DEST/$NAME  (in the app: user menu → Reload the form)"
