#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{Inspector,EvidenceRows,EvidenceJson,ScanOptions,ScanEvidence,ScanReport,ScanComparison}.java \
  tests/ScanComparisonTest.java
java -Xmx256m -cp "$tmp/classes:$json_test_jar" org.diemobile.ScanComparisonTest
