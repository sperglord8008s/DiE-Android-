#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Extract only pinned engine source paths; patch application remains explicit."""
from pathlib import Path
import shutil
import sys
import tarfile
import zipfile

cache = Path(sys.argv[1])
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
with tarfile.open(cache / 'aosp-xmp.tar.gz') as source:
    (target / 'XMP-NOTICE.txt').write_bytes(source.extractfile('NOTICE').read())
    for member in source.getmembers():
        if member.isfile() and member.name.startswith('XMPCore/src/') and member.name.endswith('.java'):
            relative = Path(member.name).relative_to('XMPCore/src')
            if '..' in relative.parts:
                raise ValueError('Unexpected source path')
            output = target / 'xmp' / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(source.extractfile(member).read())
