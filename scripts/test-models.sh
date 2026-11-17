#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" app/src/main/java/org/diemobile/{Inspector,ScanOptions,ScanEvidence,ScanReport,NativeTable,FileRange,RangeAnalysis}.java tests/{ScanEvidenceTest,NativeTableTest}.java
java -cp "$tmp/classes:$json_test_jar" org.diemobile.ScanEvidenceTest
java -cp "$tmp/classes:$json_test_jar" org.diemobile.NativeTableTest
