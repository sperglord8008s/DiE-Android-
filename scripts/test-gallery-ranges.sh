#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build-photorec.sh host
bash scripts/build-archive.sh host
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{Inspector,EvidenceRows,EvidenceJson,InputSnapshot,InvestigationBackend,FileRange,RangeAnalysis,PreparedExport,PhotoRecEngine,PhotoRecReport,BinaryRunner,CarvingBackend,PhotoRecBackend,BinwalkBackend,GalleryDiscovery,RecoveredFiles,ContentMap,OwnedContent,GallerizeSession,ArchiveLaunch,NativeArchiveStore,ArchiveRunner,ArchiveEngine,NativeArchiveSession,ContentOrigin}.java \
  tests/GalleryRangesTest.java
java -Xmx192m --add-opens=java.base/java.io=ALL-UNNAMED \
  -Djava.library.path="$PWD/app/build/host-photorec:$PWD/app/build/host-archive" \
  -cp "$tmp/classes:$json_test_jar" org.diemobile.GalleryRangesTest "$tmp/oracle"
python3 - "$tmp/oracle" <<'PY'
import json
from pathlib import Path
import sys
root=Path(sys.argv[1])
report=json.loads((root/'gallery-report.json').read_bytes())
expected=(root/'expected-name-utf16be').read_bytes()
assert report['source']['name'].encode('utf-16-be','surrogatepass')==expected
rows=[json.loads(line) for line in (root/'origin.ndjson').read_text().split('\n') if line]
assert rows[0]['relationship']['kind']=='archive-member'
assert rows[0]['relationship']['physicalOffset'] is None
trail=rows[1]['value']
launch=json.loads(trail['originalLaunchRaw'])
assert launch['recoveredExtentBytes'] is None
assert launch['scopeOffset']==37
assert json.loads(launch['findingRecordRaw'])['reportedSizeBytes'] is None
context=launch['galleryContext']
assert context['card']['sizeBytes'] is None
assert context['gallery']['source']['name'].encode('utf-16-be','surrogatepass')==expected
assert rows[-1]['witnessCount']==2
print('Independent Python reconstructed complete gallery context/origin, original unknown extents and exact UTF-16 names after source expiry.')
PY
