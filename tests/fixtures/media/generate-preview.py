#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Generate owned, tiny solid-green MPEG-4 and PCM fixtures; never use an inspected sample."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import wave

output = Path(sys.argv[1])
output.mkdir(parents=True, exist_ok=True)
video = output / 'green-64x48.mp4'
subprocess.run([
    'ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'color=c=0x008000:s=64x48:r=5',
    '-t', '1', '-an', '-c:v', 'mpeg4', '-q:v', '2', '-pix_fmt', 'yuv420p',
    '-flags', '+bitexact', '-fflags', '+bitexact', '-y', str(video)
], check=True, timeout=30)
assert video.stat().st_size < 65536
with wave.open(str(output / 'pcm-500ms.wav'), 'wb') as audio:
    audio.setnchannels(1)
    audio.setsampwidth(2)
    audio.setframerate(8000)
    audio.writeframes(b'\x00\x00' * 4000)
manifest = {
    'meaning': 'Owned generated fixtures, not inspected applications or third-party media.',
    'video': {'width': 64, 'height': 48, 'durationMs': 1000, 'rgb': '008000', 'codec': 'mpeg4'},
    'audio': {'durationMs': 500, 'sampleRate': 8000, 'channels': 1, 'codec': 'pcm_s16le'},
    'generator': subprocess.check_output(['ffmpeg', '-version'], text=True).splitlines()[0],
    'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.iterdir()) if p.suffix in ['.mp4', '.wav']},
}
(output / 'fixture-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print('Owned 64×48 green video and 500 ms PCM fixtures generated.')
