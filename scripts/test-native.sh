#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
build="${NATIVE_BUILD_DIR:-$tmp/native}"
cmake -S app/src/main/cpp -B "$build" -DCMAKE_BUILD_TYPE=Release
cmake --build "$build" --target diemobile --parallel 4
cc -O0 -g -Wl,--export-dynamic tests/native_fixture.c -o "$tmp/fixture"
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" app/src/main/java/org/diemobile/{Engine,Inspector,CustomRuleStore,NativeTable,NativeStructures,NativeEntropy,FileRange,RangeAnalysis}.java tests/{NativeToolsTest,NativeRecordsTest,NativeStructuresTest,NativeEntropyTest,NativeUnavailableTest}.java
mkdir -p "$tmp/custom"
python3 tests/generate-native-fixtures.py "$tmp/formats"
timeout 180s java -Djava.library.path="$build" -cp "$tmp/classes:$json_test_jar" org.diemobile.NativeToolsTest "$tmp/fixture" "$PWD/generated-assets/db" "$tmp/custom" "$tmp/formats"
timeout 60s java -Djava.library.path="$build" -cp "$tmp/classes:$json_test_jar" org.diemobile.NativeRecordsTest "$tmp/fixture" "$tmp/formats"
timeout 60s java -Djava.library.path="$build" -cp "$tmp/classes:$json_test_jar" org.diemobile.NativeStructuresTest "$tmp/fixture" "$tmp/formats" "$tmp/structures"
python3 tests/check-native-structures.py "$tmp/formats" "$tmp/structures"
timeout 60s java -Djava.library.path="$build" -cp "$tmp/classes:$json_test_jar" org.diemobile.NativeEntropyTest "$tmp/formats" "$tmp/entropy"
python3 tests/check-native-entropy.py "$tmp/formats" "$tmp/entropy"
jni_home="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
cc -std=gnu11 -O2 -Iapp/src/main/cpp -Ivendor/CDiE/src -Ivendor/CDiE/dep/xxfclib/include \
  -I"$jni_home/include" -I"$jni_home/include/linux" tests/structures_adapter_test.c \
  -L"$build" -Wl,-rpath,"$build" -ldiemobile -o "$tmp/structure-values"
"$tmp/structure-values" "$tmp/structure-values.jsonl"
python3 tests/check-structure-values.py "$tmp/structure-values.jsonl"
mkdir -p "$tmp/no-native"
java -Djava.library.path="$tmp/no-native" -cp "$tmp/classes:$json_test_jar" org.diemobile.NativeUnavailableTest
