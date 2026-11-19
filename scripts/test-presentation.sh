#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
javac -d "$tmp" app/src/main/java/org/diemobile/{Inspector,ToolCatalog,ReferenceSections}.java tests/PresentationCatalogTest.java
java -cp "$tmp" org.diemobile.PresentationCatalogTest
