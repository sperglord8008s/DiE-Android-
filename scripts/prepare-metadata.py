#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Extract only pinned engine source paths; patch application remains explicit."""
from pathlib import Path
import shutil
import hashlib
import sys
import tarfile
import zipfile

cache = Path(sys.argv[1])
metadata_jar = cache / 'metadata-extractor-2.22.0-sources.jar'
if metadata_jar.stat().st_size > 16 * 1024 * 1024 or hashlib.sha256(metadata_jar.read_bytes()).hexdigest() != 'db4027ec3925959ecd26933ec1fbf4e48e01ad2b338dded702e1a6e62226cccc':
    raise ValueError('metadata-extractor source JAR checksum mismatch')
expected = {}
for line in Path('scripts/xmp-source-files.sha256').read_text().splitlines():
    digest, name = line.split('  ', 1)
    if name in expected:
        raise ValueError('Duplicate source manifest path')
    expected[name] = digest
payloads = {}
expanded = 0
with tarfile.open(cache / 'aosp-xmp.tar.gz') as source:
    for index, member in enumerate(source):
        expanded += member.size
        if index >= 1024 or expanded > 16 * 1024 * 1024:
            raise ValueError('Source archive exceeds entry/expanded-byte budget')
        selected = member.name == 'NOTICE' or member.name.startswith('XMPCore/src/') and member.name.endswith('.java')
        if not selected:
            continue
        if not member.isfile() or member.name not in expected or member.name in payloads:
            raise ValueError('Unexpected/duplicate/non-file compiled source or license path')
        data = source.extractfile(member).read()
        if hashlib.sha256(data).hexdigest() != expected[member.name]:
            raise ValueError('Source payload checksum mismatch: ' + member.name)
        payloads[member.name] = data
if set(payloads) != set(expected):
    raise ValueError('Source archive is missing pinned source/license payloads')
print('AOSP XMP: verified all %d source/license payload checksums' % len(payloads))
target = Path('vendor/metadata-engine')
if target.exists():
    shutil.rmtree(target)
target.mkdir(parents=True)
with zipfile.ZipFile(cache / 'metadata-extractor-2.22.0-sources.jar') as source:
    for name in source.namelist():
        parts = Path(name).parts
        if name.startswith('com/drew/') and '/tools/' not in name and name.endswith('.java') and '..' not in parts:
            output = target / 'metadata' / name
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(source.read(name))
(target / 'XMP-NOTICE.txt').write_bytes(payloads['NOTICE'])
for name, data in payloads.items():
    if name == 'NOTICE':
        continue
    relative = Path(name).relative_to('XMPCore/src')
    if '..' in relative.parts:
        raise ValueError('Unexpected source path')
    output = target / 'xmp' / relative
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
