#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Check publication refusal and byte-inventory contracts without remote writes."""
import hashlib
import importlib.util
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('publication', ROOT / 'scripts/publish-prerelease.py')
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)


class PublicationContracts(unittest.TestCase):
    def test_published_tag_requires_exact_source_and_never_retargets(self):
        source = '1' * 40
        with mock.patch.object(publication, 'api', return_value={'object': {'type': 'commit', 'sha': source}}):
            publication.verify_tag('repos/owner/repo', 'v0.6.0-pre-alpha.1', source)
        for value in (None, {'object': {'type': 'commit', 'sha': '2' * 40}}):
            with mock.patch.object(publication, 'api', return_value=value) as api:
                with self.assertRaises(ValueError):
                    publication.verify_tag('repos/owner/repo', 'v0.6.0-pre-alpha.1', source)
                self.assertEqual(1, api.call_count)

    def test_release_updates_preserve_and_verify_every_identity_field(self):
        def defaulting_api(path, payload, method):
            # Reproduce the observed omitted-field reset on a metadata update.
            return {'tag_name': payload.get('tag_name', 'untagged-generated'),
                    'target_commitish': payload.get('target_commitish', 'main'),
                    'name': payload.get('name', ''), 'body': payload.get('body', ''),
                    'draft': payload.get('draft', False),
                    'prerelease': payload.get('prerelease', False)}
        with mock.patch.object(publication, 'api', side_effect=defaulting_api):
            for draft in (True, False):
                updated = publication.update_release('repos/owner/repo', {'id': 42},
                    'v0.6.0-pre-alpha.1', '1' * 40, 'GlassDigestor 0.6.0-pre-alpha.1', 'complete notes\n', draft)
                self.assertEqual('v0.6.0-pre-alpha.1', updated['tag_name'])
                self.assertEqual('1' * 40, updated['target_commitish'])
                self.assertEqual('complete notes\n', updated['body'])
        with mock.patch.object(publication, 'api', return_value=defaulting_api('', {}, '')):
            with self.assertRaises(ValueError):
                publication.update_release('repos/owner/repo', {'id': 42},
                    'v0.6.0-pre-alpha.1', '1' * 40, 'GlassDigestor', 'notes', False)

    def test_direct_draft_upload_streams_exact_bytes_without_tag_lookup(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'source.zip'
            payload = bytes(range(256)) * 1025 + b'last bytes'
            path.write_bytes(payload)
            class Response:
                status = 201
                def __enter__(self): return self
                def __exit__(self, *arguments): pass
                def read(self, maximum): return b'{}'
            class Client:
                request = None
                collected = None
                def open(self, request, timeout):
                    self.request = request
                    blocks = list(request.data)
                    assert all(0 < len(block) <= 65536 for block in blocks)
                    self.collected = b''.join(blocks)
                    return Response()
            client = Client()
            with mock.patch.dict('os.environ', {'GH_TOKEN': 'fixture-token'}):
                publication.upload_asset('owner/repo', 42, path, client)
            self.assertEqual(payload, client.collected)
            self.assertEqual('https://uploads.github.com/repos/owner/repo/releases/42/assets?name=source.zip', client.request.full_url)
            self.assertEqual(str(len(payload)), client.request.get_header('Content-length'))
            self.assertEqual('Bearer fixture-token', client.request.get_header('Authorization'))
            self.assertIsNone(publication.NoUploadRedirect().redirect_request(None, None, 302, '', {}, 'https://foreign.invalid'))

    def test_all_five_successful_jobs_are_required(self):
        jobs = [{'name': name, 'status': 'completed', 'conclusion': 'success'} for name in publication.JOBS]
        publication.validate_jobs(jobs)
        for invalid in (jobs[:-1], jobs + [jobs[0]],
                        [dict(item, conclusion='skipped') if index == 0 else item for index, item in enumerate(jobs)],
                        [dict(item, name='unknown') if index == 0 else item for index, item in enumerate(jobs)]):
            with self.assertRaises(ValueError):
                publication.validate_jobs(invalid)

    def test_zip_digest_paths_types_duplicates_and_budget(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / 'artifact.zip'
            def write(entries):
                with zipfile.ZipFile(archive, 'w') as output:
                    for name, value in entries:
                        output.writestr(name, value)
                return 'sha256:' + publication.digest(archive)
            checksum = write([('nested/complete.txt', b'original bytes')])
            publication.extract_artifact(archive, root / 'valid', checksum)
            self.assertEqual((root / 'valid/nested/complete.txt').read_bytes(), b'original bytes')
            with self.assertRaises(ValueError):
                publication.extract_artifact(archive, root / 'wrong-hash', 'sha256:' + '0' * 64)
            with self.assertRaises(ValueError):
                publication.extract_artifact(archive, root / 'valid', checksum)
            for index, entries in enumerate([
                    [('../escape', b'bad')], [('/absolute', b'bad')], [('back\\slash', b'bad')],
                    [('same', b'one'), ('same', b'two')]]):
                checksum = write(entries)
                destination = root / ('invalid-' + str(index))
                with self.assertRaises(ValueError):
                    publication.extract_artifact(archive, destination, checksum)
                self.assertFalse(destination.exists())
            link = zipfile.ZipInfo('link')
            link.create_system = 3
            link.external_attr = (stat.S_IFLNK | 0o777) << 16
            checksum = write([(link, b'outside')])
            with self.assertRaises(ValueError):
                publication.extract_artifact(archive, root / 'link', checksum)
            checksum = write([('complete', b'12345')])
            with self.assertRaises(ValueError):
                publication.extract_artifact(archive, root / 'oversize', checksum, maximum=4)
            self.assertFalse((root / 'oversize').exists())

    def test_complete_checksum_inventory_and_preserved_modified_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data = root / 'source.zip'
            data.write_bytes(b'original source archive')
            pair = root / 'source.zip.sha256'
            pair.write_text(publication.digest(data) + '  source.zip\n')
            sums = root / 'SHA256SUMS'
            sums.write_text(''.join(publication.digest(path) + '  ' + path.name + '\n' for path in (data, pair)))
            publication.verify_checksums(root)
            data.write_bytes(b'locally edited bytes')
            with self.assertRaises(ValueError):
                publication.verify_checksums(root)
            self.assertEqual(data.read_bytes(), b'locally edited bytes')
            sums.write_text('')
            with self.assertRaises(ValueError):
                publication.verify_checksums(root)
            for name in ('../outside', '/absolute', 'has space', 'line\nbreak'):
                with self.assertRaises(ValueError):
                    publication.safe_name(name)


if __name__ == '__main__':
    unittest.main()
