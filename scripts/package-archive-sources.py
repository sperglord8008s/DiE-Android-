#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Package unchanged upstream archives and the complete descriptor adapter/build fixtures."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile
root=Path(__file__).resolve().parents[1]
subprocess.run(['bash',str(root/'scripts/fetch-archive.sh')],cwd=root,check=True)
subprocess.run(['python3',str(root/'scripts/archive-notices.py'),'--check'],cwd=root,check=True)
lock=json.loads((root/'archive-engine/source-lock.json').read_text())
cache=Path(os.environ.get('ARCHIVE_DEPS_DIR',str(root/'.ci-cache/archive')))
files={}
for path in (root/'archive-engine').rglob('*'):
    if path.is_file():files[path.relative_to(root).as_posix()]=path
for name in ['LICENSE','scripts/fetch-archive.sh','scripts/prepare-archive.py','scripts/build-archive-libraries.sh',
             'scripts/build-archive.sh','scripts/archive-notices.py','scripts/package-archive-sources.py',
             'scripts/test-archive-engine.sh','scripts/fetch-verified.sh','scripts/test-dependencies.sh',
             'tests/ArchiveEngineTest.java','tests/generate-archive-fixtures.py','tests/archive_header_position_oracle.c',
             'app/src/main/java/org/diemobile/ArchiveEngine.java']:
    files[name]=root/name
for item in lock['components']:files['upstream/'+item['archive']]=cache/item['archive']
output=root/'app/build/archive-sources/archive-component-sources.zip'
output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as target:
    for name,path in sorted(files.items()):
        info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
        target.writestr(info,path.read_bytes())
with zipfile.ZipFile(output) as check:
    assert check.namelist()==sorted(files)
    for name,path in files.items():assert check.read(name)==path.read_bytes(),name
sha=hashlib.sha256(output.read_bytes()).hexdigest()
output.with_suffix('.zip.sha256').write_text(sha+'  '+output.name+'\n')
print('Original archive/codec and complete adapter sources verified:',output,sha)
