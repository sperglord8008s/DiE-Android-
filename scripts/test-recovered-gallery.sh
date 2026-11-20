#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
# Run test-binwalk.sh and test-photorec.sh first; this checks their real combined outputs.
for library in app/build/host-binwalk/libdiebinwalk.so app/build/host-photorec/libdiephotorec.so; do
  if [[ ! -f "$library" ]]; then
    echo 'Build/test both native engines before running recovered-gallery integration.' >&2
    exit 1
  fi
done
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" \
  app/src/main/java/org/diemobile/{Inspector,EvidenceRows,EvidenceJson,InputSnapshot,InvestigationBackend,FileRange,RangeAnalysis,PreparedExport,PhotoRecEngine,PhotoRecReport,BinwalkEngine,BinaryRunner,CarvingBackend,PhotoRecFileChecks,PhotoRecBackend,BinwalkBackend,ContentMap,RecoveredFiles,OwnedContent,GalleryDiscovery,GallerizeSession,ArchiveLaunch,NativeArchiveStore,ArchiveRunner,ArchiveEngine}.java \
  tests/fixtures/metadata/MetadataFixtureFactory.java tests/RecoveredGalleryTest.java
java -Xmx192m --add-opens=java.base/java.io=ALL-UNNAMED \
  -Djava.library.path="$PWD/app/build/host-binwalk:$PWD/app/build/host-photorec" \
  -cp "$tmp/classes:$json_test_jar" org.diemobile.RecoveredGalleryTest "$tmp/oracle"
python3 - "$tmp/oracle" <<'PY'
from pathlib import Path
import json,hashlib,sys,zipfile
root=Path(sys.argv[1]); oracle=json.loads((root/'oracle.json').read_bytes())
report=json.loads((root/'gallery-report.json').read_bytes())
assert report['rawNativeRowCount']==sum(len(report[key]['rows']) for key in ('nativeEvidence','binwalkNativeEvidence'))
with zipfile.ZipFile(root/'recovered-gallery.zip') as z:
    assert set(z.namelist()) == set(oracle)|{'_recovery.json'}
    manifest=json.loads(z.read('_recovery.json'))
    for item in manifest['files']:
        data=z.read(item['file'])
        assert data==(root/f"expected-{oracle[item['file']]}").read_bytes()
        assert hashlib.sha256(data).hexdigest()==item['sha256']
        for discovery in item['discoveries']:
            for witness in discovery['nativeFindings']:
                key='nativeEvidence' if witness['engine']=='photorec' else 'binwalkNativeEvidence'
                row=report[key]['rows'][witness['rowIndex']]
                assert witness['physicalOffset']==row['physicalOffset']
                assert witness['sizeBytes']==row['reportedSizeBytes']
    jpeg=next(x for x in manifest['files'] if x['file'].endswith('.jpg'))
    witnesses=jpeg['discoveries'][0]['nativeFindings']
    assert any(x['engine']=='binwalk' and x['supportsDisplayedExtent'] for x in witnesses)
    assert any(x['engine']=='photorec' and x['supportsDisplayedExtent'] for x in witnesses)
    gzip=next(x for x in manifest['files'] if x['file'].endswith('.gz'))
    witnesses=gzip['discoveries'][0]['nativeFindings']
    assert any(x['engine']=='binwalk' and x['supportsDisplayedExtent'] for x in witnesses)
    assert any(x['engine']=='photorec' and x['sizeBytes'] is None and not x['supportsDisplayedExtent'] for x in witnesses)
print('Independent Python checked every combined-gallery exported byte and its original native boundary witnesses.')
PY
