#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f generated-assets/knowledge/uad.json ]]; then
  echo 'Run scripts/fetch-knowledge.sh before the package knowledge checks.' >&2
  exit 1
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{PackageKnowledge,PackageKnowledgeBackend,CatalogJson,EvidenceJson,EvidenceRows,InvestigationBackend,InputSnapshot,PreparedExport,RangeAnalysis,FileRange,Inspector}.java \
  tests/PackageKnowledgeTest.java
java -Xmx128m -cp "$tmp/classes:$json_test_jar" org.diemobile.PackageKnowledgeTest "$tmp/oracle" generated-assets/knowledge/uad.json
python3 - "$tmp/oracle" <<'PY'
from decimal import Decimal
import json
from pathlib import Path
import sys
root=Path(sys.argv[1])
def read(path):return json.loads(path.read_bytes(),parse_float=Decimal)
original=read(root/'fixture-original.json')
report=read(root/'fixture-catalog-report.json')
assert len(report['rows'])==len(original)==4
for row in report['rows']:assert row['fullOriginalRecord']==original[row['packageId']]
card=read(root/'fixture-card-report.json')
values={row['sourceField']:row['fullOriginalFieldValue'] for row in card['rows']}
assert values==original['app.A']
assert values['description'].encode('utf-16-be','surrogatepass')==(root/'description-utf16be').read_bytes()
assert values['unknown']['largeNumber']==Decimal('1.2300e+9999')
assert b'1.2300e+9999' in (root/'fixture-catalog-report.json').read_bytes()
assert b'9007199254740993' in (root/'fixture-catalog-report.json').read_bytes()
assert b'"negativeZero":-0' in (root/'fixture-catalog-report.json').read_bytes()
assert read(root/'fixture-description-report.json')['originalValue']==original['app.A']['description']
relations=read(root/'fixture-relations-report.json')['rows']
assert len(relations)==8 and any(r['originalTargetValue']==7 for r in relations)
class Pairs(list):pass
real=json.loads(Path('generated-assets/knowledge/uad.json').read_bytes(),object_pairs_hook=Pairs)
def normal(value):
 if isinstance(value,Pairs):return {key:normal(item) for key,item in value}
 if isinstance(value,list):return [normal(item) for item in value]
 return value
rows=read(root/'real-catalog-report.json')['rows']
assert len(rows)==len(real)==5402
assert len({key for key,value in real})==5399
assert [(r['packageId'],r['fullOriginalRecord']) for r in rows]==[(key,normal(value)) for key,value in real]
print('Independent Python reconstructed all 5,402 actual source records / 5,399 distinct IDs, including every duplicate, numeric/raw token and all 65,536 UTF-16 units.')
PY
