#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Retain full upstream license files and leading source attribution notices."""
import argparse
from hashlib import sha256
import json
import os
from pathlib import Path, PurePosixPath
import re
import tarfile

parser = argparse.ArgumentParser()
parser.add_argument('--check', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
cache = Path(os.environ.get('MPV_SOURCE_DIR', root / 'vendor/mpv-source-cache'))
lock = json.loads((root / 'media-engine/source-lock.json').read_text())
parts = [
    'MPV Android runtime source and license notices\n\n'
    'Official mpv-android 2026-09-17 ARM64 libraries are copied without modification.\n'
    'Complete source/archive/library identities: media-engine/{source,runtime}-lock.json.\n'
    'Component source archives, exact submodule revisions and original build recipes are\n'
    'retained in the separate MPV component-source artifact, prepared by the build workflow.\n'
    'The app probe/worker is GPL-3.0-only; terms: DiE-Android-LICENSE.txt.\n'
    'FFmpeg was built with --enable-gpl --enable-version3; this is not an LGPL-only runtime.\n'
    'Below are complete upstream license files and leading source license/attribution\n'
    'comments. The source bundle retains the original files and all other notices.\n\n'
]
def decoded(data):
    try:
        return data.decode('utf-8')
    except UnicodeDecodeError:
        return data.decode('iso-8859-1')

def source_header(data, name):
    text = decoded(data)
    comments = []
    position = 0
    # C/C++ headers can have several adjacent license comments before code.
    while True:
        match = re.match(r'\s*(/\*.*?\*/|//[^\n]*(?:\n\s*//[^\n]*)*)',text[position:],re.S)
        if not match:
            break
        comments.append(match.group(1))
        position += match.end()
    if not comments and name.suffix.lower() in ('.s', '.asm', '.py', '.sh'):
        for line in text.splitlines():
            if line.startswith((';', '#')) and not line.startswith(('#include', '#define', '#if', '#pragma')):
                comments.append(line)
            elif line.strip():
                break
    result = '\n'.join(comments)
    return result if re.search(r'copyright|licen[cs]e|permission|redistribution|SPDX',result,re.I) else ''

source_count = 0
for item in lock['sources']:
    path = cache / item['file']
    if sha256(path.read_bytes()).hexdigest() != item['sha256']:
        raise ValueError('Source archive checksum differs: ' + item['name'])
    parts.append('\n\nComponent: ' + item['name'] + '\nRevision: ' + item['revision'] + '\nSource: ' + item['url'] + '\n\n')
    found = 0
    with tarfile.open(path) as source:
        for member in sorted(source.getmembers(), key=lambda value: value.name):
            if not member.isfile():
                continue
            name = PurePosixPath(*PurePosixPath(member.name).parts[1:])
            lower = name.name.lower()
            is_license = lower.startswith(('license', 'licence', 'copying', 'copyright', 'notice')) or lower in ('ftl.txt', 'gplv2.txt', 'gplv3.txt')
            content = None
            if is_license:
                if member.size > 4*1024*1024:
                    raise ValueError('Unexpected oversized source notice: ' + member.name)
                content = decoded(source.extractfile(member).read())
                found += 1
            elif name.suffix.lower() in ('.c', '.h', '.cpp', '.cc', '.m', '.mm', '.s', '.asm', '.py', '.sh'):
                content = source_header(source.extractfile(member).read(65536),name)
            if content:
                parts.append('File: ' + name.as_posix() + '\n\n' + content + '\n\n')
                source_count += 1
        if item['name'] == 'lua':
            member = next(member for member in source.getmembers() if member.name.endswith('/doc/readme.html'))
            parts.append('Lua official README (license and attribution included):\n\n' + decoded(source.extractfile(member).read()) + '\n')
            found += 1
        if item['name'] == 'mpv':
            for header in ('client.h', 'stream_cb.h'):
                member = next(member for member in source.getmembers() if member.name.endswith('/include/mpv/' + header))
                if source.extractfile(member).read() != (root / 'media-engine/include/mpv' / header).read_bytes():
                    raise ValueError('libmpv API header differs from its pinned engine source: ' + header)
    if not found:
        raise ValueError('No complete license payload found for ' + item['name'])

for item in json.loads((root / 'media-engine/licenses/llvm-license-lock.json').read_text()):
    path = root / 'media-engine/licenses' / item['file']
    data = path.read_bytes()
    if sha256(data).hexdigest() != item['sha256']:
        raise ValueError('NDK libc++ runtime notice changed: ' + item['name'])
    parts.append('\nNDK r30 LLVM runtime component: ' + item['name'] + '\nSource revision: ' + item['sourceRevision'] + '\nSource: ' + item['url'] + '\n\n' + decoded(data) + '\n')

content = ''.join(parts).encode('utf-8')
output = root / 'app/src/main/assets/notices/mpv-runtime.txt'
if args.check:
    if not output.exists() or output.read_bytes() != content:
        raise ValueError('MPV source/license payload differs; review and regenerate notices')
else:
    output.write_bytes(content)
print('Verified' if args.check else 'Wrote',len(content),'MPV notice bytes across',source_count,'source/license entries.')
