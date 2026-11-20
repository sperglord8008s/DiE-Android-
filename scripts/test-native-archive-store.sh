#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build-archive.sh host
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
python3 tests/generate-archive-fixtures.py "$tmp/fixtures"
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{ArchiveEngine,ArchiveRunner,NativeArchiveStore,NativeArchiveSession,InputSnapshot,EvidenceRows,RangeAnalysis,FileRange,Inspector}.java \
  tests/NativeArchiveStoreTest.java
java --add-opens=java.base/java.io=ALL-UNNAMED -Djava.library.path="$PWD/app/build/host-archive" \
  -cp "$tmp/classes:$json_test_jar" org.diemobile.NativeArchiveStoreTest "$tmp/fixtures"
