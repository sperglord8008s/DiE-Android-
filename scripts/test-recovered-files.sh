#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{Inspector,EvidenceRows,EvidenceJson,InputSnapshot,FileRange,RangeAnalysis,PreparedExport,OwnedContent,ContentMap,RecoveredFiles}.java \
  tests/fixtures/metadata/MetadataFixtureFactory.java tests/RecoveredFilesTest.java
java -Xmx128m -cp "$tmp/classes:$json_test_jar" org.diemobile.RecoveredFilesTest "$tmp/oracle"
python3 - "$tmp/oracle" <<'PY'
from pathlib import Path
import hashlib,json,sys,zipfile
root=Path(sys.argv[1]);oracle=json.loads((root/'oracle.json').read_bytes())
with zipfile.ZipFile(root/'recovered-files.zip') as archive:
 assert set(archive.namelist())==set(oracle)|{'_recovery.json'}
 manifest=json.loads(archive.read('_recovery.json'))
 for item in manifest['files']:
  name=item['file'];assert '/' not in name and '\\' not in name
  data=archive.read(name)
  assert data==(root/f'expected-{oracle[name]}').read_bytes()
  assert hashlib.sha256(data).hexdigest()==item['sha256'] and len(data)==item['sizeBytes']
 assert any(item['mime']=='image/jpeg' and item['file'].endswith('.jpg') for item in manifest['files'])
 assert any(item['mime']=='audio/wav' and item['file'].endswith('.wav') for item in manifest['files'])
print('Independent Python ZIP reader verified every actual file byte, MIME, filename and SHA-256.')
PY
