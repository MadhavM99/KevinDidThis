#!/usr/bin/env bash
# Pushes Whisper model files onto a connected device for the Incident Assistant app.
#
# Requires THREE model files, all in ONE directory:
#   encoder_model_htp.bin  decoder_model_htp.bin   <- AI Hub, runtime "Qualcomm AI Runtime"
#                                                     (NOT the voice_ai runtime page's
#                                                      Download Model button - see README)
#   vocab.bin              <- generated, see notebook/whisper/npu/whisper_vocab/README.md
# plus the DSP-side skel for your SoC's HTP version:
#   libQnnHtpVxxSkel.so    <- from the QNN package, xx per the table below
#
# HTP version by SoC (from whisper_sdk/sampleapp/.../java/README.md):
#   SM8975 V85 | SM8850/SM8845 V81 | SM8750 V79 | SM8650 V75
#   QCS8550/SM8550/SM8635/SM7750/SM7635 V73 | QCM6490 V68
#
# Usage:
#   HTP_VERSION=V75 ./scripts/push-whisper-models.sh /path/to/model/dir
set -euo pipefail

PKG=com.megathon.incidentassistant
HTP_VERSION="${HTP_VERSION:-V75}"
QNN_ROOT="${QNN_ROOT:-$(ls -d /opt/qcom/aistack/qairt/* 2>/dev/null | sort -V | tail -1)}"

SRC="${1:?usage: push-whisper-models.sh <dir containing encoder/decoder/vocab.bin>}"

for f in encoder_model_htp.bin decoder_model_htp.bin vocab.bin; do
  [[ -f "$SRC/$f" ]] || { echo "missing: $SRC/$f" >&2; exit 1; }
done

adb get-state >/dev/null || { echo "no device" >&2; exit 1; }

# App-private external dir: no root / remount needed.
DEST="/sdcard/Android/data/$PKG/files/whisper"
echo "pushing to $DEST"
adb shell "mkdir -p '$DEST'"
adb push "$SRC/encoder_model_htp.bin" "$DEST/"
adb push "$SRC/decoder_model_htp.bin" "$DEST/"
adb push "$SRC/vocab.bin" "$DEST/"

SKEL="$QNN_ROOT/lib/hexagon-${HTP_VERSION#V}/unsigned/libQnnHtp${HTP_VERSION}Skel.so"
if [[ -f "$SKEL" ]]; then
  adb push "$SKEL" "$DEST/"
else
  echo "WARN: skel not found at $SKEL (set HTP_VERSION)" >&2
fi

echo
echo "Installed:"
adb shell "ls -la '$DEST'"
echo
echo "Now: ./gradlew :app:installDebug  (the app detects these files and swaps in real ASR)"
