#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Publish a checked CI capture using the repository workflow's ordinary token.

Never rebuild the APK, change its signer, retarget a tag, overwrite an asset or
publish a partial release. A failed upload leaves a resumable draft.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

JOBS = {'build', 'native-integration', 'apk-signatures', 'binary-integration', 'media-integration'}
COMPONENTS = {'photorec-upstream-and-adapter-sources', 'archive-engine-and-codec-sources',
              'mpv-android-component-sources'}
MAX_ARTIFACT_BYTES = 256 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def safe_name(name):
    require(isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,191}', name),
            'Unsafe release asset name')
    return name


def extract_artifact(archive, destination, expected, maximum=MAX_ARTIFACT_BYTES):
    require(re.fullmatch(r'sha256:[0-9a-f]{64}', expected or ''), 'Artifact digest is unavailable')
    require(digest(archive) == expected[7:], 'Downloaded artifact digest differs from GitHub')
    require(not destination.exists(), 'Artifact destination already exists')
    destination.mkdir()
    success = False
    try:
        with zipfile.ZipFile(archive) as source:
            total, names = 0, set()
            for entry in source.infolist():
                name = PurePosixPath(entry.filename)
                require(not name.is_absolute() and '..' not in name.parts
                        and '\\' not in entry.filename and entry.filename not in names,
                        'Unsafe or duplicate artifact path')
                names.add(entry.filename)
                mode = entry.external_attr >> 16
                require(stat.S_IFMT(mode) in (0, stat.S_IFREG, stat.S_IFDIR),
                        'Artifact contains a link or special file')
                total += entry.file_size
                require(total <= maximum, 'Expanded artifact exceeds its byte budget')
                target = destination / entry.filename
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.open(entry) as original, target.open('xb') as output:
                        shutil.copyfileobj(original, output, 65536)
        success = True
    finally:
        if not success:
            shutil.rmtree(destination)


def verify_checksums(directory):
    files = list(directory.iterdir())
    require(files and all(path.is_file() for path in files), 'Expected flat CI artifact files')
    for path in files:
        safe_name(path.name)
    manifest = directory / 'SHA256SUMS'
    if manifest.exists():
        listed = set()
        for line in manifest.read_text(encoding='ascii').splitlines():
            checksum, name = line.split('  ', 1)
            safe_name(name)
            require(name not in listed and re.fullmatch('[0-9a-f]{64}', checksum),
                    'Invalid or duplicate checkpoint checksum')
            listed.add(name)
            require(digest(directory / name) == checksum, 'Checkpoint checksum mismatch: ' + name)
        require(listed == {path.name for path in files if path != manifest},
                'Checkpoint checksum inventory is incomplete')
    for path in files:
        if path.suffix == '.sha256':
            fields = path.read_text(encoding='ascii').split()
            require(len(fields) in (1, 2) and re.fullmatch('[0-9a-f]{64}', fields[0]),
                    'Invalid component checksum')
            original = path.with_name(path.name.removesuffix('.sha256'))
            if len(fields) == 2:
                require(fields[1] == original.name, 'Checksum belongs to another component')
            require(digest(original) == fields[0], 'Component checksum mismatch: ' + original.name)
        if path.suffix == '.zip':
            require(path.with_name(path.name + '.sha256').is_file(), 'Component checksum missing')


def validate_jobs(jobs):
    require(len(jobs) == len(JOBS) and {job['name'] for job in jobs} == JOBS,
            'Expected exactly the five reviewed Android/host jobs')
    require(all(job['status'] == 'completed' and job['conclusion'] == 'success' for job in jobs),
            'Every Android/host job must complete successfully')


