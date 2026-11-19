#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-android}"
bash scripts/build-archive-libraries.sh "$mode"
python3 scripts/archive-notices.py --check
build="app/build/archive-$mode"
flags=(-DCMAKE_BUILD_TYPE=Release)
if [[ "$mode" = android ]]; then
  ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/28.2.13676358}"
  flags+=(-DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static)
  output=app/build/generated-archive-jni/arm64-v8a
else
  output=app/build/host-archive
fi
cmake -S archive-engine -B "$build" "${flags[@]}"
cmake --build "$build" --parallel 2
mkdir -p "$output"
cp "$build/libdiearchive.so" "$output/"

cp archive-engine/{NOTICE.txt,source-headers.txt} "$output/"
