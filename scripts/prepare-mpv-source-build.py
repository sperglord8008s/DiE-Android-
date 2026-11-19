#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Populate the original mpv-android build recipes from verified source archives.

This prepares a fresh directory; it neither downloads an SDK nor runs a build.
NDK r30 and the upstream build prerequisites are required for a source rebuild.
"""
import argparse
from hashlib import sha256
import json
import os
from pathlib import Path
import tarfile

parser = argparse.ArgumentParser()
parser.add_argument('destination', type=Path, help='a new/empty source-build directory')
parser.add_argument('--lock', type=Path, default=Path(__file__).resolve().parent.parent / 'media-engine/source-lock.json')
parser.add_argument('--cache', type=Path, default=Path(os.environ.get('MPV_SOURCE_DIR', Path(__file__).resolve().parent.parent / 'vendor/mpv-source-cache')))
args = parser.parse_args()
if args.destination.exists() and any(args.destination.iterdir()):
    raise SystemExit('Refusing to overwrite a nonempty source-build directory.')
lock = json.loads(args.lock.read_text())
for item in lock['sources']:
    source = args.cache / item['file']
    if sha256(source.read_bytes()).hexdigest() != item['sha256']:
        raise ValueError('Source checksum mismatch: ' + item['name'])
args.destination.mkdir(parents=True, exist_ok=True)
targets = {}
for item in lock['sources']:
    name = item['name']
    if name == 'mpv-android':
        target = args.destination / 'mpv-android'
    elif 'parent' in item:
        target = targets[item['parent']] / item['destination']
    else:
        target = args.destination / 'mpv-android/buildscripts/deps' / name
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(args.cache / item['file']) as source:
        members = []
        for member in source.getmembers():
            parts = Path(member.name).parts
            if len(parts) <= 1:
                continue
            member.name = Path(*parts[1:]).as_posix()
            members.append(member)
        source.extractall(target, members=members, filter='data')
    targets[name] = target
    print(name, 'prepared')
print('Read mpv-android/buildscripts/README.md and the integration README before rebuilding.')
