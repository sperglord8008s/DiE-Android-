#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Generate the offline property reference from the exact pinned MPV manual."""
import argparse
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import tarfile

parser = argparse.ArgumentParser()
parser.add_argument('--check', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
lock = json.loads((root / 'media-engine/source-lock.json').read_text())
item = next(value for value in lock['sources'] if value['name'] == 'mpv')
cache = Path(os.environ.get('MPV_SOURCE_DIR', root / 'vendor/mpv-source-cache'))
archive = cache / item['file']
if sha256(archive.read_bytes()).hexdigest() != item['sha256']:
    raise ValueError('Pinned MPV manual source checksum differs')
with tarfile.open(archive) as source:
    member = next(value for value in source.getmembers() if value.name.endswith('/DOCS/man/input.rst'))
    content = source.extractfile(member).read()
text = content.decode('utf-8')
start = text.index('Property list\n')
end = text.index('Inconsistencies between options and properties')
section = text[start:end]
headings = list(re.finditer(r'^``[^\n]+\n',section,re.M))
properties = {}
for i, heading in enumerate(headings):
    body = section[heading.end():headings[i+1].start() if i+1 < len(headings) else len(section)].strip()
    for key in re.findall(r'``([^`]+)``',heading.group()):
        if '/' not in key:
            properties[key] = body
result = {'sourceRevision':item['revision'], 'sourcePath':'DOCS/man/input.rst',
          'sourceSha256':sha256(content).hexdigest(), 'license':'GPL-2.0-or-later', 'properties':properties}
encoded = (json.dumps(result,ensure_ascii=True,indent=2)+'\n').encode('utf-8')
output = root / 'app/src/main/resources/mpv-properties.json'
if args.check:
    if not output.exists() or output.read_bytes() != encoded:
        raise ValueError('Bundled MPV property reference differs from the pinned source')
else:
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_bytes(encoded)
print('Verified' if args.check else 'Wrote',len(properties),'source-pinned MPV property reference blocks.')
