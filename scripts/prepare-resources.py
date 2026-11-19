#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Prepare checked source atomically; reject edits instead of overwriting them."""
from hashlib import sha256
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile

root = Path(__file__).resolve().parent.parent
source_jar = Path(sys.argv[1])
source_digest = '5f8079eb2d2535b4d8bdc4782022287b52a40dce852fc942fca2adc12f78ccdc'
if source_jar.stat().st_size>1024*1024 or sha256(source_jar.read_bytes()).hexdigest() != source_digest:
    raise ValueError('Google binary-resources source checksum mismatch')
patch = root / 'patches/android-resource-engine.patch'
target = root / 'vendor/android-resource-engine'
if target.is_symlink():
    raise ValueError('Prepared resource target is a symbolic link; refusing to replace it')
identity = {'engine':'Google binary-resources','version':'32.5.1','sourceJarSha256':source_digest,
            'patchSha256':sha256(patch.read_bytes()).hexdigest()}
if (target / 'revision.json').is_file():
    previous = json.loads((target / 'revision.json').read_text())
    expected = previous['files']
    actual = {}
    for path in target.rglob('*'):
        if path.is_symlink():
            raise ValueError('Prepared resource source contains a new symbolic link; refusing to replace it')
        if path==target / 'revision.json':
            continue
        if path.is_file():
            relative=path.relative_to(target).as_posix()
            if relative not in expected or path.stat().st_size>256*1024:
                raise ValueError('Prepared resource source contains a new/oversized file; refusing to replace it')
            actual[relative]=sha256(path.read_bytes()).hexdigest()
    if expected != actual:
        raise ValueError('Prepared resource engine has local source edits; preserve/move it before updating')
    if all(previous.get(key) == value for key,value in identity.items()):
        print('Prepared Google resource source/patch identity and all managed payloads verified.')
        sys.exit(0)
elif target.exists() and (not target.is_dir() or any(target.iterdir())):
    raise ValueError('Unrecognized existing resource-engine directory; refusing to replace it')
# Gradle creates declared output directories before starting an Exec task.
# Only a truly empty directory can be adopted without an inventory; unknown
# files, symlinks and any edits to a managed source tree still fail above.
target.parent.mkdir(parents=True,exist_ok=True)
temporary = Path(tempfile.mkdtemp(prefix='resource-engine-stage-',dir=target.parent))
backup = None
try:
    java_count = 0
    expanded = 0
    with ZipFile(source_jar) as source:
        names = source.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate published source archive member')
        for entry in source.infolist():
            name = entry.filename
            if name == 'NOTICE':
                destination = temporary / 'NOTICE'
            elif name.startswith('com/google/devrel/gmscore/tools/apk/arsc/') and name.endswith('.java'):
                if '..' in Path(name).parts or name.startswith('/'):
                    raise ValueError('Unsafe source path')
                destination = temporary / 'src' / name
                java_count += 1
            else:
                continue
            expanded += entry.file_size
            if expanded > 2*1024*1024 or entry.file_size > 256*1024:
                raise ValueError('Published source exceeds its extraction budget')
            destination.parent.mkdir(parents=True,exist_ok=True)
            destination.write_bytes(source.read(entry))
    if java_count != 27 or not (temporary / 'NOTICE').is_file():
        raise ValueError('Published source/license set is incomplete')
    subprocess.run(['patch','--batch','--forward','-p1','-d',str(temporary / 'src'),'-i',str(patch)],check=True)
    identity['files'] = {path.relative_to(temporary).as_posix():sha256(path.read_bytes()).hexdigest()
                         for path in sorted(temporary.rglob('*')) if path.is_file()}
    (temporary / 'revision.json').write_text(json.dumps(identity,indent=2)+'\n')
    if target.exists():
        backup = target.with_name('android-resource-engine-previous')
        if backup.exists():
            raise ValueError('A previous resource engine backup needs recovery before updating')
        target.replace(backup)
    try:
        temporary.replace(target)
    except BaseException:
        if backup is not None:
            backup.replace(target)
        raise
    if backup is not None:
        shutil.rmtree(backup)
    print('Prepared 27 Google resource parser sources, exact NOTICE and documented compatibility patch.')
finally:
    if temporary.exists():
        shutil.rmtree(temporary)
