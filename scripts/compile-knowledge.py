"""Extract English metadata from the pinned legacy exact-match rule paths.
SPDX-License-Identifier: GPL-3.0-only
"""
import json, pathlib, sys
root, output = map(pathlib.Path, sys.argv[1:])
rules = {}
for kind in ("native-libs", "activities-libs", "services-libs", "receivers-libs", "providers-libs"):
    folder = root / kind
    for path in sorted(folder.rglob("*.json")):
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
            en = next((x["data"] for x in raw.get("data", []) if x.get("locale") == "en"), None)
            if not isinstance(en, dict):
                continue
            key = str(path.relative_to(folder))[:-5]
            if kind != "native-libs":
                key = key.replace("/", ".")
            # Exact matches only. Do not accidentally interpret regex filenames.
            rules[kind + ":" + key] = en
        except (ValueError, KeyError, TypeError, AttributeError):
            continue
if not rules:
    raise SystemExit("No LibChecker rules extracted; upstream format changed")
output.write_text(json.dumps(rules, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print(f"Bundled {len(rules)} English LibChecker exact-match rules")
