#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Retain the resolved Android dependency license texts; --check is the CI mode."""
from pathlib import Path
import argparse
import json

parser = argparse.ArgumentParser()
parser.add_argument('metadata', type=Path)
parser.add_argument('rust_sysroot', type=Path)
parser.add_argument('--check', action='store_true')
args = parser.parse_args()
repository = Path(__file__).resolve().parent.parent
metadata = json.loads(args.metadata.read_text())
parts = ['Binwalk Android dependency license notices\n\nResolved versions and crate/Git checksums: binary-engine/Cargo.lock.\nThe Binwalk core is unmodified MIT source at 26713972e3f9f52dc37d4a421c4554b0bd9e82ef.\nThe JNI/scanning adapter is GPL-3.0-only; complete terms: DiE-Android-LICENSE.txt.\n\n']
for package in sorted(metadata['packages'], key=lambda item: (item['name'], item['version'])):
    if package['name'] in ('die-binwalk-core', 'die-binwalk-bridge'):
        continue
    root = Path(package['manifest_path']).parent
    texts = []
    for path in sorted(root.rglob('*')):
        if not path.is_file():
            continue
        lower = path.name.lower()
        in_licenses = any(part.lower() in ('licenses', 'license') for part in path.relative_to(root).parts[:-1])
        if not (lower.startswith(('license', 'copying', 'notice', 'copyright')) or in_licenses):
            continue
        if path.stat().st_size > 2 * 1024 * 1024:
            raise ValueError('Unexpected oversized license text: ' + str(path))
        raw = path.read_bytes()
        try:
            value = raw.decode('utf-8')
        except UnicodeDecodeError:
            value = raw.decode('iso-8859-1')
        texts.append('File: ' + path.relative_to(root).as_posix() + '\n\n' + value + '\n')
    if not texts:
        if package['name'] == 'jni-sys-macros' and package['version'] == '0.4.1':
            # The crate archive omits its workspace's license files. Retain the
            # exact upstream files at its .cargo_vcs_info.json commit instead.
            texts = ['Shared workspace license at jni-rs/jni-sys commit 64d77b7a5f119d7b55b4e2c169a4668067ff59e6\n\n' + path.read_text() for path in sorted((repository / 'binary-engine/licenses').glob('jni-sys-macros-0.4.1-LICENSE-*'))]
        if not texts:
            raise ValueError('No retained license text for ' + package['name'])
    declared = package.get('license') or 'Manifest omits SPDX declaration; the pinned LICENSE payload below supplies the terms'
    parts.append('\n\nPackage: %s %s\nDeclared license: %s\nSource: %s\n\n%s' % (package['name'], package['version'], declared, package.get('source'), '\n'.join(texts)))
notices = repository / 'app/src/main/assets/notices'
outputs = {
    'binwalk-3.1.1.txt': (repository / 'vendor/Binwalk/LICENSE').read_bytes(),
    'binary-dependencies.txt': ''.join(parts).encode('utf-8'),
    'rust-stdlib-1.90.0.html': (args.rust_sysroot / 'share/doc/rust/COPYRIGHT-library.html').read_bytes(),
}
for name, content in outputs.items():
    path = notices / name
    if args.check:
        if not path.is_file() or path.read_bytes() != content:
            raise ValueError('Bundled license notice needs review/regeneration: ' + name)
    else:
        path.write_bytes(content)
    print(name, len(content), 'bytes verified' if args.check else 'bytes written')
