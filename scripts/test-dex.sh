#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
source scripts/test-dex-dependencies.sh
load_dex_test_dependencies "$tmp"
javac -cp "$dex_test_classpath:$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{Inspector,FileRange,RangeAnalysis,PreparedExport,EvidenceRows,DexQuery,DexDocument,DexValues,DexTables,DexCode,DexSmali}.java \
  tests/fixtures/dex/DexFixtureFactory.java tests/DexInvestigationTest.java
java -cp "$tmp/classes:$dex_test_classpath:$json_test_jar" org.diemobile.DexInvestigationTest "$tmp"
