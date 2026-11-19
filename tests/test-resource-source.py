#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify the original publisher notice and refusal to overwrite source edits."""
from hashlib import sha256
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile

root = Path(__file__).resolve().parent.parent
source = Path(sys.argv[1]) / 'binary-resources-32.5.1-sources.jar'
patch_digest = sha256((root / 'patches/android-resource-engine.patch').read_bytes()).hexdigest()
assert patch_digest in (root / 'app/src/main/java/org/diemobile/AndroidResourceProbe.java').read_text(), 'Report source patch identity is stale'
assert 'android-resources-source-patch-sha256=' + patch_digest in (root / 'app/src/main/assets/analysis-engines.properties').read_text(), 'Bundled source patch identity is stale'
with ZipFile(source) as archive:
    notice = archive.read('NOTICE')
    assert sha256(notice).hexdigest() == 'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30'
    assert (root / 'app/src/main/assets/notices/android-resources-32.5.1.txt').read_bytes().endswith(notice), 'Publisher NOTICE bytes changed'
    java = [name for name in archive.namelist() if name.endswith('.java')]
    assert len(java) == 27, 'Published source inventory changed'

with tempfile.TemporaryDirectory(prefix='die-resource-source-') as temporary:
    sandbox = Path(temporary)
    for relative in ('scripts/prepare-resources.py', 'patches/android-resource-engine.patch'):
        destination = sandbox / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / relative, destination)
    command = [sys.executable, str(sandbox / 'scripts/prepare-resources.py'), str(source.resolve())]
    def run(ok):
        result = subprocess.run(command, text=True, capture_output=True)
        assert (result.returncode == 0) == ok, result.stdout + result.stderr
    target = sandbox / 'vendor/android-resource-engine'
    target.mkdir(parents=True)
    unmanaged = target / 'contributor.txt'
    unmanaged.write_text('unmanaged contributor source')
    run(False)
    assert unmanaged.read_text() == 'unmanaged contributor source'
    unmanaged.unlink()
    # Reproduce Gradle's precreated empty outputs.dir on a cold checkout.
    run(True)
    run(True)
    edited = target / 'src/com/google/devrel/gmscore/tools/apk/arsc/Chunk.java'
    original = edited.read_bytes()
    edited.write_bytes(original + b'\n// local contributor edit\n')
    run(False)
    assert edited.read_bytes().endswith(b'// local contributor edit\n'), 'Local source edit overwritten'
    edited.write_bytes(original)
    added = target / 'contributor/revision.json'
    added.parent.mkdir()
    added.write_text('local contributor file\n')
    run(False)
    assert added.read_text() == 'local contributor file\n', 'New contributor file overwritten'
    added.unlink()
    link = target / 'unmanaged-link'
    link.symlink_to(edited)
    run(False)
    link.unlink()
    run(True)
print('Resource publisher notice, source identity/idempotence and source-edit/new-file/symlink preservation passed.')
