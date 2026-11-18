#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run actual pinned Exodus methods as a development-only contract reference.

No Exodus dependency, credential, database, APK or sample application is loaded.
The AGPL source remains a reference downloaded into the test cache, outside the
app; no reference code is incorporated into the Android artifact.
"""
import ast
import hashlib
import itertools
import json
import logging
import os
from pathlib import Path
import re
import sys
import unicodedata
import urllib.request
from collections import namedtuple

if unicodedata.unidata_version != '15.0.0':
    raise SystemExit('Tracker contract references require Python 3.12 / Unicode 15.0.0; APK builds use retained frozen data and do not run this reference.')
revision = '8a18f43f16f1727438c5dd8f18a8a31301e1df9a'
source_sha = '4569ea6464d888f9edda362cc392247934db50bb598b35655e7dec99c1d230a1'
cache = Path(os.environ.get('TRACKER_DEPS_DIR', 'app/build/tracker-deps'))
cache.mkdir(parents=True, exist_ok=True)
source = Path(os.environ.get('EXODUS_REFERENCE_SOURCE', cache / 'static_analysis.py'))
if not source.is_file():
    source.write_bytes(urllib.request.urlopen('https://raw.githubusercontent.com/Exodus-Privacy/exodus-core/' + revision + '/exodus_core/analysis/static_analysis.py', timeout=30).read())
data = source.read_bytes()
assert hashlib.sha256(data).hexdigest() == source_sha, 'Exodus reference source differs'
ucd = cache / 'UnicodeData-15.0.0.txt'
if not ucd.is_file():
    ucd.write_bytes(urllib.request.urlopen('https://www.unicode.org/Public/15.0.0/ucd/UnicodeData.txt', timeout=30).read())
assert hashlib.sha256(ucd.read_bytes()).hexdigest() == '806e9aed65037197f1ec85e12be6e8cd870fc5608b4de0fffd990f689f376a73', 'Unicode source differs'

tree = ast.parse(data)
original = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == 'StaticAnalysis')
methods = [node for node in original.body if isinstance(node, ast.FunctionDef) and node.name in ('_compile_signatures', 'detect_trackers_in_list')]
assert len(methods) == 2
# Execute the original AST methods, with only their standard-library dependencies.
reference_class = ast.ClassDef(name='StaticAnalysis', bases=[], keywords=[], body=methods, decorator_list=[],
                             **({'type_params': []} if 'type_params' in ast.ClassDef._fields else {}))
module = ast.fix_missing_locations(ast.Module(body=[reference_class], type_ignores=[]))
namespace = {'re': re, 'itertools': itertools, 'logging': logging}
exec(compile(module, str(source), 'exec'), namespace)
Tracker = namedtuple('Tracker', 'id name code_signature')

patterns = [r'com/example', r'com/(example|other)/', r'example', r'^com/example/[A-Z][a-z]+$',
            r'com.example', r'com\.example', r'com/example/Outer\$Inner', r'org/(?:alpha|beta)/',
            r'(a+)+b', r'(?:)', r'ab', r'^abc$', r'\Aabc\Z', r'com/\w+/\d+',
            r'com/[\w-]+/[\d]+', r'com/\D+/Name', r'com/\W+/Name', r'com/\S+/Name',
            r'com/[\s]+/Name', r'com/[^a-z]+/Name', r'com/[a-z]{2,4}/Name',
            r'com/.*?/Name', r'com/[\b]/Name', r'com/\v/Name', r'com/\a/Name',
            r'com/\\/Name', r'com/\./Name', r'com/[a-z\-]+/Name']
inputs = ['com/example/Name', 'com/other/Item', 'xcom/example/NameSuffix', 'com.example.Name',
          'example', 'com/example/Outer$Inner', 'org/beta/Thing', 'abc', 'xabc', 'aaaaab', 'a'*14,
          'com/汉字/١٢', 'com/é/12', 'com/a_b/12', 'com/-/Name', 'com/😀/Name', 'com/12/Name',
          'com/abc/Name', 'com/a/Name', 'com/abcd/Name', 'com/abcde/Name', 'com/ab-c/Name',
          'com/\x1c/Name', 'com/\u180e/Name', 'com/\u2000/Name', 'com/\b/Name', 'com/\v/Name',
          'com/\a/Name', 'com/\\/Name', 'com/./Name', 'com/𞓐/١٢', 'com/letters/𑽐']
cases = []
for index, pattern in enumerate(patterns):
    # Both one-name cases and a complete list compare first-evidence selection.
    for values in [[value] for value in inputs] + [inputs]:
        instance = namespace['StaticAnalysis']()
        instance.apk_path = 'synthetic-no-apk'
        instance.signatures = [Tracker(str(index), 'Synthetic reference ' + str(index), pattern)]
        instance._compile_signatures()
        ordered = sorted(set(values), key=lambda value: ('L' + value + ';').encode('utf-16-be'))
        hits = instance.detect_trackers_in_list(ordered)
        expected = None
        if hits:
            name = next(value for value in ordered if re.search(pattern, value))
            match = re.search(pattern, name)
            # Java indices are UTF-16 code units; Python indices are code points.
            units = lambda value: len(value.encode('utf-16-le')) // 2
            expected = {'input': name, 'start': units(name[:match.start()]), 'end': units(name[:match.end()])}
        cases.append({'signature': pattern, 'descriptors': ['L' + value + ';' for value in values], 'expected': expected})
Path(sys.argv[1]).write_text(json.dumps({'revision': revision, 'sourceSha256': source_sha,
    'pythonVersion': sys.version, 'unicodeVersion': unicodedata.unidata_version,
    'cases': cases}, ensure_ascii=True), encoding='utf-8')
print('%d actual-upstream-method reference cases generated; Python Unicode %s.' % (len(cases), unicodedata.unidata_version))
