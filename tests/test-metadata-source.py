#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""A regenerated tar/gzip must work; changed/missing/extra source must fail."""
import gzip
import io
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile

repository = Path(__file__).resolve().parent.parent
cache = Path(sys.argv[1]).resolve()
with tarfile.open(cache / 'aosp-xmp.tar.gz') as original:
    payloads = {member.name: original.extractfile(member).read() for member in original if member.isfile() and (member.name == 'NOTICE' or member.name.startswith('XMPCore/src/') and member.name.endswith('.java'))}
with tempfile.TemporaryDirectory() as temporary:
    root = Path(temporary)
    (root / 'scripts').mkdir()
    shutil.copy(repository / 'scripts/prepare-metadata.py', root / 'scripts')
    shutil.copy(repository / 'scripts/xmp-source-files.sha256', root / 'scripts')
    deps = root / 'cache'
    deps.mkdir()
    shutil.copy(cache / 'metadata-extractor-2.22.0-sources.jar', deps)

    def run(entries, expected):
        tar_bytes = io.BytesIO()
        with tarfile.open(fileobj=tar_bytes, mode='w') as output:
            for name, data in entries:
                member = tarfile.TarInfo(name)
                member.size = len(data)
                member.mtime = 123456789
                member.uid = 777
                member.mode = 0o600
                output.addfile(member, io.BytesIO(data))
        with (deps / 'aosp-xmp.tar.gz').open('wb') as raw, gzip.GzipFile(filename='different-wrapper-name.tar', fileobj=raw, mode='wb', mtime=1) as output:
            output.write(tar_bytes.getvalue())
        result = subprocess.run([sys.executable, 'scripts/prepare-metadata.py', str(deps)], cwd=root, capture_output=True, text=True)
        assert (result.returncode == 0) == expected, result.stdout + result.stderr
        if not expected:
            assert (root / 'vendor/metadata-engine/sentinel').read_text() == 'keep valid source', 'Invalid payload destroyed existing prepared source'

    valid = list(payloads.items())
    run(valid, True)
    (root / 'vendor/metadata-engine/sentinel').write_text('keep valid source')
    run([(valid[0][0], valid[0][1] + b'changed')] + valid[1:], False)
    run(valid[1:], False)
    run(valid + [('XMPCore/src/com/adobe/xmp/Injected.java', b'package com.adobe.xmp;')], False)
    run(valid + [valid[0]], False)
print('Metadata source fixtures passed: regenerated headers accepted; changed/missing/extra/duplicate payloads rejected without replacing valid source')
