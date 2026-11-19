#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Prepare hash-pinned unmodified library sources; no inspected archive is handled here."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
lock = json.loads((ROOT / 'archive-engine/source-lock.json').read_text())
cache = Path(sys.argv[1])
target = ROOT / 'vendor/Archive'
target.parent.mkdir(exist_ok=True)

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def inventory(path):
    return {item.relative_to(path).as_posix(): digest(item)
            for item in path.rglob('*') if item.is_file() and item.name != 'source-identity.json'}

with tempfile.TemporaryDirectory(prefix='archive-source-', dir=target.parent) as temporary:
    stage = Path(temporary)
    for item in lock['components']:
        original = cache / item['archive']
        if digest(original) != item['archiveSha256']:
            raise SystemExit('Archive source pin differs: ' + item['name'])
        output = stage / item['name']
        output.mkdir()
        excluded = {}
        with tarfile.open(original) as source:
            members = source.getmembers()
            if len(members) > 5000 or sum(m.size for m in members) > 64 * 1024 * 1024:
                raise SystemExit('Archive source inventory exceeds reviewed limits')
            seen = set()
            for member in members:
                path = Path(member.name)
                if path.is_absolute() or '..' in path.parts or path.parts[0] != item['prefix']:
                    raise SystemExit('Unexpected source archive path')
                relative = Path(*path.parts[1:])
                if relative.as_posix() in seen:
                    raise SystemExit('Duplicate source archive path')
                seen.add(relative.as_posix())
                if member.issym() and item['excludedCliSymlinks'].get(relative.as_posix()) == member.linkname:
                    excluded[relative.as_posix()] = member.linkname
                    continue
                if not (member.isfile() or member.isdir()):
                    raise SystemExit('Unsupported source archive entry type')
                destination = output / relative
                if member.isdir(): destination.mkdir(parents=True, exist_ok=True)
                else:
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    with source.extractfile(member) as data, destination.open('wb') as file:
                        shutil.copyfileobj(data, file)
            if excluded != item['excludedCliSymlinks']:
                raise SystemExit('Reviewed excluded CLI symlinks differ')
        for name, expected in item['contractFileSha256'].items():
            if digest(output/name) != expected:
                raise SystemExit('Archive source API/license contract differs: '+item['name']+'/'+name)
        print(item['name'],item['version'],'original source archive verified')
    (stage / 'source-identity.json').write_text(json.dumps(lock,sort_keys=True)+'\n')
    if target.exists() and inventory(stage) == inventory(target):
        identity=stage/'source-identity.json'
        if (target/'source-identity.json').read_bytes() != identity.read_bytes():
            shutil.copyfile(identity,target/'source-identity.json')
        print('All prepared archive/codec source bytes and API/license contracts verified')
    else:
        if target.exists(): shutil.rmtree(target)
        shutil.copytree(stage,target)
        print('Prepared pinned archive and codec sources')
