#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Shared Android/packaging version contract; no clock or CI-run-number dependency."""
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def identity(root=ROOT, variant='debug'):
    values = {}
    for line in (root / 'version.properties').read_text(encoding='ascii').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            if key.strip() in values:
                raise ValueError('Duplicate version property')
            values[key.strip()] = value.strip()
    base = values['version.name']
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-(?:pre-alpha|alpha|beta|rc)\.[1-9][0-9]*|[0-9]+\.[0-9]+\.[0-9]+', base):
        raise ValueError('Expected a numbered release or prerelease version')
    if not re.fullmatch(r'[1-9][0-9]*', values['version.code']):
        raise ValueError('Version code must be a positive decimal integer')
    code = int(values['version.code'])
    if code > 2_100_000_000:
        raise ValueError('Version code exceeds the Android distribution limit')
    product, artifact = values['product.name'], values['artifact.name']
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9 ]{0,63}', product) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9-]{0,63}', artifact):
        raise ValueError('Invalid product/artifact name')
    try:
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, stderr=subprocess.DEVNULL, text=True).strip()
        if not re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', revision):
            raise ValueError('Invalid Git source revision')
        dirty = bool(subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=normal'], cwd=root, text=True))
    except subprocess.CalledProcessError:
        revision, dirty = 'unknown', None
    if variant not in ('debug', 'release'):
        raise ValueError('Unknown Android variant')
    name = base
    if variant == 'debug':
        name += '-dev.' + ('g' + revision[:12] if revision != 'unknown' else 'unknown')
        if dirty:
            name += '.dirty'
    return {'baseVersionName': base, 'versionName': name, 'versionCode': code, 'productName': product, 'artifactName': artifact,
            'sourceRevision': revision, 'sourceDirty': dirty, 'variant': variant,
            'apkName': f'{artifact}-{name}-arm64-v8a-{variant}.apk'}


def apk_path(root=ROOT, variant='debug'):
    directory = root / 'app/build/outputs/apk' / variant
    metadata = json.loads((directory / 'output-metadata.json').read_text())
    entries = metadata['elements']
    if len(entries) != 1:
        raise ValueError('Expected one ARM64 APK output')
    name = entries[0]['outputFile']
    if not isinstance(name, str) or Path(name).name != name or not name.endswith('.apk'):
        raise ValueError('Unsafe APK output name')
    return directory / name


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--variant', choices=('debug', 'release'), default='debug')
    parser.add_argument('--apk-path', action='store_true', help='Resolve the actual single output from AGP metadata')
    args = parser.parse_args()
    print(apk_path(variant=args.variant) if args.apk_path else json.dumps(identity(variant=args.variant), sort_keys=True))
