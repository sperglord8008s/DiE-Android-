#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
apk="${1:-$(python3 scripts/build-version.py --apk-path)}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" ]]; then echo 'Set ANDROID_HOME to the pinned Android SDK installation.' >&2; exit 1; fi
tools="$sdk/build-tools/35.0.0"
readelf="$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

"$tools/zipalign" -c -P 16 4 "$apk"
if ! "$tools/apksigner" verify --verbose --print-certs "$apk" > "$tmp/signature.txt" 2>&1; then
  cat "$tmp/signature.txt"; exit 1
fi
"$tools/aapt2" dump permissions "$apk" > "$tmp/permissions.txt"
"$tools/aapt2" dump badging "$apk" > "$tmp/badging.txt"
"$tools/aapt2" dump xmltree "$apk" --file AndroidManifest.xml > "$tmp/manifest-tree.txt"
python3 scripts/build-version.py > "$tmp/build-identity.json"
python3 - "$tmp/permissions.txt" "$tmp/badging.txt" "$tmp/build-identity.json" "$tmp/version.txt" <<'PY'
import json
import re
import sys
from pathlib import Path
permissions, badging = (Path(path).read_text() for path in sys.argv[1:3])
identity = json.loads(Path(sys.argv[3]).read_text())
if 'android.permission.INTERNET' in permissions:
    raise SystemExit('Unexpected INTERNET permission in the packaged application.')
for expected in ("package: name='org.diemobile'", "minSdkVersion:'26'", "targetSdkVersion:'35'"):
    if expected not in badging:
        raise SystemExit('Packaged identity/API field missing: ' + expected + '\n' + badging)
package_line = next((line for line in badging.splitlines() if line.startswith('package: ')), '')
for key in ('versionCode', 'versionName'):
    expected = str(identity[key])
    actual = re.search(r"\b" + key + r"='([^']*)'", package_line)
    if actual is None or actual.group(1) != expected:
        raise SystemExit('Packaged application ' + key + ' differs from the current version/source identity. Rebuild after source changes.')
if "application-label:'" + identity['productName'] + "'" not in badging:
    raise SystemExit('Packaged application label differs from the product identity.')
Path(sys.argv[4]).write_text('Application version: ' + identity['versionName'] + ' (version code ' + str(identity['versionCode']) + ')\n')
PY
python3 - "$tmp/manifest-tree.txt" <<'PY'
from pathlib import Path
import sys
lines=Path(sys.argv[1]).read_text().splitlines();blocks=[]
for i,line in enumerate(lines):
    if line.strip().startswith('E: service '):
        indent=len(line)-len(line.lstrip());block=[]
        for child in lines[i+1:]:
            if child.strip().startswith('E:') and len(child)-len(child.lstrip())<=indent:
                break
            block.append(child)
        if any('org.diemobile.BinaryWorkerService' in child for child in block):
            blocks.append(block)
assert len(blocks)==1,'Expected one packaged BinaryWorkerService'
block=blocks[0]
assert any(':isolatedProcess(' in line and line.endswith('=true') for line in block),'Binary worker lost UID isolation'
assert any(':exported(' in line and line.endswith('=false') for line in block),'Binary worker became externally accessible'
assert any(':process(' in line and '":binary_worker"' in line for line in block),'Binary worker lost its separate process'
print('Packaged binary worker is private and requests a separate isolated UID/process.')
for activity in ('GallerizeActivity', 'ContentPreviewActivity'):
    found=[]
    for i,line in enumerate(lines):
        if line.strip().startswith('E: activity '):
            indent=len(line)-len(line.lstrip());children=[]
            for child in lines[i+1:]:
                if child.strip().startswith('E:') and len(child)-len(child.lstrip())<=indent: break
                children.append(child)
            if any('org.diemobile.'+activity+'"' in child for child in children): found.append(children)
    assert len(found)==1 and any(':exported(' in child and child.endswith('=false') for child in found[0]), activity+' must remain private'
print('Packaged content gallery and preview activities are private.')

PY

