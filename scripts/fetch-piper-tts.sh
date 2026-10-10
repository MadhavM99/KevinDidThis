#!/usr/bin/env bash
# Sets up the on-device TTS: Piper (VITS) through sherpa-onnx on the CPU.
#
#   1. downloads the sherpa-onnx Android AAR into app/libs/ (git-ignored, like the Qualcomm SDKs)
#   2. downloads an English Piper voice into models/
#   3. pushes the voice to the device: <external files>/tts/piper/
#
# Why not the NPU: see docs/TTS.md (VoiceAI TTS SDK 1.1.1.0 cannot run the AI Hub 8 Gen 3
# MeloTTS assets, and has no Piper core).
#
# Usage:
#   ./scripts/fetch-piper-tts.sh                    # default voice, then push
#   VOICE=vits-piper-en_US-amy-low ./scripts/fetch-piper-tts.sh
#   NO_PUSH=1 ./scripts/fetch-piper-tts.sh          # download only
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHERPA_VERSION="${SHERPA_VERSION:-1.13.8}"
VOICE="${VOICE:-vits-piper-en_US-lessac-medium}"
PKG=com.megathon.incidentassistant

AAR="$ROOT/app/libs/sherpa-onnx-$SHERPA_VERSION.aar"
MODELS="$ROOT/models/sherpa"
mkdir -p "$ROOT/app/libs" "$MODELS"

if [[ ! -f "$AAR" ]]; then
  echo "downloading sherpa-onnx $SHERPA_VERSION AAR"
  curl -fSL -o "$AAR" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
fi

if [[ ! -d "$MODELS/$VOICE" ]]; then
  echo "downloading voice $VOICE"
  curl -fSL -o "$MODELS/$VOICE.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$VOICE.tar.bz2"
  tar xjf "$MODELS/$VOICE.tar.bz2" -C "$MODELS"
  rm -f "$MODELS/$VOICE.tar.bz2"
fi

[[ -n "${NO_PUSH:-}" ]] && { echo "done (not pushed)"; exit 0; }

adb get-state >/dev/null || { echo "no device" >&2; exit 1; }
DEST="/sdcard/Android/data/$PKG/files/tts/piper"
echo "pushing $VOICE to $DEST"
adb shell "rm -rf '$DEST' && mkdir -p '$DEST'"
adb push "$MODELS/$VOICE/"*.onnx "$MODELS/$VOICE/tokens.txt" "$DEST/"
# adb push cannot create nested dirs under Android/data (secure_mkdirs: Operation not
# permitted); stage in /data/local/tmp and copy on the device instead.
adb shell "rm -rf /data/local/tmp/espeak-ng-data"
adb push "$MODELS/$VOICE/espeak-ng-data" /data/local/tmp/ >/dev/null
adb shell "cp -r /data/local/tmp/espeak-ng-data '$DEST/' && rm -rf /data/local/tmp/espeak-ng-data"
adb shell "ls -la '$DEST'"
