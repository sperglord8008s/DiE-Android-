#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
javac -d "$tmp" app/src/main/java/org/diemobile/Inspector.java tests/InspectorTest.java
java -cp "$tmp" org.diemobile.InspectorTest
