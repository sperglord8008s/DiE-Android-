#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Deterministic copyright/source notices from the verified upstream archive."""
import hashlib
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
upstream = root / 'vendor/PhotoRec'
lock = json.loads((root / 'carving-engine/source-lock.json').read_text())
if (upstream / 'source-identity.txt').read_text().strip() != lock['archiveSha256']:
    raise SystemExit('Prepare pinned PhotoRec sources before verifying notices')
notice = ('PhotoRec / TestDisk ' + lock['version'] + '\n'
          'Copyright Christophe GRENIER and the upstream contributors.\n'
          'Unmodified format registration, header/data and PDF file-check sources; GPL-2.0-or-later.\n'
          'DiE Android descriptor adapter: GPL-3.0-only, carving-engine/ in the app source.\n'
          'Upstream: https://www.cgsecurity.org/ and https://github.com/cgsecurity/testdisk\n'
          'Source revision: ' + lock['revision'] + '\nArchive: ' + lock['url'] + '\n'
          'Archive SHA-256: ' + lock['archiveSha256'] + '\n'
          'Reproduce: bash scripts/build-photorec.sh android\n'
          'Adapter 4 enables only the upstream PDF file-check through bounded read-only relative stdio.\n'
          'Optional libjpeg/zlib dependencies, auxiliary signatures, other file checks, rename '
          'callbacks and filesystem/fragment recovery are not enabled.\n\n'
          + (upstream / 'COPYING').read_text())
headers = ['PhotoRec 7.2 upstream source headers (unmodified source archive pinned above).\n']
for path in sorted((upstream / 'src').iterdir()):
    if path.suffix not in ('.c', '.h'):
        continue
    text = path.read_text(errors='strict')
    # Preserve every initial comment, including adjacent notices and credits.
    match = re.match(r'\s*((?:(?:/\*.*?\*/|//[^\n]*)(?:\s*))*)', text, re.S)
    if match and match.group(1).strip():
        headers += ['\n=== ' + path.relative_to(upstream).as_posix() + ' ===\n', match.group(1)]
outputs = {
    root / 'app/src/main/assets/notices/photorec-7.2.txt': notice,
    root / 'app/src/main/assets/notices/photorec-source-headers.txt': ''.join(headers),
}
for path, value in outputs.items():
    if '--write' in sys.argv:
        path.write_text(value)
    elif not path.exists() or path.read_text() != value:
        raise SystemExit('PhotoRec notice differs from actual pinned sources: ' + str(path))
print('PhotoRec source license/copyright notices verified')
