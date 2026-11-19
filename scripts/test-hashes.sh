#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check-hash-sources.py
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
build="${NATIVE_BUILD_DIR:-$PWD/app/build/host-native}"
cmake -S app/src/main/cpp -B "$build" -DCMAKE_BUILD_TYPE=Release
cmake --build "$build" --target diehash --parallel 4
build=$(cd "$build" && pwd)
cc -O2 -Wall -Wextra -Ihash-engine tests/hash_boundary_test.c -L"$build" -Wl,-rpath,"$build" -ldiehash -o "$tmp/hash-boundary"
"$tmp/hash-boundary"
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{HashEngine,Inspector,EvidenceRows,InputSnapshot,HashDocument,HashBackend,InvestigationBackend,BinaryRunner}.java tests/HashInvestigationTest.java
java --add-opens=java.base/java.io=ALL-UNNAMED -Djava.library.path="$build" -cp "$tmp/classes:$json_test_jar" org.diemobile.HashInvestigationTest "$tmp"
