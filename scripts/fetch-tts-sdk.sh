#!/usr/bin/env bash
# Copies the Qualcomm VoiceAI TTS (MeloTTS) Android runtime into app/libs.
#
# Source: /opt/qcom/VoiceAI_TTS/<ver>/  (installed via `qpm-cli --install voiceai_tts`)
# QNN libs are shared with the Whisper ASR path and already fetched by fetch-whisper-sdk.sh.
#
# Usage: ./scripts/fetch-tts-sdk.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TTS_ROOT="${TTS_ROOT:-/opt/qcom/VoiceAI_TTS/1.1.1.0}"

SDK="$TTS_ROOT/melo_sdk/libs/npu/rpc_libraries"
[[ -d "$SDK/android/arm64-v8a" ]] || { echo "missing: $SDK" >&2; exit 1; }

OUT="$ROOT/app/libs"
ARM="$OUT/arm64-v8a"
mkdir -p "$ARM"

echo "TTS root: $TTS_ROOT"
echo "output   : $OUT"

cp -v "$SDK/android/tts-sdk.jar" "$OUT/"
cp -v "$SDK/android/arm64-v8a/libtts.so" "$ARM/"
cp -v "$SDK/android/arm64-v8a/libtts_jni.so" "$ARM/"

# NOTE: libtts_impl_skel.so is DSP-side and must be pushed to the device (see
# scripts/push-tts-model.sh) - it is NOT packaged into the APK.

echo
echo "Done. Device-side files still required (see docs/TTS.md):"
echo "  melo_en.64_bit.qnn_v2.50.0.qnn   <- combined model (build it, see docs/TTS.md)"
echo "  libtts_impl_skel.so              <- melo_sdk/libs/npu/rpc_libraries/cdsp/"
echo "  libQnnHtpV75.so + libQnnSystem.so <- \${QNN}/lib/hexagon-v75/unsigned/"
