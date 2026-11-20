#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{ContentOrigin,OwnedContent,PreparedExport,InputSnapshot,EvidenceRows,EvidenceJson,RangeAnalysis,FileRange,Inspector}.java \
  tests/ContentOriginTest.java
java -Xmx128m -cp "$tmp/classes:$json_test_jar" org.diemobile.ContentOriginTest "$tmp/oracle"
python3 - "$tmp/oracle" <<'PY'
import json
from pathlib import Path
import sys
root=Path(sys.argv[1])
raw=(root/'capture.ndjson').read_bytes()
lines=raw.decode('utf-8').split('\n')[:-1]
rows=[json.loads(line) for line in lines]
assert len(rows)==4 and raw.endswith(b'\n')
assert rows[0]['schema']=='org.diemobile.content-origin'
relation=rows[0]['relationship']
assert relation['kind']=='stored-range' and relation['physicalOffset']==7
assert relation['storedSizeBytes']==rows[0]['subjectSizeBytes']
assert relation['entryIndex'] is None
witness=rows[1]['value']
assert witness['unknownField'].encode('utf-16-be', 'surrogatepass') == (root/'expected-utf16be').read_bytes()
assert witness['unknownNull'] is None and witness['exactInteger']==9007199254740993
assert witness['originalRawJson']=='{"unknown":1.2300e+009,"s":"\\ud800"}'
assert rows[2]['value']['completeDecodingVerified'] is False
assert rows[-1]=={'record':'end','witnessCount':2}
print('Independent Python JSON Lines/byte and all-65,536-UTF-16-unit witness reconstruction passed.')
PY
