#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f vendor/metadata-engine/revision.txt ]]; then bash scripts/fetch-metadata.sh; fi
bash scripts/build-binwalk.sh host
bash scripts/build-mpv.sh host
if [[ ! -f app/build/generated-binwalk-jni/arm64-v8a/libdiebinwalk.so ]]; then bash scripts/build-binwalk.sh android; fi
build="${NATIVE_BUILD_DIR:-$PWD/app/build/host-native}"
fixtures="$PWD/app/build/host-ui-fixtures"
cmake -S app/src/main/cpp -B "$build" -DCMAKE_BUILD_TYPE=Release
cmake --build "$build" --target diemobile diehash --parallel 4
build=$(cd "$build" && pwd)
python3 tests/generate-native-fixtures.py "$fixtures"
DIE_REQUIRE_AAPT2=true python3 tests/generate-resource-reference.py "$fixtures/android-resources"
cc -O0 -g -Wl,--export-dynamic tests/native_fixture.c -o "$fixtures/elf-fixture"
./gradlew --no-daemon -PhostNativeLibraryDir="$build" -PnativeFixturesDir="$fixtures" :app:testDebugUnitTest
