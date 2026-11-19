#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Independent byte Counter + math.log2 oracle; no production entropy/model code."""
from collections import Counter
import json
import math
from pathlib import Path
import struct
import sys

fixtures, reports = map(Path, sys.argv[1:])
measurements = bins = scopes = 0
for path in sorted(reports.glob('*.json')):
    table = json.loads(path.read_text())
    data = (fixtures / path.name.removesuffix('.json')).read_bytes()
    assert table['fileSizeBytes'] == len(data)
    count = Counter(data)
    rows = table['rows']
    sample_count = min(100, max(1, len(data) // 512)) if data else 0
    assert table['metadata']['sampleCount'] == sample_count
    block_end = 0
    native_bins = {}
    for row in rows:
        kind = row['rowType']
        assert row['virtualAddress'] is None
        if kind == 'byte-frequency':
            assert row['physicalOffset'] is None and row['sizeBytes'] is None
            byte = row['byteValue']
            assert int(row['count']) == count[byte]
            assert row['probability'] == (count[byte] / len(data) if data else 0)
            native_bins[byte] = int(row['count'])
            bins += 1
        else:
            offset, size = row['physicalOffset'], int(row['sizeBytes'])
            assert offset >= 0 and size >= 0 and offset + size <= len(data)
            source = data[offset:offset+size]
            histogram = Counter(source)
            expected = -sum((n / size) * math.log2(n / size) for n in histogram.values()) if size else 0
            assert math.isclose(row['entropyBitsPerByte'], expected, abs_tol=1e-12), (path.name, row['index'], expected, row['entropyBitsPerByte'])
            assert struct.unpack('>d', bytes.fromhex(row['entropyIeeeBitsHex']))[0] == row['entropyBitsPerByte']
            assert row['upstreamDisplayLabel'] == ('packed' if expected >= 6.5 else 'not packed')
            if kind == 'whole-file':
                assert offset == 0 and size == len(data)
            if kind == 'block':
                assert offset == block_end and size > 0
                block_end += size
            measurements += 1
    assert block_end == len(data)
    assert native_bins == {byte: count[byte] for byte in range(256)}
    if path.name == 'entropy-zero.bin.json':
        assert rows[0]['entropyBitsPerByte'] == 0 and native_bins[0] == len(data)
    if path.name == 'entropy-uniform.bin.json':
        assert rows[0]['entropyBitsPerByte'] > 7.99
    scopes += 1
print(f'Independent byte Counter/log2/IEEE oracle passed {scopes} scopes, {measurements} complete physical measurements and all {bins} histogram bins, including empty counts, remainders, high entropy and native executable regions.')
