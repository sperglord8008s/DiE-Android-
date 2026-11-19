#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify the published API/behavior reference; never extracts or executes it."""
import hashlib
import json
import re
from pathlib import Path
import sys
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / "signing-engine/source-lock.json").read_text())
source = Path(sys.argv[1])
if hashlib.sha256(source.read_bytes()).hexdigest() != lock["sourcesSha256"]:
    raise SystemExit("APK signing source archive SHA-256 mismatch")
with ZipFile(source) as archive:
    for name, expected in lock["auditedPublicAndInputContracts"].items():
        data = archive.read(name)
        if hashlib.sha256(data).hexdigest() != expected or b"Licensed under the Apache License, Version 2.0" not in data:
            raise SystemExit("Reviewed apksig source contract/header mismatch: " + name)
report = (root / "app/src/main/java/org/diemobile/ApkSigningReport.java").read_text()
for constant, key in [("VERSION", "version"), ("BINARY_SHA256", "binarySha256"), ("SOURCES_SHA256", "sourcesSha256")]:
    if not re.search(r"\b" + constant + r'\s*=\s*"' + re.escape(lock[key]) + r'"\s*;', report):
        raise SystemExit("APK signing report provenance differs from source lock: " + constant)
print("Pinned AOSP signing public/input contracts, source headers and report provenance verified.")
