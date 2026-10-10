#!/usr/bin/env bash
# Builds the single combined Piper TTS model file the VoiceAI TTS SDK requires.
#
# WHY THIS EXISTS (same trap as MeloTTS)
#   `TTS.setModelPath()` is NOT a directory: `TTSModel::loadModel()` reads the WHOLE file
#   into rpcmem and hands its fd to the DSP. The SDK wants one combined blob.
#
#   Qualcomm's PiperTTS-EN (AI Hub model card `pipertts_en`, MIT) ships the sub-models
#   separately — T5 text encoder/decoder, VITS encoder/flow/decoder and the sdp head —
#   and the VoiceAI_TTS package's notebook script recombines them, exactly like it does
#   for MeloTTS. See the QIDK references: AI Hub PiperTTS-EN, qai-appbuilder pipertts_en
#   sample ("runs fully on the Snapdragon NPU (HTP)"), Audio Analytics container
#   (`piper-tts-en` @ 22050 Hz).
#
# Usage:
#   ./scripts/build-piper-model.sh <aihub-model-dir> [output.qnn]
set -euo pipefail

SRC="${1:?usage: build-piper-model.sh <aihub-model-dir> [out.qnn]}"
OUT="${2:-$HOME/sources/qualcomm-ps/models/pipertts_en_8gen3/piper_en.64_bit.qnn_v2.50.0.qnn}"

TTS_ROOT="${TTS_ROOT:-/opt/qcom/VoiceAI_TTS/1.1.1.0}"
# Path to the piper conversion script inside the VoiceAI_TTS package (mirrors the melo
# notebook layout; adjust to the exact folder in your installed version).
GEN="${PIPER_GEN_DIR:-$TTS_ROOT/notebook/piper/npu/model_conversion_scripts/piper/py/qnn_model_generation.py}"
[[ -f "$GEN" ]] || { echo "missing piper conversion script: $GEN (set PIPER_GEN_DIR)" >&2; exit 1; }

# Values come from the AI Hub PiperTTS-EN package's config.json ("runtime") / metadata.json.
QNN_MAJOR="${QNN_MAJOR:-2}"
QNN_MINOR="${QNN_MINOR:-50}"
QNN_PATCH="${QNN_PATCH:-0}"
ARCH="${ARCH:-64}"
LANG="${LANG:-en}"
SCRATCH="${SCRATCH:-3200000}"

for f in t5_encoder.bin t5_decoder.bin encoder.bin flow.bin decoder.bin sdp.bin; do
  [[ -f "$SRC/$f" ]] || { echo "missing: $SRC/$f" >&2; exit 1; }
done

# TODO(device): confirm the exact flag names against the shipped
# qnn_model_generation.py — they mirror the melo script's flags.
GEN_ARGS="${PIPER_GEN_ARGS:---t5_encoder_model $SRC/t5_encoder.bin \
--t5_decoder_model $SRC/t5_decoder.bin \
--vits_encoder_model $SRC/encoder.bin \
--vits_flow_model $SRC/flow.bin \
--vits_decoder_model $SRC/decoder.bin \
--sdp_model $SRC/sdp.bin \
--qnn_version_major $QNN_MAJOR \
--qnn_version_minor $QNN_MINOR \
--qnn_version_patch $QNN_PATCH \
--arch $ARCH \
--model_lang $LANG \
--scratch_mem_size_req $SCRATCH \
--path_out_model $OUT}"

mkdir -p "$(dirname "$OUT")"
PYTHONPATH="$(dirname "$GEN")${PYTHONPATH:+:$PYTHONPATH}" \
  python3 "$GEN" $GEN_ARGS

echo
echo "Built: $OUT ($(stat -c%s "$OUT") bytes)"
echo "Now:   ./scripts/push-tts-model.sh $OUT"