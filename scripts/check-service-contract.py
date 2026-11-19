#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify the exact unmodified AOSP output-contract reference and bundled attribution."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / 'system-engine/binder-service-contract.json').read_text())
source = root / 'app/src/main/assets/references/android-15-service.cpp'
assert hashlib.sha256(source.read_bytes()).hexdigest() == lock['sha256'], 'AOSP service reference changed'
adapter = (root / 'app/src/main/java/org/diemobile/BinderRegistry.java').read_text()
notice = (root / 'app/src/main/assets/notices/android-service-contract.txt').read_text()
for value in [lock['revision'], lock['sha256']]:
    assert value in adapter and value in notice, 'Service contract provenance mismatch'
assert 'Copyright 2013 The Android Open Source Project' in source.read_text()
assert 'Version 2.0, January 2004' in notice and 'END OF TERMS AND CONDITIONS' in notice
print('Pinned unmodified Android service-list reference, parser identity and Apache-2.0 notice verified.')