def checkpoint(directory, properties, tag):
    require((directory / 'SHA256SUMS').is_file(), 'Original checkpoint checksums missing')
    verify_checksums(directory)
    metadata = json.loads((directory / 'checkpoint.json').read_text(encoding='utf-8'))
    require(metadata.get('schema') == 'org.diemobile.alpha-checkpoint'
            and metadata.get('schemaVersion') == 1, 'Unsupported checkpoint schema')
    revision = metadata['sourceRevision']
    require(re.fullmatch('[0-9a-f]{40}|[0-9a-f]{64}', revision), 'Invalid captured source revision')
    expected_version = properties['version.name'] + '-dev.g' + revision[:12]
    require(tag == 'v' + properties['version.name'] and metadata['versionName'] == expected_version,
            'Tag/version does not match the exact clean captured source')
    require(type(metadata['versionCode']) is int and str(metadata['versionCode']) == properties['version.code'],
            'Android version code differs from the captured source')
    require(metadata['productName'] == properties['product.name']
            and metadata['applicationId'] == 'org.diemobile' and metadata['variant'] == 'debug'
            and metadata['abi'] == 'arm64-v8a' and metadata['minSdk'] == 26
            and metadata['targetSdk'] == 35, 'Unexpected captured application/build identity')
    name = safe_name(metadata['apk'])
    require(name == properties['artifact.name'] + '-' + expected_version + '-arm64-v8a-debug.apk',
            'APK filename differs from the version contract')
    apk = directory / name
    require(apk.stat().st_size == metadata['apkSizeBytes'] and digest(apk) == metadata['apkSha256'],
            'APK differs from captured size/SHA-256')
    manifest = (directory / 'build-manifest.txt').read_text(encoding='utf-8')
    for line in ('Source revision: ' + revision,
                 'Uncommitted source entries at verification: 0',
                 'Application version: ' + expected_version + ' (version code ' + str(metadata['versionCode']) + ')'):
        require(line in manifest.splitlines(), 'Build manifest identity is incomplete or inconsistent')
    return metadata


def gh(arguments, *, output=None, payload=None, optional=False):
    result = subprocess.run(['gh', *arguments], input=None if payload is None else json.dumps(payload).encode(),
                            stdout=subprocess.PIPE if output is None else output,
                            stderr=subprocess.PIPE)
    if result.returncode:
        if optional and b'HTTP 404' in result.stderr:
            return None
        # Retain actionable HTTP/CLI diagnostics while removing signed URLs and
        # token-shaped text from errors. A failed upload stays in the draft.
        detail = result.stderr.decode('utf-8', errors='replace')
        detail = re.sub(r'https?://\S+', '<GitHub endpoint>', detail)
        detail = re.sub(r'\b(?:github_pat_|gh[opsu]_)[A-Za-z0-9_]+', '[redacted]', detail)
        raise RuntimeError('GitHub operation failed: ' + arguments[0] + ' (exit '
                           + str(result.returncode) + '): ' + detail[:2000])
    return result.stdout


def api(path, payload=None, method=None, optional=False):
    arguments = ['api', path]
    if method:
        arguments += ['--method', method]
    if payload is not None:
        arguments += ['--input', '-']
    raw = gh(arguments, payload=payload, optional=optional)
    return None if raw is None else json.loads(raw)


class NoUploadRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        # Authorization belongs only to the fixed GitHub upload endpoint.
        return None


def upload_asset(repository, release_id, path, opener=None):
    """Stream to a known draft ID; do not rediscover a draft through its tag."""
    require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository), 'Invalid repository')
    require(type(release_id) is int and release_id > 0, 'Invalid draft release ID')
    name = safe_name(path.name)
    require(path.stat().st_size <= MAX_ARTIFACT_BYTES, 'Release asset exceeds the upload budget')
    endpoint = ('https://uploads.github.com/repos/' + repository + '/releases/'
                + str(release_id) + '/assets?' + urllib.parse.urlencode({'name': name}))
    client = opener or urllib.request.build_opener(NoUploadRedirect())
    with path.open('rb') as source:
        request = urllib.request.Request(endpoint,
            data=iter(lambda: source.read(65536), b''), method='POST', headers={
                'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                'Content-Type': 'application/octet-stream',
                'Content-Length': str(path.stat().st_size),
                'Accept': 'application/vnd.github+json',
                'X-GitHub-Api-Version': '2022-11-28',
                'User-Agent': 'GlassDigestor-verified-prerelease',
            })
        try:
            with client.open(request, timeout=180) as response:
                require(response.status == 201, 'GitHub did not create the asset: ' + name)
                require(len(response.read(1024 * 1024 + 1)) <= 1024 * 1024,
                        'Upload response exceeded its metadata budget')
        except urllib.error.HTTPError as error:
            raise RuntimeError('GitHub draft asset upload failed: HTTP ' + str(error.code)
                               + ' for ' + name + '; the draft remains unpublished') from None
        except urllib.error.URLError:
            raise RuntimeError('GitHub draft asset upload connection failed for ' + name
                               + '; the draft remains unpublished') from None


