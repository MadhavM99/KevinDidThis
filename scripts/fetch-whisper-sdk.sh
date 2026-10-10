#!/usr/bin/env bash
# Copies the Qualcomm VoiceAI ASR (Whisper) Android runtime into app/libs.
#
# Sources (installed via `qpm-cli --install VoiceAI_ASR`):
#   /opt/qcom/VoiceAI_ASR/<ver>/whisper_sdk/...
# Plus the matching QNN (QAIRT) Android libs.
#
# Usage:
#   ./scripts/fetch-whisper-sdk.sh
#   WHISPER_TYPE=whisper_small_encoder_quantized_decoder_fp16 ./scripts/fetch-whisper-sdk.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ASR_ROOT="${ASR_ROOT:-/opt/qcom/VoiceAI_ASR/2.7.1.0}"
QNN_ROOT="${QNN_ROOT:-$(ls -d /opt/qcom/aistack/qairt/* 2>/dev/null | sort -V | tail -1)}"
WHISPER_TYPE="${WHISPER_TYPE:-whisper_all_quantized}"

SDK_LIBS="$ASR_ROOT/whisper_sdk/libs/npu/rpc_libraries"
ANDROID_DIR="$SDK_LIBS/android/$WHISPER_TYPE"

for d in "$ANDROID_DIR" "$QNN_ROOT"; do
  [[ -d "$d" ]] || { echo "missing: $d" >&2; exit 1; }
done

OUT="$ROOT/app/libs"
ARM="$OUT/arm64-v8a"
mkdir -p "$ARM"

echo "whisper type : $WHISPER_TYPE"
echo "ASR root     : $ASR_ROOT"
echo "QNN root     : $QNN_ROOT"
echo "output       : $OUT"

# 1. Java API
cp -v "$ANDROID_DIR/whisper-sdk.jar" "$OUT/"

# 2. Whisper native libs
cp -v "$ANDROID_DIR"/arm64-v8a/*.so "$ARM/"

# 3. VAD model (shipped as a .so by the SDK, not inside the model dir)
cp -v "$SDK_LIBS/assets/arm64-v8a_android/libnnvad_model.so" "$ARM/"

# 4. QNN host-side libs. The HTP stub version must match the target SoC:
#      SM8975 V85 | SM8850/SM8845 V81 | SM8750 V79 | SM8650 V75
#      QCS8550/SM8550/SM8635/SM7750/SM7635 V73 | QCM6490 V68
#    Every stub is copied so one APK covers all supported devices.
for src in \
  "$QNN_ROOT/lib/aarch64-android/libQnnHtp.so" \
  "$QNN_ROOT/lib/aarch64-android/libQnnSystem.so" \
  "$QNN_ROOT/lib/aarch64-android/libQnnCpu.so" \
  "$QNN_ROOT"/lib/aarch64-android/libQnnHtpV*Stub.so; do
  [[ -f "$src" ]] && cp -v "$src" "$ARM/"
done

echo
echo "Done. Device-side files still required (NOT bundled - see README):"
echo "  encoder_model_htp.bin  decoder_model_htp.bin  vocab.bin"
echo "  libQnnHtpVxxSkel.so    (xx = HTP version of your SoC)"
echo "  all of the above go in one directory, passed to Whisper.init(path)"
