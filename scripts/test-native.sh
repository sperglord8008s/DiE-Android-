#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cmake -S app/src/main/cpp -B "$tmp/native" -DCMAKE_BUILD_TYPE=Release
cmake --build "$tmp/native" --target diemobile --parallel 4
cc -O0 -g -Wl,--export-dynamic tests/native_fixture.c -o "$tmp/fixture"
javac -d "$tmp/classes" app/src/main/java/org/diemobile/Engine.java app/src/main/java/org/diemobile/Inspector.java tests/NativeToolsTest.java
mkdir -p "$tmp/custom"
java -Djava.library.path="$tmp/native" -cp "$tmp/classes" org.diemobile.NativeToolsTest "$tmp/fixture" "$PWD/generated-assets/db" "$tmp/custom"
