#!/usr/bin/env bash
set -euo pipefail

# Downloads sherpa-onnx runtime libs + models needed by SpeechService.
# Writes only into app/src/main/{assets,jniLibs} (both gitignored).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ZIPFORMER_MODEL_DIR="app/src/main/assets/sherpa-onnx-zipformer-en-2023-04-01"
ASSETS_DIR="app/src/main/assets"
JNI_DIR="app/src/main/jniLibs/arm64-v8a"

ZIPFORMER_TARBALL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-en-2023-04-01.tar.bz2"
SILERO_VAD_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
SHERPA_ANDROID_TARBALL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.23/sherpa-onnx-v1.12.23-android.tar.bz2"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "ERROR: Missing required command: $1" >&2
    exit 1
  fi
}

need_cmd curl
need_cmd tar
need_cmd mkdir
need_cmd cp
need_cmd rm
need_cmd mktemp

mkdir -p "$ZIPFORMER_MODEL_DIR" "$ASSETS_DIR" "$JNI_DIR"

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

fetch() {
  local url="$1"
  local out="$2"

  if [[ -s "$out" ]]; then
    echo "OK  : cached $(basename "$out")"
    return 0
  fi

  echo "GET : $url"
  curl -fL --retry 3 --retry-delay 2 --connect-timeout 10 -o "$out" "$url"
}

extract_zipformer() {
  local tarball="$1"
  local dest="$2"

  local required_a=(
    "sherpa-onnx-zipformer-en-2023-04-01/encoder-epoch-99-avg-1.int8.onnx"
    "sherpa-onnx-zipformer-en-2023-04-01/decoder-epoch-99-avg-1.int8.onnx"
    "sherpa-onnx-zipformer-en-2023-04-01/joiner-epoch-99-avg-1.int8.onnx"
    "sherpa-onnx-zipformer-en-2023-04-01/tokens.txt"
    "sherpa-onnx-zipformer-en-2023-04-01/bpe.model"
  )
  local required_b=(
    "encoder-epoch-99-avg-1.int8.onnx"
    "decoder-epoch-99-avg-1.int8.onnx"
    "joiner-epoch-99-avg-1.int8.onnx"
    "tokens.txt"
    "bpe.model"
  )

  local missing=0
  for rel in "${required_a[@]}"; do
    local out_path="$dest/$(basename "$rel")"
    if [[ -s "$out_path" ]]; then
      continue
    fi
    missing=1
    break
  done

  if [[ "$missing" -eq 0 ]]; then
    echo "OK  : Zipformer model files already present"
    return 0
  fi

  echo "EXTR: zipformer model files"
  local extracted=()
  if tar -xjf "$tarball" -C "$TMP_DIR" "${required_a[@]}"; then
    extracted=("${required_a[@]}")
  elif tar -xjf "$tarball" -C "$TMP_DIR" "${required_b[@]}"; then
    extracted=("${required_b[@]}")
  elif tar -xjf "$tarball" -C "$TMP_DIR" "${required_c[@]}"; then
    extracted=("${required_c[@]}")
  else
    echo "ERROR: Failed to extract Zipformer model files from archive" >&2
    exit 1
  fi

  for rel in "${extracted[@]}"; do
    cp -f "$TMP_DIR/$rel" "$dest/$(basename "$rel")"
  done
}

