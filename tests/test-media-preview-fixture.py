#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Offline fixture identity/PCM oracles and refusal of edited canonical video bytes."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import wave

root = Path(__file__).resolve().parents[1]
script = root / 'tests/fixtures/media/generate-preview.py'
with tempfile.TemporaryDirectory(prefix='die-media-preview-fixture-') as temporary:
    directory = Path(temporary)
    output = directory / 'offline'
    # Missing PATH proves the default path does not execute an external codec tool.
    environment = dict(os.environ, PATH='')
    subprocess.run([sys.executable, str(script), str(output)], env=environment, check=True)
    manifest = json.loads((output / 'fixture-manifest.json').read_text())
    assert manifest['videoRegeneratedThisBuild'] is False
    assert (output / 'green-64x48.mp4').read_bytes() == script.with_name('green-64x48.mp4').read_bytes()
    for name, digest in manifest['files'].items():
        assert hashlib.sha256((output / name).read_bytes()).hexdigest() == digest
    with wave.open(str(output / 'pcm-500ms.wav'), 'rb') as pcm:
        assert (pcm.getnchannels(), pcm.getsampwidth(), pcm.getframerate(), pcm.getnframes()) == (1, 2, 8000, 4000)
        assert pcm.readframes(4000) == b'\x00\x00' * 4000
    edited = directory / 'edited'
    edited.mkdir()
    shutil.copyfile(script, edited / script.name)
    (edited / 'green-64x48.mp4').write_bytes(b'edited canonical fixture')
    candidate = directory / 'refused'
    refusal = subprocess.run([sys.executable, str(edited / script.name), str(candidate)], env=environment,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    assert refusal.returncode != 0 and b'pinned SHA-256' in refusal.stdout
    assert not candidate.exists(), 'Edited fixture published partial output'
print('Pinned media fixture stages without FFmpeg/PATH, exact manifest and independent PCM bytes pass; edited source refuses before publication.')
