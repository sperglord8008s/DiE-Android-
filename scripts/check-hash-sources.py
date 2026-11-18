#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify retained unmodified RHash source bytes and matching provenance/notices."""
from hashlib import sha256
from pathlib import Path
import json

root = Path(__file__).resolve().parent.parent
lock = json.loads((root / 'hash-engine/source-lock.json').read_text())
source = root / 'hash-engine/third_party/rhash'
assert {p.name for p in source.iterdir()} == set(lock['files']), 'RHash source inventory differs'
for name, expected in lock['files'].items():
    assert sha256((source / name).read_bytes()).hexdigest() == expected, 'Unmodified RHash source changed: ' + name
notice = (root / 'app/src/main/assets/notices/rhash-md4-1.4.5.txt').read_bytes()
assert (source / 'COPYING').read_bytes() in notice, 'RHash license payload differs'
for name in ('md4.c', 'byte_order.c'):
    value = (source / name).read_bytes()
    assert value[:value.index(b'*/') + 2] in notice, 'RHash source notice omitted: ' + name
for path in ('app/src/main/cpp/hash_bridge.c', 'app/src/main/java/org/diemobile/HashDocument.java'):
    value = (root / path).read_text()
    assert lock['rhashSource'] in value and lock['xxfclibSource'] in value, 'Hash engine provenance is stale: ' + path
print('Unmodified RHash source identities, original license/header notices and report provenance verified.')
