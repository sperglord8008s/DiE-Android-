#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Check retained declaration references/SDK IDs; optionally verify retrieved upstream files."""
import hashlib
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / "package-engine/source-lock.json").read_text())
sources = {name: (root / "app/src/main/java/org/diemobile" / (name + ".java")).read_text()
           for name in ("PackageManifest", "PackageSetGraph")}
for reference in lock["references"]:
    code = sources[reference["adapter"]]
    if not re.search(r'REFERENCE_SHA\s*=\s*"' + reference["sha256"] + '"', code):
        raise SystemExit("Package reference provenance differs from reviewed lock")
    if len(sys.argv) == 2:
        source = Path(sys.argv[1]) / reference["cachedFile"]
        if hashlib.sha256(source.read_bytes()).hexdigest() != reference["sha256"]:
            raise SystemExit("Retrieved AOSP reference bytes differ: " + str(source))
for field, identity in lock["compiledAttributeIds"].items():
    match = re.search(r'\bfield\(\s*"' + field + r'"\s*,([^;]*?)\);', sources["PackageManifest"], re.S)
    if not match or identity.lower() + "l" not in match.group(1).lower():
        raise SystemExit("Compiled attribute identity differs from SDK 35 contract: " + field)
print("Package reference identities and reviewed SDK attribute IDs agree with code.")
if len(sys.argv) == 2:
    print("Retrieved original AOSP declaration/dependency source bytes also verified.")
