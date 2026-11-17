#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
javac -d "$tmp" app/src/main/java/org/diemobile/{Inspector,CustomRuleStore}.java tests/{InspectorTest,CustomRuleStoreTest}.java
java -cp "$tmp" org.diemobile.InspectorTest
java -cp "$tmp" org.diemobile.CustomRuleStoreTest