def source_text(prefix, revision, path, maximum):
    value = api(prefix + '/contents/' + path + '?ref=' + revision)
    require(value['type'] == 'file' and value['encoding'] == 'base64'
            and value['size'] <= maximum, 'Source file is missing or exceeds its budget')
    data = base64.b64decode(value['content'])
    require(len(data) == value['size'], 'Incomplete source file')
    return data.decode('utf-8')


def verify_remote_assets(prefix, release, assets, temporary):
    remote = api(prefix + '/releases/' + str(release['id']) + '/assets?per_page=100')
    require(len(remote) == len(assets) and {item['name'] for item in remote} == set(assets),
            'Release asset inventory is incomplete or contains unexpected files')
    for item in remote:
        local = assets[item['name']]
        require(item['size'] == local.stat().st_size, 'Uploaded asset size mismatch')
        actual = item.get('digest')
        if actual is None:
            downloaded = temporary / ('verify-' + safe_name(item['name']))
            with downloaded.open('wb') as output:
                gh(['api', prefix + '/releases/assets/' + str(item['id']),
                    '-H', 'Accept: application/octet-stream'], output=output)
            actual = 'sha256:' + digest(downloaded)
            downloaded.unlink()
        require(actual == 'sha256:' + digest(local), 'Uploaded asset digest mismatch')


