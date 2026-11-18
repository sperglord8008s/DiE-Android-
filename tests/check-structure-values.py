#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
import json
from pathlib import Path
import sys

cases = {row['case']: row['nativeValue'] for row in map(json.loads, Path(sys.argv[1]).read_text().splitlines())}
assert cases['max-u64']['value'] == '18446744073709551615'
assert cases['min-i64']['value'] == '-9223372036854775808'
assert cases['negative-zero']['ieeeBitsHex'] == '80000000'
assert cases['nan-payload']['ieeeBitsHex'] == '7FF8123456789ABC'
for name in ['raw-string', 'raw-bytes']:
    assert bytes.fromhex(cases[name]['bytesHex']) == b'A\x00\xff"\n\\'
    assert cases[name]['lengthBytes'] == 6 and cases[name]['value'] is None
wide = cases['all-units']
raw = wide['wcharUnitsHex']
units = [int(raw[i:i+8], 16) for i in range(0, len(raw), 8)]
assert units == list(range(65536)) + [0x1f916, 0x110000, 0xffffffff]
# JSON parsers may combine adjacent surrogate escapes. Compare exact UTF-16 units.
expected = ''.join(map(chr, range(65536))) + '\U0001f916\ufffd\ufffd'
assert wide['value'].encode('utf-16-le', 'surrogatepass') == expected.encode('utf-16-le', 'surrogatepass')
assert wide['lengthNativeUnits'] == len(units)
assert cases['opaque-pointer']['value'] is None and cases['opaque-pointer']['opaquePointerPresent'] is True
assert '1234' not in json.dumps(cases['opaque-pointer'])
assert cases['absent']['value'] is None
print('Independent native-value reconstruction passed: exact 64-bit limits, signed zero/NaN payload bits, NUL/invalid UTF-8 bytes, every UTF-16 unit, supplementary/invalid wchar values and opaque pointers.')
