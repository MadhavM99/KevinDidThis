#!/usr/bin/env bash
# Pushes MeloTTS runtime files onto a connected device for the Incident Assistant app.
#
# The TTS SDK needs, in ONE directory:
#   melo_en.64_bit.qnn_v2.50.0.qnn   (combined model, built by build-melo-model.sh)
#   libtts_impl_skel.so              (DSP skeleton, from the VoiceAI_TTS package)
#   libQnnHtpVXX.so + libQnnSystem.so (DSP-side QNN libs; XX = HTP version of your SoC)
#
# HTP version by SoC: SM8975 V85 | SM8850/SM8845 V81 | SM8750 V79 | SM8650 V75 |
#   QCS8550/SM8550/SM8635/SM7750/SM7635 V73 | QCM6490 V68
#
# Usage: HTP_VERSION=V75 ./scripts/push-tts-model.sh <path/to/melo_en...qnn>
set -euo pipefail

PKG=com.megathon.incidentassistant
HTP_VERSION="${HTP_VERSION:-V75}"
TTS_ROOT="${TTS_ROOT:-/opt/qcom/VoiceAI_TTS/1.1.1.0}"
QNN_ROOT="${QNN_ROOT:-$(ls -d /opt/qcom/aistack/qairt/* 2>/dev/null | sort -V | tail -1)}"

MODEL="${1:?usage: push-tts-model.sh <path/to/combined .qnn model>}"
[[ -f "$MODEL" ]] || { echo "no such model: $MODEL" >&2; exit 1; }

SKEL="$TTS_ROOT/melo_sdk/libs/npu/rpc_libraries/cdsp/libtts_impl_skel.so"
DSPLIB="$QNN_ROOT/lib/hexagon-$(echo "$HTP_VERSION" | tr 'A-Z' 'a-z')/unsigned"
[[ -f "$SKEL" ]] || { echo "missing skel: $SKEL" >&2; exit 1; }
[[ -f "$DSPLIB/libQnnHtp${HTP_VERSION}.so" ]] || { echo "missing $DSPLIB/libQnnHtp${HTP_VERSION}.so" >&2; exit 1; }

adb get-state >/dev/null || { echo "no device" >&2; exit 1; }

# App-private external dir: no root / remount needed (unlike the sample's /vendor/etc/tts).
DEST="/sdcard/Android/data/$PKG/files/tts"
echo "pushing to $DEST"
adb shell "mkdir -p '$DEST'"
adb push "$MODEL" "$DEST/"
adb push "$SKEL" "$DEST/"
adb push "$DSPLIB/libQnnHtp${HTP_VERSION}.so" "$DEST/"
adb push "$DSPLIB/libQnnSystem.so" "$DEST/"

echo; echo "Installed:"; adb shell "ls -la '$DEST'"

# Optional cDSP logging for debugging:
#   adb shell "echo 0x1f > '$DEST/com.example.tts.farf'"
