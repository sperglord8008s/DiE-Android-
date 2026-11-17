#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/fetch-metadata.sh
python3 tests/test-metadata-source.py "${METADATA_DEPS_DIR:-$PWD/vendor/metadata-downloads}"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
python3 - "$tmp/engine-sources" <<'PY'
from pathlib import Path
import sys
sources = sorted(str(path) for root in ('vendor/metadata-engine', 'metadata-engine/src/main/java') for path in Path(root).rglob('*.java'))
Path(sys.argv[1]).write_text('\n'.join(sources) + '\n')
PY
javac --release 8 -d "$tmp/engine" @"$tmp/engine-sources"
javac -cp "$tmp/engine:$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{Inspector,EvidenceRows,InputSnapshot,FileRange,RangeAnalysis,PreparedExport,InvestigationBackend,MetadataValues,MetadataDocument,MetadataBackend}.java \
  tests/fixtures/metadata/MetadataFixtureFactory.java tests/MetadataInvestigationTest.java
java -cp "$tmp/classes:$tmp/engine:$json_test_jar" org.diemobile.MetadataInvestigationTest "$tmp"
