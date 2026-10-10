#!/usr/bin/env bash
# Builds the single combined MeloTTS model file that the TTS SDK requires.
#
# WHY THIS EXISTS
#   `TTS.setModelPath()` is NOT a directory. `TTSModel::loadModel()` (tts_wrapper/src/
#   TTSModel.cpp) does `std::ifstream fp(model_file, std::ios::binary)`, reads the WHOLE
#   file into rpcmem and hands its fd to the DSP. So the SDK wants one combined blob.
#
#   AI Hub's "Qualcomm Voice AI" download for melotts_en ships the 8 sub-models
#   separately (encoder.bin, flow.bin, decoder.bin, bert_wrapper.bin, t5_*.bin, ...).
#   The shipped notebook script recombines them, and it is pure stdlib Python, so it
#   runs on any machine - no torch, no notebooks.
#
# Usage:
#   ./scripts/build-melo-model.sh <aihub-model-dir> [output.qnn]
set -euo pipefail

SRC="${1:?usage: build-melo-model.sh <aihub-model-dir> [out.qnn]}"
OUT="${2:-$HOME/sources/qualcomm-ps/models/melotts_en_8gen3/melo_en.64_bit.qnn_v2.50.0.qnn}"

TTS_ROOT="${TTS_ROOT:-/opt/qcom/VoiceAI_TTS/1.1.1.0}"
GEN="$TTS_ROOT/notebook/melo/npu/model_conversion_scripts/melo/py"

# Values come from the AI Hub package's config.json ("runtime") / metadata.json.
QNN_MAJOR="${QNN_MAJOR:-2}"
QNN_MINOR="${QNN_MINOR:-50}"
QNN_PATCH="${QNN_PATCH:-0}"
ARCH="${ARCH:-64}"
LANG="${LANG:-en}"
SCRATCH="${SCRATCH:-3200000}"

for f in bert_wrapper.bin bert_en_tokenizer.bin bert_normalizer.bin \
         encoder.bin flow.bin decoder.bin t5_encoder.bin t5_decoder.bin; do
  [[ -f "$SRC/$f" ]] || { echo "missing: $SRC/$f" >&2; exit 1; }
done

mkdir -p "$(dirname "$OUT")"

# Run from a writable copy: the script imports en_dict_generation.* / en_g2p_dict_generation.*
# as sibling modules, and those dictionaries ship pre-generated (no notebook step needed).
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -r "$GEN" "$WORK/py"

python3 "$WORK/py/qnn_model_generation.py" \
  --bert_model          "$SRC/bert_wrapper.bin" \
  --bert_tokenizer      "$SRC/bert_en_tokenizer.bin" \
  --bert_normalizer     "$SRC/bert_normalizer.bin" \
  --melo_encoder_model  "$SRC/encoder.bin" \
  --melo_flow_model     "$SRC/flow.bin" \
  --melo_decoder_model  "$SRC/decoder.bin" \
  --g2p_enc_model       "$SRC/t5_encoder.bin" \
  --g2p_dec_model       "$SRC/t5_decoder.bin" \
  --qnn_version_major "$QNN_MAJOR" \
  --qnn_version_minor "$QNN_MINOR" \
  --qnn_version_patch "$QNN_PATCH" \
  --arch "$ARCH" \
  --model_lang "$LANG" \
  --scratch_mem_size_req "$SCRATCH" \
  --path_out_model "$OUT"

echo
echo "Built: $OUT ($(stat -c%s "$OUT") bytes)"
echo "Now:   ./scripts/push-tts-model.sh $OUT"
