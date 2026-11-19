#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/fetch-verified.sh
cache="${ARCHIVE_DEPS_DIR:-$PWD/.ci-cache/archive}"
while IFS=$'\t' read -r name checksum url; do
  fetch_verified_dependency "$cache/$name" "$checksum" "$url"
done < <(python3 - <<'PY'
import json
from pathlib import Path
for item in json.loads(Path('archive-engine/source-lock.json').read_text())['components']:
    print(item['archive'],item['archiveSha256'],item['url'],sep='\t')
PY
)
python3 scripts/prepare-archive.py "$cache"
