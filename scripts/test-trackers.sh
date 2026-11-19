#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check-regex-sources.py
python3 scripts/tracker-unicode.py
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
python3 scripts/tracker-reference.py "$tmp/reference.json"
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
if command -v rg >/dev/null; then rg --files regex-engine/third_party/re2j -g '*.java' > "$tmp/sources";
else find regex-engine/third_party/re2j -name '*.java' > "$tmp/sources"; fi
javac --release 8 -d "$tmp/engine" @"$tmp/sources"
javac -cp "$json_test_jar:$tmp/engine" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{Inspector,EvidenceJson,EvidenceRows,InputSnapshot,InvestigationBackend,CatalogJson,TrackerJson,TrackerUnicode,TrackerRegex,TrackerPrefilter,TrackerCatalog,TrackerCatalogBackend}.java tests/TrackerCatalogTest.java tests/TrackerPrefilterTest.java
tracker_audit=()
if [[ -n "${TRACKER_SNAPSHOT:-}" ]]; then tracker_audit+=("$TRACKER_SNAPSHOT"); fi
java -cp "$tmp/classes:$tmp/engine:$json_test_jar" org.diemobile.TrackerCatalogTest "$tmp/reference.json" "${TRACKER_DEPS_DIR:-app/build/tracker-deps}/UnicodeData-15.0.0.txt" "$tmp" "${tracker_audit[@]}"
java -cp "$tmp/classes:$tmp/engine:$json_test_jar" org.diemobile.TrackerPrefilterTest
