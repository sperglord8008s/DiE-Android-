#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build-binwalk.sh host
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{Inspector,EvidenceRows,InputSnapshot,InvestigationBackend,FileRange,RangeAnalysis,PreparedExport,BinwalkEngine,BinaryRunner,BinwalkBackend}.java \
  tests/fixtures/metadata/MetadataFixtureFactory.java tests/BinwalkInvestigationTest.java
java --add-opens=java.base/java.io=ALL-UNNAMED -Djava.library.path="$PWD/app/build/host-binwalk" -cp "$tmp/classes:$json_test_jar" org.diemobile.BinwalkInvestigationTest "$tmp"
