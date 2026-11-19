#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Exercise real Git/source states, not a mocked version string."""
import importlib.util
import json
import re
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('build_version', ROOT / 'scripts/build-version.py')
version = importlib.util.module_from_spec(spec)
spec.loader.exec_module(version)


class BuildIdentityTest(unittest.TestCase):
    def test_clean_dirty_ignored_and_detached_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'version.properties').write_text((ROOT / 'version.properties').read_text())
            (root / '.gitignore').write_text('build/\n')
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL, text=True).strip()
            git('init', '-q')
            git('add', '.')
            git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture')
            revision = git('rev-parse', 'HEAD')
            clean = version.identity(root)
            self.assertEqual(clean['sourceRevision'], revision)
            self.assertIs(clean['sourceDirty'], False)
            self.assertEqual(clean['versionName'], clean['baseVersionName'] + '-dev.g' + revision[:12])
            self.assertEqual(clean['apkName'], 'GlassDigestor-' + clean['versionName'] + '-arm64-v8a-debug.apk')
            (root / 'build').mkdir()
            (root / 'build/generated.apk').write_bytes(b'ignored build output')
            self.assertEqual(version.identity(root), clean)
            (root / 'untracked-source.java').write_text('new source')
            self.assertTrue(version.identity(root)['versionName'].endswith('.dirty'))
            (root / 'untracked-source.java').unlink()
            with (root / 'version.properties').open('a') as stream:
                stream.write('# changed tracked source\n')
            self.assertIs(version.identity(root)['sourceDirty'], True)
            git('checkout', '--', 'version.properties')
            git('checkout', '--detach', revision)
            self.assertEqual(version.identity(root), clean)
            release = version.identity(root, 'release')
            self.assertEqual(release['versionName'], clean['baseVersionName'])
            self.assertEqual(release['versionCode'], clean['versionCode'])
            self.assertIn('-release.apk', release['apkName'])
            output = root / 'app/build/outputs/apk/debug'
            output.mkdir(parents=True)
            (output / 'output-metadata.json').write_text(json.dumps({'elements': [{'outputFile': clean['apkName']}]}))
            self.assertEqual(version.apk_path(root), output / clean['apkName'])
            (output / 'output-metadata.json').write_text(json.dumps({'elements': [{'outputFile': '../foreign.apk'}]}))
            with self.assertRaises(ValueError):
                version.apk_path(root)

    def test_unidentified_source_and_invalid_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = (ROOT / 'version.properties').read_text()
            (root / 'version.properties').write_text(original)
            unknown = version.identity(root)
            self.assertEqual(unknown['sourceRevision'], 'unknown')
            self.assertIsNone(unknown['sourceDirty'])
            self.assertTrue(unknown['versionName'].endswith('-dev.unknown'))
            for change in [re.sub(r'^version.code=.*$', 'version.code=7.5', original, flags=re.M),
                           re.sub(r'^version.code=.*$', 'version.code=2100000001', original, flags=re.M),
                           re.sub(r'^version.name=.*$', 'version.name=arbitrary', original, flags=re.M),
                           original + '\nversion.code=8\n']:
                (root / 'version.properties').write_text(change)
                with self.assertRaises(ValueError):
                    version.identity(root)


if __name__ == '__main__':
    unittest.main()
