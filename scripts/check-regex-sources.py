#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Retain source and binary-distribution notices for the complete RE2/J source."""
from hashlib import sha256
from pathlib import Path
import json

root = Path(__file__).resolve().parent.parent
lock = json.loads((root / 'regex-engine/source-lock.json').read_text())
source = root / 'regex-engine/third_party/re2j'
actual = {path.relative_to(source).as_posix(): sha256(path.read_bytes()).hexdigest()
          for path in source.rglob('*') if path.is_file()}
assert actual == lock['files'], 'Unmodified RE2/J source inventory/bytes changed'
notice = (root / 'app/src/main/assets/notices/re2j-1.8.txt').read_bytes()
assert (source / 'LICENSE').read_bytes() in notice, 'RE2/J full license payload missing'
classes = [path for path in source.rglob('*.java')]
assert len(classes) == 19, 'RE2/J source set incomplete'
for path in classes:
    value = path.read_bytes()
    assert value[:value.index(b'*/') + 2] in notice, 'RE2/J original source notice missing: ' + str(path)
print('All 19 original RE2/J sources, published source identity and full license/header notices verified.')