extract_android_jni() {
  local tarball="$1"
  local dest="$2"

  local required_a=(
    "jniLibs/arm64-v8a/libonnxruntime.so"
    "jniLibs/arm64-v8a/libsherpa-onnx-jni.so"
    "jniLibs/arm64-v8a/libsherpa-onnx-c-api.so"
    "jniLibs/arm64-v8a/libsherpa-onnx-cxx-api.so"
  )
  local required_b=(
    "./jniLibs/arm64-v8a/libonnxruntime.so"
    "./jniLibs/arm64-v8a/libsherpa-onnx-jni.so"
    "./jniLibs/arm64-v8a/libsherpa-onnx-c-api.so"
    "./jniLibs/arm64-v8a/libsherpa-onnx-cxx-api.so"
  )
  local required_c=(
    "sherpa-onnx-v1.12.23-android/jniLibs/arm64-v8a/libonnxruntime.so"
    "sherpa-onnx-v1.12.23-android/jniLibs/arm64-v8a/libsherpa-onnx-jni.so"
    "sherpa-onnx-v1.12.23-android/jniLibs/arm64-v8a/libsherpa-onnx-c-api.so"
    "sherpa-onnx-v1.12.23-android/jniLibs/arm64-v8a/libsherpa-onnx-cxx-api.so"
  )

  local missing=0
  for rel in "${required_a[@]}"; do
    local out_path="$dest/$(basename "$rel")"
    if [[ -s "$out_path" ]]; then
      continue
    fi
    missing=1
    break
  done

  if [[ "$missing" -eq 0 ]]; then
    echo "OK  : JNI libs already present"
    return 0
  fi

  echo "EXTR: android jni libs"
  local extracted=()
  if tar -xjf "$tarball" -C "$TMP_DIR" "${required_a[@]}"; then
    extracted=("${required_a[@]}")
  elif tar -xjf "$tarball" -C "$TMP_DIR" "${required_b[@]}"; then
    extracted=("${required_b[@]}")
  else
    echo "ERROR: Failed to extract Android JNI libs from archive" >&2
    exit 1
  fi

  for rel in "${extracted[@]}"; do
    cp -f "$TMP_DIR/$rel" "$dest/$(basename "$rel")"
  done
}

ZIPFORMER_TARBALL="$TMP_DIR/zipformer.tar.bz2"
SHERPA_ANDROID_TARBALL="$TMP_DIR/sherpa-android.tar.bz2"

if [[ -s "$ZIPFORMER_MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx" ]] && \
   [[ -s "$ZIPFORMER_MODEL_DIR/decoder-epoch-99-avg-1.int8.onnx" ]] && \
   [[ -s "$ZIPFORMER_MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx" ]] && \
   [[ -s "$ZIPFORMER_MODEL_DIR/tokens.txt" ]] && \
   [[ -s "$ZIPFORMER_MODEL_DIR/bpe.model" ]]; then
  echo "OK  : Zipformer model files already present"
else
  fetch "$ZIPFORMER_TARBALL_URL" "$ZIPFORMER_TARBALL"
  extract_zipformer "$ZIPFORMER_TARBALL" "$ZIPFORMER_MODEL_DIR"
fi

# Silero VAD lives at app/src/main/assets/silero_vad.onnx
if [[ -s "$ASSETS_DIR/silero_vad.onnx" ]]; then
  echo "OK  : silero_vad.onnx already present"
else
  fetch "$SILERO_VAD_URL" "$ASSETS_DIR/silero_vad.onnx"
fi

if [[ -s "$JNI_DIR/libonnxruntime.so" ]] && \
   [[ -s "$JNI_DIR/libsherpa-onnx-jni.so" ]] && \
   [[ -s "$JNI_DIR/libsherpa-onnx-c-api.so" ]] && \
   [[ -s "$JNI_DIR/libsherpa-onnx-cxx-api.so" ]]; then
  echo "OK  : JNI libs already present"
else
  fetch "$SHERPA_ANDROID_TARBALL_URL" "$SHERPA_ANDROID_TARBALL"
  extract_android_jni "$SHERPA_ANDROID_TARBALL" "$JNI_DIR"
fi

# bpe.vocab is committed in the repo. Fail fast if it is missing.
if [[ ! -s "$ZIPFORMER_MODEL_DIR/bpe.vocab" ]]; then
  echo "ERROR: Missing $ZIPFORMER_MODEL_DIR/bpe.vocab" >&2
  echo "This file should be committed in the repo. Re-clone or restore it." >&2
  exit 1
fi

echo
echo "Done. Verified files:"
ls -lh \
  "$ASSETS_DIR/silero_vad.onnx" \
  "$ZIPFORMER_MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx" \
  "$ZIPFORMER_MODEL_DIR/decoder-epoch-99-avg-1.int8.onnx" \
  "$ZIPFORMER_MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx" \
  "$ZIPFORMER_MODEL_DIR/tokens.txt" \
  "$ZIPFORMER_MODEL_DIR/bpe.model" \
  "$ZIPFORMER_MODEL_DIR/bpe.vocab" \
  "$JNI_DIR/libonnxruntime.so" \
  "$JNI_DIR/libsherpa-onnx-jni.so" \
  "$JNI_DIR/libsherpa-onnx-c-api.so" \
  "$JNI_DIR/libsherpa-onnx-cxx-api.so"
