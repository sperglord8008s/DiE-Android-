#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify and prepare unmodified upstream format sources; never run configure."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
lock = json.loads((ROOT / 'carving-engine/source-lock.json').read_text())
archive = Path(sys.argv[1])
if hashlib.sha256(archive.read_bytes()).hexdigest() != lock['archiveSha256']:
    raise SystemExit('PhotoRec source archive checksum differs from lock')
target = ROOT / 'vendor/PhotoRec'
target.parent.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='photorec-', dir=target.parent) as temporary:
    stage = Path(temporary)
    with tarfile.open(archive) as source:
        members = source.getmembers()
        if len(members) > 2000 or sum(x.size for x in members) > 16 * 1024 * 1024:
            raise SystemExit('PhotoRec source archive exceeds audited bounds')
        prefix = 'testdisk-' + lock['revision']
        for member in members:
            path = Path(member.name)
            if (path.is_absolute() or '..' in path.parts or path.parts[0] != prefix
                    or not (member.isfile() or member.isdir())):
                raise SystemExit('Unexpected PhotoRec archive member')
        source.extractall(stage, members=members, filter='data')
    extracted = stage / prefix
    for name, expected in lock['contractFileSha256'].items():
        if hashlib.sha256((extracted / name).read_bytes()).hexdigest() != expected:
            raise SystemExit('PhotoRec source contract differs: ' + name)
    text = (extracted / 'src/Makefile.am').read_text()
    files = re.search(r'^file_C\s*=\s*(.*?)(?=\n\w)', text, re.M | re.S).group(1)
    files = files.replace('\\', '').split()
    files += 'common.c crc.c ext2_common.c fat_common.c log.c misc.c setdate.c suspend_no.c'.split()
    if len(files) != lock['compiledSourceCount'] or any(not re.fullmatch(r'\w+\.c', x) for x in files):
        raise SystemExit('PhotoRec source inventory differs from reviewed Makefile')
    cmake = 'set(PHOTOREC_UPSTREAM_SOURCES\n' + ''.join(
        '  "${PHOTOREC_ROOT}/src/' + x + '"\n' for x in files) + ')\n'
    (extracted / 'diemobile-sources.cmake').write_text(cmake)
    identity = lock['archiveSha256'] + '\n'
    (extracted / 'source-identity.txt').write_text(identity)
    # Compare all prepared bytes: an already present edited vendor file is never trusted.
    def inventory(folder):
        return {p.relative_to(folder): hashlib.sha256(p.read_bytes()).digest()
                for p in folder.rglob('*') if p.is_file()}
    if target.exists() and inventory(target) == inventory(extracted):
        print('Unmodified PhotoRec 7.2 source inventory verified')
    else:
        if target.exists():
            shutil.rmtree(target)
        extracted.rename(target)
        print('Prepared pinned PhotoRec 7.2 sources')