python3 - "$apk" "$readelf" "$tmp" <<'PY'
from pathlib import Path
import subprocess
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    names=[name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')]
    assert {'lib/arm64-v8a/libdiemobile.so','lib/arm64-v8a/libdiebinwalk.so','lib/arm64-v8a/libdiempv.so','lib/arm64-v8a/libdiehash.so','lib/arm64-v8a/libdiephotorec.so'} <= set(names), 'Required native engine library missing'
    for name in names:
        assert name.startswith('lib/arm64-v8a/'), 'Unexpected native ABI: '+name
        data=archive.read(name);header=data[:20]
        assert header[:6]==b'\x7fELF\x02\x01' and int.from_bytes(header[18:20],'little')==183, 'Expected little-endian AArch64 ELF64: '+name
        path=Path(sys.argv[3])/Path(name).name;path.write_bytes(data)
        segments=subprocess.check_output([sys.argv[2],'-lW',str(path)],text=True);loads=[]
        for line in segments.splitlines():
            fields=line.split()
            if fields and fields[0]=='LOAD':
                offset,address,alignment=int(fields[1],16),int(fields[2],16),int(fields[-1],16)
                assert alignment>=16384 and alignment&(alignment-1)==0 and offset%16384==address%16384, 'Invalid 16 KiB LOAD alignment: '+name
                loads.append(alignment)
        assert loads,'No ELF LOAD segments: '+name
        print('Static ARM64 / 16 KiB ELF verification:',name)
    import hashlib,json
    lock=json.loads(Path('media-engine/runtime-lock.json').read_text())
    for name,expected in lock['libraries'].items():
        assert hashlib.sha256(archive.read('lib/arm64-v8a/'+name)).hexdigest()==expected, 'Packaged official MPV runtime changed: '+name
    assert 'lib/arm64-v8a/libplayer.so' not in names,'Unneeded upstream player JNI was packaged'
    assert archive.read('mpv-properties.json')==Path('app/src/main/resources/mpv-properties.json').read_bytes(),'Offline MPV property reference missing/changed'
    print('Nine packaged official MPV runtime libraries and offline property references match their pinned inputs.')
    service_lock=json.loads(Path('system-engine/binder-service-contract.json').read_text())
    assert hashlib.sha256(archive.read('assets/references/android-15-service.cpp')).hexdigest()==service_lock['sha256'], 'Packaged Binder contract reference changed'
    assert archive.read('assets/notices/android-service-contract.txt')==Path('app/src/main/assets/notices/android-service-contract.txt').read_bytes(), 'Binder reference license notice missing/changed'
    catalog=archive.read('assets/knowledge/libchecker-v5.json')
    assert hashlib.sha256(catalog).hexdigest()==archive.read('assets/knowledge/libchecker-v5.json.sha256').decode('ascii').strip(),'Canonical SDK catalog checksum differs from its complete payload'
PY

unzip -p "$apk" assets/engine-revisions.properties | cmp - generated-assets/engine-revisions.properties
unzip -p "$apk" assets/knowledge/revisions.txt | cmp - generated-assets/knowledge/revisions.txt
unzip -p "$apk" assets/notices/apksig-8.9.2.txt | cmp - app/src/main/assets/notices/apksig-8.9.2.txt
unzip -p "$apk" assets/notices/DiE-Android-LICENSE.txt | cmp - LICENSE
unzip -p "$apk" assets/analysis-engines.properties | cmp - app/src/main/assets/analysis-engines.properties
unzip -p "$apk" assets/notices/smali-3.0.10.txt | cmp - app/src/main/assets/notices/smali-3.0.10.txt
unzip -p "$apk" assets/notices/dex-dependencies.txt | cmp - app/src/main/assets/notices/dex-dependencies.txt
unzip -p "$apk" assets/notices/metadata-extractor-2.22.0.txt | cmp - app/src/main/assets/notices/metadata-extractor-2.22.0.txt
unzip -p "$apk" assets/notices/xmp-aosp-BSD.txt | cmp - app/src/main/assets/notices/xmp-aosp-BSD.txt
unzip -p "$apk" assets/notices/metadata-source-headers.txt | cmp - app/src/main/assets/notices/metadata-source-headers.txt
unzip -p "$apk" assets/notices/binwalk-3.1.1.txt | cmp - app/src/main/assets/notices/binwalk-3.1.1.txt
unzip -p "$apk" assets/notices/photorec-7.2.txt | cmp - app/src/main/assets/notices/photorec-7.2.txt
unzip -p "$apk" assets/notices/photorec-source-headers.txt | cmp - app/src/main/assets/notices/photorec-source-headers.txt
unzip -p "$apk" assets/notices/binary-dependencies.txt | cmp - app/src/main/assets/notices/binary-dependencies.txt
unzip -p "$apk" assets/notices/rust-stdlib-1.90.0.html | cmp - app/src/main/assets/notices/rust-stdlib-1.90.0.html
unzip -p "$apk" assets/notices/mpv-runtime.txt | cmp - app/src/main/assets/notices/mpv-runtime.txt
unzip -p "$apk" assets/notices/android-resources-32.5.1.txt | cmp - app/src/main/assets/notices/android-resources-32.5.1.txt
unzip -p "$apk" assets/notices/rhash-md4-1.4.5.txt | cmp - app/src/main/assets/notices/rhash-md4-1.4.5.txt
unzip -p "$apk" assets/notices/re2j-1.8.txt | cmp - app/src/main/assets/notices/re2j-1.8.txt
unzip -p "$apk" assets/notices/unicode-15.0.0.txt | cmp - app/src/main/assets/notices/unicode-15.0.0.txt
unzip -p "$apk" assets/knowledge/libchecker-v5.json | cmp - generated-assets/knowledge/libchecker-v5.json
unzip -p "$apk" assets/knowledge/libchecker-v5.json.sha256 | cmp - generated-assets/knowledge/libchecker-v5.json.sha256
unzip -p "$apk" assets/db/_init > "$tmp/db-init"
test -s "$tmp/db-init"

source_revision=$(git rev-parse HEAD)
source_changes=$(git status --porcelain | wc -l | tr -d ' ')
manifest="$(dirname "$apk")/build-manifest.txt"
{
  echo "Source revision: $source_revision"
  echo "Uncommitted source entries at verification: $source_changes"
  echo 'Configuration: debug, org.diemobile, API 26 minimum / API 35 target, ARM64'
  cat "$tmp/version.txt"
  echo 'Pinned toolchain: JDK 17, Rust 1.90.0, Gradle 8.11.1, NDK 28.2.13676358, CMake 3.22.1, build-tools 35.0.0'
  sha256sum binary-engine/Cargo.lock
  sha256sum media-engine/runtime-lock.json media-engine/source-lock.json
  sha256sum patches/android-resource-engine.patch
  sha256sum hash-engine/source-lock.json
  sha256sum regex-engine/source-lock.json
  sha256sum regex-engine/tracker-unicode-lock.json
  sha256sum signing-engine/source-lock.json package-engine/source-lock.json
  sha256sum carving-engine/source-lock.json
  echo "Canonical LibChecker catalog SHA-256: $(cat generated-assets/knowledge/libchecker-v5.json.sha256)"
  cat generated-assets/engine-revisions.properties generated-assets/knowledge/revisions.txt
  cat app/src/main/assets/analysis-engines.properties
  echo 'Verification: APK signature, ZIP alignment, ELF 16 KiB alignment, application identity/API levels, no INTERNET permission, bundled revision/license assets'
  echo 'No device-runtime verification. Debug signing keys and local build paths may differ between builds; bit-identical signed APKs are not claimed.'
  cat "$tmp/signature.txt"
} > "$manifest"
(
  cd "$(dirname "$apk")"
  sha256sum "$(basename "$apk")" > "$(basename "$apk").sha256"
)
echo 'APK signature, offline packaging, identity and notice checks passed; checksum and build manifest written beside APK.'
