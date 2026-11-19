#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Acquire only the hash-pinned official ARM64 libmpv runtime, never its app/JNI."""
from hashlib import sha256
import json
import os
from pathlib import Path
import tempfile
from urllib.request import urlopen
from zipfile import ZipFile

root = Path(__file__).resolve().parent.parent
lock = json.loads((root / 'media-engine/runtime-lock.json').read_text())
cache = Path(os.environ.get('MPV_DEPS_DIR', root / 'vendor/mpv-runtime-cache'))
cache.mkdir(parents=True, exist_ok=True)
archive = cache / 'mpv-android-arm64-2026-09-17.apk'

def digest(path):
    with path.open('rb') as stream:
        result = sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
        return result.hexdigest()

if archive.exists() and digest(archive) != lock['sha256']:
    raise SystemExit('Cached MPV runtime checksum differs; preserve/remove the corrupt file before retrying.')
if not archive.exists():
    if os.environ.get('CARGO_NET_OFFLINE') == 'true' or os.environ.get('DIE_OFFLINE') == 'true':
        raise SystemExit('Offline build needs the pinned MPV runtime in MPV_DEPS_DIR.')
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temporary:
        temporary_path = Path(temporary.name)
        try:
            with urlopen(lock['url'], timeout=60) as source:
                total = 0
                for block in iter(lambda: source.read(1024 * 1024), b''):
                    total += len(block)
                    if total > 40 * 1024 * 1024:
                        raise ValueError('MPV archive exceeds its acquisition budget')
                    temporary.write(block)
            temporary.close()
            if digest(temporary_path) != lock['sha256']:
                raise ValueError('Official MPV archive checksum mismatch')
            temporary_path.replace(archive)
        finally:
            temporary_path.unlink(missing_ok=True)

output = root / 'app/build/generated-mpv-jni' / lock['abi']
output.mkdir(parents=True, exist_ok=True)
with ZipFile(archive) as source:
    for name, expected in lock['libraries'].items():
        member = 'lib/' + lock['abi'] + '/' + name
        if source.namelist().count(member) != 1:
            raise ValueError('Duplicate/missing MPV runtime member: ' + member)
        data = source.read(member)
        if sha256(data).hexdigest() != expected:
            raise ValueError('MPV library checksum mismatch: ' + name)
        if data[:6] != b'\x7fELF\x02\x01' or int.from_bytes(data[18:20], 'little') != 183:
            raise ValueError('MPV library is not little-endian AArch64 ELF64: ' + name)
        target = output / name
        if not target.exists() or target.read_bytes() != data:
            target.write_bytes(data)
for name, expected in lock['headers'].items():
    if digest(root / 'media-engine' / name) != expected:
        raise ValueError('Pinned libmpv API header changed: ' + name)
print('Verified official MPV ARM64 archive, nine native runtime payloads and pinned API headers.')
