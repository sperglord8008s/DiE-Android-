#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify complete pinned library license texts and original source-header notices."""
import json
from pathlib import Path
import re
import sys
root=Path(__file__).resolve().parents[1]
lock=json.loads((root/'archive-engine/source-lock.json').read_text())
upstream=root/'vendor/Archive'
if json.loads((upstream/'source-identity.json').read_text()) != lock:
    raise SystemExit('Prepare original archive/codec sources before verifying notices')
license_files={'libarchive':['COPYING'],'bzip2':['LICENSE'],'xz':['COPYING','COPYING.0BSD'],
               'lz4':['lib/LICENSE'],'zstd':['LICENSE']}
header_roots={'libarchive':['libarchive'],'bzip2':['.'],'xz':['src/liblzma','src/common'],
              'lz4':['lib'],'zstd':['lib']}
notice=['DiE Android archive engine / codec source identities and licenses\n',
        'Application descriptor adapter: GPL-3.0-only, archive-engine/ in the app source.\n',
        'Source-build scripts preserve original library bytes; no command line tools are shipped.\n',
        'Reproduce: ARCHIVE_DEPS_DIR=<cache> bash scripts/build-archive.sh android\n',
        'libarchive uses its BSD/public-domain licenses; the triple-licensed BLAKE2 code uses CC0.\n',
        'XZ liblzma uses 0BSD, LZ4 uses BSD-2-Clause, Zstandard uses its BSD option.\n\n']
headers=['Original source copyright/license comment headers. Some headers belong to uncompiled optional library code; inclusion does not claim that capability.\n']
for component in lock['components']:
    name=component['name'];base=upstream/name
    notice += ['\n=== '+name+' '+component['version']+' ===\n',
               'Source: '+component['url']+'\nArchive SHA-256: '+component['archiveSha256']+'\n']
    for path in license_files[name]:
        notice += ['\n--- '+name+'/'+path+' ---\n',(base/path).read_text()]
    files=set()
    for folder in header_roots[name]:
        files.update(p for p in (base/folder).rglob('*') if p.suffix in ['.c','.h'] and p.is_file())
    for path in sorted(files):
        text=path.read_text(errors='strict')
        match=re.match(r'\s*((?:(?:/\*.*?\*/|//[^\n]*)(?:\s*))*)',text,re.S)
        if match and match.group(1).strip():
            headers += ['\n=== '+name+'/'+path.relative_to(base).as_posix()+' ===\n',match.group(1)]
outputs={root/'archive-engine/NOTICE.txt':''.join(notice),root/'archive-engine/source-headers.txt':''.join(headers)}
for path,text in outputs.items():
    if '--write' in sys.argv:path.write_text(text)
    elif not path.exists() or path.read_text()!=text:raise SystemExit('Archive notice differs from pinned original sources: '+str(path))
print('Archive/codec original license texts and source headers verified')