def publish(run_id, tag, repository, temporary):
    require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository), 'Invalid repository')
    require(re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+-(?:pre-alpha|alpha|beta|rc)\.[1-9][0-9]*', tag),
            'Expected a numbered pre-release tag')
    prefix = 'repos/' + repository
    run = api(prefix + '/actions/runs/' + str(run_id))
    require(run['status'] == 'completed' and run['conclusion'] == 'success'
            and run['path'] == '.github/workflows/android.yml'
            and run['head_repository']['full_name'] == repository,
            'Expected a successful source workflow from this repository')
    validate_jobs(api(prefix + '/actions/runs/' + str(run_id) + '/jobs?filter=latest&per_page=100')['jobs'])
    available = api(prefix + '/actions/runs/' + str(run_id) + '/artifacts?per_page=100')['artifacts']
    saves = [item for item in available if item['name'].startswith('GlassDigestor-')
             and item['name'].endswith('-savepoint') and not item['expired']]
    require(len(saves) == 1, 'Expected one retained GlassDigestor checkpoint')
    required = [saves[0]]
    for name in sorted(COMPONENTS):
        matches = [item for item in available if item['name'] == name and not item['expired']]
        require(len(matches) == 1, 'Corresponding source artifact missing: ' + name)
        required += matches
    directories = []
    for index, item in enumerate(required):
        archive = temporary / (str(index) + '.zip')
        with archive.open('wb') as output:
            gh(['api', prefix + '/actions/artifacts/' + str(item['id']) + '/zip'], output=output)
        directory = temporary / ('component-' + str(index))
        extract_artifact(archive, directory, item.get('digest'))
        verify_checksums(directory)
        directories.append(directory)
        archive.unlink()
    source = json.loads((directories[0] / 'checkpoint.json').read_text())['sourceRevision']
    require(re.fullmatch('[0-9a-f]{40}|[0-9a-f]{64}', source), 'Invalid checkpoint source')
    for revision in (source, run['head_sha']):
        require(re.fullmatch('[0-9a-f]{40}|[0-9a-f]{64}', revision), 'Invalid workflow source')
    source_tree = api(prefix + '/git/commits/' + source)['tree']['sha']
    require(source_tree == api(prefix + '/git/commits/' + run['head_sha'])['tree']['sha'],
            'Captured source tree differs from the successful workflow head')
    properties = dict(line.split('=', 1) for line in source_text(prefix, source, 'version.properties', 4096).splitlines()
                      if line.strip() and not line.startswith('#'))
    metadata = checkpoint(directories[0], properties, tag)
    files = {}
    for directory in directories:
        for path in directory.iterdir():
            require(path.name not in files, 'Duplicate release asset name')
            files[path.name] = path
    proof = temporary / 'SOURCE-AND-CHECKS.md'
    proof.write_text('# Exact release source and checks\n\nSource: `' + source + '`.\n\n'
                     + 'Git tree: `' + source_tree + '`.\n\n'
                     + '[Successful five-job CI run](https://github.com/' + repository + '/actions/runs/' + str(run_id) + ').\n\n'
                     + 'The tag identifies the exact clean source recorded in the original APK/checkpoint. CI may use a generated PR merge commit; its tree was compared with the workflow head. All downloaded artifact digests, checkpoint inventories, APK size/SHA-256 and corresponding-source checksums were verified before upload. Every remote asset is checked before publication.\n\n'
                     + 'Run `sha256sum -c RELEASE-SHA256SUMS` with all listed assets present. Original CI files and component checksums are unchanged. The debug signer is recorded in build-manifest.txt. Actual device verification and full desktop parity remain separate acceptance work.\n', encoding='utf-8')
    files[proof.name] = proof
    sums = temporary / 'RELEASE-SHA256SUMS'
    sums.write_text(''.join(digest(path) + '  ' + name + '\n' for name, path in sorted(files.items())), encoding='ascii')
    files[sums.name] = sums
    notes = source_text(prefix, source, 'docs/releases/' + properties['version.name'] + '.md', 128 * 1024)
    notes = ('All five [CI jobs](https://github.com/' + repository + '/actions/runs/' + str(run_id) + ') passed.\n\n'
             + '**APK:** `' + metadata['apk'] + '`  \n**SHA-256:** `' + metadata['apkSha256']
             + '`  \n**Exact source:** `' + source + '`\n\n' + notes + '\n')
    notes = notes.replace('](../', '](https://github.com/' + repository + '/blob/' + tag + '/docs/')
    release = api(prefix + '/releases/tags/' + tag, optional=True)
    if release is None:
        # GitHub's by-tag endpoint returns published releases; the authenticated
        # collection also contains resumable drafts without a published tag.
        drafts = api(prefix + '/releases?per_page=100')
        matches = [item for item in drafts if item['tag_name'] == tag]
        require(len(matches) <= 1, 'Multiple drafts use this tag; nothing will be overwritten')
        if matches:
            release = matches[0]
        else:
            require(len(drafts) < 100, 'Release listing needs pagination before proving this tag is absent')
    if release is None:
        release = api(prefix + '/releases', {'tag_name': tag, 'target_commitish': source,
                      'name': properties['product.name'] + ' ' + properties['version.name'],
                      'body': notes, 'draft': True, 'prerelease': True}, 'POST')
    require(release['target_commitish'] == source and release['prerelease'],
            'Existing release belongs to another source or release channel')
    if not release['draft']:
        verify_remote_assets(prefix, release, files, temporary)
        print('Published release already contains the exact verified assets:', release['html_url'])
        return
    present = {item['name']: item for item in release['assets']}
    require(set(present) <= set(files), 'Draft contains unrecognized assets; nothing will be removed')
    for name, item in present.items():
        require(item.get('digest') == 'sha256:' + digest(files[name])
                and item['size'] == files[name].stat().st_size,
                'Draft asset differs; overwriting is prohibited')
    api(prefix + '/releases/' + str(release['id']), {'body': notes}, 'PATCH')
    for name, path in files.items():
        if name not in present:
            upload_asset(repository, release['id'], path)
    verify_remote_assets(prefix, release, files, temporary)
    reference = api(prefix + '/git/ref/tags/' + tag, optional=True)
    if reference is not None:
        object_value = reference['object']
        for _ in range(4):
            if object_value['type'] != 'tag':
                break
            object_value = api(prefix + '/git/tags/' + object_value['sha'])['object']
        require(object_value['type'] == 'commit' and object_value['sha'] == source,
                'Existing tag points to another source; retargeting is prohibited')
    else:
        api(prefix + '/git/refs', {'ref': 'refs/tags/' + tag, 'sha': source}, 'POST')
    published = api(prefix + '/releases/' + str(release['id']),
                    {'draft': False, 'prerelease': True, 'make_latest': 'false'}, 'PATCH')
    require(not published['draft'], 'GitHub did not publish the verified draft')
    print('Published exact verified pre-release:', published['html_url'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', type=int, required=True)
    parser.add_argument('--tag', required=True)
    arguments = parser.parse_args()
    require(arguments.run_id > 0, 'Invalid workflow run ID')
    with tempfile.TemporaryDirectory(prefix='glassdigestor-publish-') as directory:
        publish(arguments.run_id, arguments.tag, os.environ['GH_REPO'], Path(directory))
