#!/usr/bin/env bash
# Builds the two Cactus on-device LLM runtimes for arm64-v8a:
#   libcactus_engine.so  Cactus v2 (Gemma 4, LFM2-VL: CQ bundles)
#   libcactus.so         Cactus v1.14 (Qwen3/3.5, FunctionGemma, Gemma 3, LFM2: int4/int8 bundles)
# Both JNI layers are renamed onto MeowClaw classes so they can share a
# process, and cloud telemetry/handoff is compiled out.
#
# usage: scripts/build-cactus.sh <out-jniLibs-dir> [work-dir]
set -euo pipefail

OUT=${1:?output jniLibs directory}
WORK=${2:-"$(dirname "$OUT")/cactus-src"}
V2_REV=2cfcdb8568eb9192323b27fd0012a834517b4419   # v2.2.2
V1_REV=40a7123b0788f5b38c5fe0daa0d909b4286fe773   # v1.14
JNI_PREFIX=Java_com_farzanshibu_meowclaw_llm_cactus
# The JNI prefix is part of the stamp: renaming the app package must rebuild.
STAMP="$OUT/arm64-v8a/.cactus-$V2_REV-$V1_REV-$JNI_PREFIX"

if [ -f "$STAMP" ]; then
  echo "Cactus runtimes up to date"
  exit 0
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
  ANDROID_NDK_HOME=$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)
fi
[ -d "${ANDROID_NDK_HOME:-}" ] || { echo "Android NDK not found (set ANDROID_NDK_HOME)"; exit 1; }
export ANDROID_NDK_HOME
if ! command -v cmake >/dev/null; then
  SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
  CMAKE_DIR=$(ls -d "$SDK"/cmake/*/bin 2>/dev/null | sort -V | tail -1)
  export PATH="$CMAKE_DIR:$PATH"
fi
STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$(ls "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" | head -1)/bin/llvm-strip"

fetch() { # dir rev
  if [ ! -d "$1/.git" ]; then
    git init -q "$1"
    git -C "$1" remote add origin https://github.com/cactus-compute/cactus
  fi
  git -C "$1" fetch -q --depth 1 origin "$2"
  git -C "$1" checkout -q --force "$2"
  git -C "$1" clean -qfdx
}

build() { # dir jni-class lib-name telemetry-file
  local dir=$1 cls=$2 lib=$3 tele=$4
  sed -i.bak "s/Java_com_cactus_CactusJNI_/${JNI_PREFIX}_${cls}_/g" "$dir/android/cactus_jni.cpp"
  # Never send telemetry or hand prompts to the cloud.
  sed -i.bak 's/bool cloud_disabled_from_env = false;/bool cloud_disabled_from_env = true;/' "$dir/$tele"
  grep -q "cloud_disabled_from_env = true" "$dir/$tele" || { echo "telemetry patch failed for $dir"; exit 1; }
  sed -i.bak 's| >/dev/null$||' "$dir/android/build.sh"
  LDFLAGS="-Wl,-Bsymbolic" bash "$dir/android/build.sh"
  mkdir -p "$OUT/arm64-v8a"
  "$STRIP" --strip-unneeded -o "$OUT/arm64-v8a/$lib" "$dir/android/$lib"
}

mkdir -p "$WORK"
fetch "$WORK/v2" "$V2_REV"
build "$WORK/v2" CactusV2Native libcactus_engine.so cactus-engine/src/telemetry_impl.cpp
fetch "$WORK/v1" "$V1_REV"
build "$WORK/v1" CactusV1Native libcactus.so cactus/telemetry/telemetry.cpp

rm -f "$OUT"/arm64-v8a/.cactus-*
touch "$STAMP"
ls -la "$OUT/arm64-v8a"
