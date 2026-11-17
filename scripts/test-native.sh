#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
build="${NATIVE_BUILD_DIR:-$tmp/native}"
cmake -S app/src/main/cpp -B "$build" -DCMAKE_BUILD_TYPE=Release
cmake --build "$build" --target diemobile --parallel 4
cc -O0 -g -Wl,--export-dynamic tests/native_fixture.c -o "$tmp/fixture"
javac -d "$tmp/classes" app/src/main/java/org/diemobile/{Engine,Inspector,CustomRuleStore}.java tests/NativeToolsTest.java
mkdir -p "$tmp/custom"
python3 tests/generate-native-fixtures.py "$tmp/formats"
timeout 180s java -Djava.library.path="$build" -cp "$tmp/classes" org.diemobile.NativeToolsTest "$tmp/fixture" "$PWD/generated-assets/db" "$tmp/custom" "$tmp/formats"
