#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
apk="${1:-app/build/outputs/apk/debug/app-debug.apk}"
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
python3 - "$tmp/permissions.txt" "$tmp/badging.txt" <<'PY'
import sys
from pathlib import Path
permissions, badging = (Path(path).read_text() for path in sys.argv[1:])
if 'android.permission.INTERNET' in permissions:
    raise SystemExit('Unexpected INTERNET permission in the packaged application.')
for expected in ("package: name='org.diemobile'", "minSdkVersion:'26'", "targetSdkVersion:'35'"):
    if expected not in badging:
        raise SystemExit('Packaged identity/API field missing: ' + expected + '\n' + badging)
PY

unzip -p "$apk" lib/arm64-v8a/libdiemobile.so > "$tmp/libdiemobile.so"
"$readelf" -lW "$tmp/libdiemobile.so" > "$tmp/segments.txt"
python3 - "$tmp/libdiemobile.so" "$tmp/segments.txt" <<'PY'
import sys
with open(sys.argv[1], 'rb') as stream:
    header = stream.read(20)
assert header[:6] == b'\x7fELF\x02\x01', 'Expected a little-endian ELF64 library'
assert int.from_bytes(header[18:20], 'little') == 183, 'Expected AArch64 machine type'
loads = []
with open(sys.argv[2]) as stream:
    for line in stream:
        fields = line.split()
        if fields and fields[0] == 'LOAD':
            offset, address, alignment = int(fields[1], 16), int(fields[2], 16), int(fields[-1], 16)
            assert alignment >= 16384 and alignment & (alignment - 1) == 0, 'ELF LOAD alignment below 16 KiB'
            assert offset % 16384 == address % 16384, 'ELF segment not congruent at 16 KiB'
            loads.append(alignment)
assert loads, 'No ELF LOAD segments'
print('ARM64 ELF LOAD segments and APK native-library placement satisfy static 16 KiB alignment checks.')
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
unzip -p "$apk" assets/db/_init > "$tmp/db-init"
test -s "$tmp/db-init"

source_revision=$(git rev-parse HEAD)
source_changes=$(git status --porcelain | wc -l | tr -d ' ')
manifest="$(dirname "$apk")/build-manifest.txt"
{
  echo "Source revision: $source_revision"
  echo "Uncommitted source entries at verification: $source_changes"
  echo 'Configuration: debug, org.diemobile, API 26 minimum / API 35 target, ARM64'
  echo 'Pinned toolchain: JDK 17, Gradle 8.11.1, NDK 28.2.13676358, CMake 3.22.1, build-tools 35.0.0'
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
