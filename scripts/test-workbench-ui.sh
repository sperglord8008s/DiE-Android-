#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f vendor/metadata-engine/revision.txt ]]; then bash scripts/fetch-metadata.sh; fi
build="${NATIVE_BUILD_DIR:-$PWD/app/build/host-native}"
fixtures="$PWD/app/build/host-ui-fixtures"
cmake -S app/src/main/cpp -B "$build" -DCMAKE_BUILD_TYPE=Release
cmake --build "$build" --target diemobile --parallel 4
build=$(cd "$build" && pwd)
python3 tests/generate-native-fixtures.py "$fixtures"
cc -O0 -g -Wl,--export-dynamic tests/native_fixture.c -o "$fixtures/elf-fixture"
./gradlew --no-daemon -PhostNativeLibraryDir="$build" -PnativeFixturesDir="$fixtures" :app:testDebugUnitTest
