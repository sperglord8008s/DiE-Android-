#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Reuse the pinned canonical compiler; retain all active rules and detail locales."""
from hashlib import sha256
from pathlib import Path, PurePosixPath
import argparse
import json
import os
import subprocess
import sys
import tempfile
from zipfile import ZipFile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('source', type=Path)
parser.add_argument('output', type=Path)
parser.add_argument('--fixtures', type=Path)
args = parser.parse_args()
revision = '4189fc0de0a0d67f0e729a5b9699a52e9ff3ffcf'
source = args.source.resolve()
actual = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
if actual != revision:
    raise ValueError('Canonical LibChecker compiler/source revision differs from its pin')
dirty = bool(subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain', '--untracked-files=all', '--',
                                     'libraries', 'icons', 'tools/rules.py', 'tools/android_vectors.py', 'tools/vector_import.py']).strip())
with tempfile.TemporaryDirectory(prefix='die-libchecker-v5-') as temporary:
    build = Path(temporary)
    try:
        subprocess.run([sys.executable, str(source / 'tools/rules.py'), 'build', '--output', str(build), '--source-revision', revision], check=True, capture_output=True)
    except subprocess.CalledProcessError as error:
        sys.stderr.buffer.write(error.stdout + error.stderr)
        raise SystemExit(error.returncode)
    manifest = json.loads((build / 'manifest.json').read_text())
    artifact = manifest['artifacts']['portable']
    relative = PurePosixPath(artifact['path'])
    if relative.is_absolute() or '..' in relative.parts:
        raise ValueError('Unsafe upstream artifact path')
    archive_path = build / relative
    payload = archive_path.read_bytes()
    if len(payload) != artifact['size'] or sha256(payload).hexdigest() != artifact['sha256']:
        raise ValueError('Canonical portable artifact size/SHA-256 mismatch')
    with ZipFile(archive_path) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise ValueError('Duplicate upstream portable artifact names')
        metadata = json.loads(archive.read('metadata.json'))
        if any(metadata[key] != manifest[key] for key in metadata):
            raise ValueError('Canonical artifact metadata differs from its manifest')
        core = json.loads(archive.read('core.json'))
        if core['schemaVersion'] != 5 or len(core['rules']) != metadata['ruleCount'] or metadata['sourceRevision'] != revision:
            raise ValueError('Canonical core schema/count/source mismatch')
        details = {}
        for rule in core['rules']:
            path = rule['detailPath']
            if path is None:
                continue
            safe = PurePosixPath(path)
            if safe.is_absolute() or '..' in safe.parts or not path.startswith('details/'):
                raise ValueError('Unsafe rule detail path')
            value = json.loads(archive.read(path))
            if value['uuid'] != rule['uuid']:
                raise ValueError('Rule/detail UUID differs')
            details[str(rule['id'])] = value
        catalog = {'schema': 'org.diemobile.libchecker-catalog', 'schemaVersion': 1,
                   'packaging': 'local-source-build', 'sourceTreeDirty': dirty, 'metadata': metadata,
                   'portableArtifactSha256': artifact['sha256'],
                   'rules': core['rules'], 'details': details,
                   'scope': 'Every active canonical matcher and complete effective per-matcher locale payload. Orphan descriptions do not become rules; icon IDs are retained without rendering SVG.'}
        encoded = (json.dumps(catalog, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')
        if len(encoded) > 8 * 1024 * 1024:
            raise ValueError('Compiled canonical catalog exceeds 8 MiB')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(prefix='libchecker-stage-', dir=args.output.parent, delete=False) as stream:
            staged = Path(stream.name)
            stream.write(encoded)
        try:
            os.replace(staged, args.output)
        finally:
            staged.unlink(missing_ok=True)
        digest = sha256(encoded).hexdigest()
        args.output.with_suffix(args.output.suffix + '.sha256').write_text(digest + '\n')
        if args.fixtures:
            args.fixtures.parent.mkdir(parents=True, exist_ok=True)
            args.fixtures.write_bytes(archive.read('matching-fixtures.json'))
        regex = sum(rule['isRegexRule'] for rule in core['rules'])
        print(f'Compiled every canonical LibChecker matcher: {len(core["rules"])} rules, {regex} regex, {len(details)} complete locale payloads; catalog SHA-256 {digest}.')
