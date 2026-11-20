#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build-photorec.sh host
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" \
 app/src/main/java/org/diemobile/{Inspector,EvidenceRows,EvidenceJson,InputSnapshot,InvestigationBackend,FileRange,RangeAnalysis,PreparedExport,PhotoRecEngine,PhotoRecReport,PhotoRecFileChecks,BinaryRunner,CarvingBackend,PhotoRecBackend,BinwalkBackend,ContentMap,RecoveredFiles,OwnedContent,GalleryDiscovery,GallerizeSession,ArchiveLaunch,NativeArchiveStore,ArchiveRunner,ArchiveEngine}.java \
 tests/fixtures/metadata/MetadataFixtureFactory.java tests/PhotoRecPdfTest.java
java -Xmx128m --add-opens=java.base/java.io=ALL-UNNAMED -Djava.library.path="$PWD/app/build/host-photorec" \
 -cp "$tmp/classes:$json_test_jar" org.diemobile.PhotoRecPdfTest "$tmp/oracle"
python3 - "$tmp/oracle" <<'PY'
from pathlib import Path
import json,hashlib,re,sys
root=Path(sys.argv[1]); expected=(root/'expected.pdf').read_bytes(); actual=(root/'recovered.pdf').read_bytes()
assert actual==expected
match=re.search(rb'startxref\n([0-9]+)\n%%EOF\n$',actual); assert match
xref=int(match[1]); assert actual[xref:].startswith(b'xref\n0 5\n')
for n,entry in enumerate(actual[xref:].splitlines()[3:7],1):
    offset=int(entry[:10]); assert actual[offset:].startswith(f'{n} 0 obj\n'.encode())
report=json.loads((root/'native-pdf-report.json').read_bytes())
lock=json.loads(Path('carving-engine/source-lock.json').read_bytes())
assert report['adapter']==lock['adapter'] and report['sourceRevision']==lock['revision']
for key in ('maximumInputReadBufferBytes','fileCheckCallbackScope','maximumCandidateFileReadBytes','fileCheckReadBufferBytes'):
    assert report[key]==lock[key]
assert hashlib.sha256(Path('vendor/PhotoRec/src/file_pdf.c').read_bytes()).hexdigest()==lock['contractFileSha256']['src/file_pdf.c']
row=next(x for x in report['rows'] if isinstance(x,dict) and x['name']=='pdf' and x['physicalOffset']==37)
assert row['reportedSizeBytes']==len(actual) and row['fileCheckExecuted'] and row['fileCheckFailureReason'] is None
print('Independent Python reconstructed exact PDF bytes, xref/object locations, native boundary and SHA-256',hashlib.sha256(actual).hexdigest())
PY
