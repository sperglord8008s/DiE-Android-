#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f vendor/LibChecker-Rules/tools/rules.py ]]; then
  echo 'Fetch the pinned sources with bash scripts/fetch-knowledge.sh first.' >&2; exit 1
fi
python3 scripts/check-regex-sources.py
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
python3 scripts/compile-libchecker.py vendor/LibChecker-Rules "$tmp/catalog.json" --fixtures "$tmp/fixtures.json"
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
if command -v rg >/dev/null; then rg --files regex-engine/third_party/re2j -g '*.java' > "$tmp/sources";
else find regex-engine/third_party/re2j -name '*.java' > "$tmp/sources"; fi
javac --release 8 -d "$tmp/engine" @"$tmp/sources"
javac -cp "$json_test_jar:$tmp/engine" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{Inspector,EvidenceJson,EvidenceRows,InvestigationBackend,NameMatcher,KnowledgeCatalog,SdkCatalogBackend}.java tests/KnowledgeCatalogTest.java
java -cp "$tmp/classes:$tmp/engine:$json_test_jar" org.diemobile.KnowledgeCatalogTest "$tmp/catalog.json" "$tmp/fixtures.json" "$tmp"
