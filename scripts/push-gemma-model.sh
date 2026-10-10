#!/usr/bin/env bash
# Pushes the on-device LLM (Gemma 4 E2B-it, Q4_0 GGUF) onto a connected device for the
# Incident Assistant app. See docs/LLM.md.
#
# The file is Qualcomm AI Hub's Gemma-4-E2B-it asset (runtime geniex_llamacpp, precision
# q4_0, release-assets.yaml in qualcomm/ai-hub-models), hosted by Google on Hugging Face:
#   https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf  (Apache-2.0, not gated)
# Only the text model is needed; the mmproj (vision/audio projector) is not pushed.
#
# The app imports it with GenieX's local-file import (HubSource.LOCALFS) - no network on
# the device.
#
# Usage:
#   ./scripts/push-gemma-model.sh                 # downloads into models/gemma-4-e2b/ first
#   ./scripts/push-gemma-model.sh /path/to/gemma-4-E2B_q4_0-it.gguf
set -euo pipefail

PKG=com.megathon.incidentassistant
FILE=gemma-4-E2B_q4_0-it.gguf
URL="https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/$FILE"

SRC="${1:-models/gemma-4-e2b/$FILE}"

if [[ ! -f "$SRC" ]]; then
  echo "downloading $FILE (~3 GB) to $SRC"
  mkdir -p "$(dirname "$SRC")"
  curl -fL --retry 3 -C - -o "$SRC" "$URL"
fi
[[ "$(basename "$SRC")" == "$FILE" ]] || { echo "the app looks for exactly $FILE" >&2; exit 1; }

adb get-state >/dev/null || { echo "no device" >&2; exit 1; }

# App-private external dir: no root / remount needed (same convention as whisper/ and tts/).
DEST="/sdcard/Android/data/$PKG/files/llm"
echo "pushing to $DEST"
adb shell "mkdir -p '$DEST'"
adb push "$SRC" "$DEST/$FILE"

echo
echo "Installed:"
adb shell "ls -la '$DEST'"
echo
echo "Restart the app; logcat -s GemmaLlm shows the load time and compute unit."
