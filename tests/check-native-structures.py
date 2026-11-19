#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Independent byte-level expectations for the production CDiE visitor adapter."""
import json
from pathlib import Path
import struct
import sys

fixtures, reports = map(Path, sys.argv[1:])
checked = 0


def field(table, parent, name, physical=None):
    matches = [r for r in table['rows'] if r['rowType'] == 'field'
               and table['rows'][r['parentStructureIndex']]['name'] == parent
               and r['name'] == name and (physical is None or r['physicalOffset'] == physical)]
    assert len(matches) == 1, (parent, name, len(matches))
    return matches[0]


def expect(table, data, parent, name, offset, fmt, value, properties, variant=True):
    global checked
    row = field(table, parent, name, offset)
    size = struct.calcsize(fmt)
    assert struct.unpack_from(fmt, data, offset)[0] == value
    assert row['sizeBytes'] == str(size) and row['physicalRangeValid']
    assert row['properties'] == properties
    assert int(row['rawValue'], 16) == value
    if variant:
        assert int(row['nativeValue']['value']) == value
    else:
        assert row['nativeValue'] == {'typeId': 0, 'type': 'none', 'value': None}
    owner = table['rows'][row['parentStructureIndex']]
    assert owner['physicalOffset'] + int(row['relativeOffset']) == offset
    checked += 1


for bits in (32, 64):
    name = f'pe{bits}-rich.exe'
    data = (fixtures / name).read_bytes()
    table = json.loads((reports / (name + '.json')).read_text())
    assert table['metadata']['fieldCount'] + table['metadata']['structureCount'] == table['rowCount']
    expect(table, data, 'IMAGE_DOS_HEADER', 'e_magic', 0, '<H', 0x5a4d, 0x400)
    expect(table, data, 'IMAGE_DOS_HEADER', 'e_lfanew', 0x3c, '<I', 0x80, 4)
    expect(table, data, 'IMAGE_FILE_HEADER', 'Machine', 0x84, '<H', 0x14c if bits == 32 else 0x8664, 1)
    expect(table, data, 'IMAGE_FILE_HEADER', 'NumberOfSections', 0x86, '<H', 3, 0x10)
    expect(table, data, f'IMAGE_OPTIONAL_HEADER{bits}', 'AddressOfEntryPoint', 0xa8, '<I', 0x1000, 2)
    expect(table, data, f'IMAGE_OPTIONAL_HEADER{bits}', 'ImageBase', 0xb4 if bits == 32 else 0xb0, '<I' if bits == 32 else '<Q', 0x400000 if bits == 32 else 0x140000000, 2)
    # The UTF-8 COFF name is retained in its returned native byte/string value.
    for row in table['rows']:
        if row['rowType'] != 'field':
            continue
        for key, units in row['nativeTextUnits'].items():
            if units is None:
                assert row[key] is None
                continue
            decoded = ''.join(chr(int(units[i:i+8], 16)) for i in range(0, len(units), 8))
            assert row[key] == decoded

for name in ('mach64.bin', 'mach64-high.bin'):
    data = (fixtures / name).read_bytes()
    table = json.loads((reports / (name + '.json')).read_text())
    expect(table, data, 'mach_header_64', 'magic', 0, '<I', 0xfeedfacf, 1)
    expect(table, data, 'mach_header_64', 'cputype', 4, '<I', 0x01000007, 1)
    expect(table, data, 'segment_command_64', 'vmaddr', 56, '<Q', 0xffff800000000000 if 'high' in name else 0x100000000, 2)
    expect(table, data, 'section_64', 'offset', 152, '<I', 0x1000, 4)

data = (fixtures / 'mach-fat.bin').read_bytes()
for report_name, bias, cpu in [('mach-fat.bin.json', 0x1000, 0x01000007), ('mach-fat-second.json', 0x3000, 0x0100000c)]:
    table = json.loads((reports / report_name).read_text())
    expect(table, data, 'fat_header', 'magic', 0, '>I', 0xcafebabe, 0x400, False)
    expect(table, data, 'fat_arch', 'offset', 16, '>I', 0x1000, 4, False)
    expect(table, data, 'fat_arch', 'offset', 36, '>I', 0x3000, 4, False)
    expect(table, data, 'mach_header_64', 'cputype', bias + 4, '<I', cpu, 1)
    expect(table, data, 'section_64', 'offset', bias + 152, '<I', 0x1000, 4)
    opaque = [r for r in table['rows'] if r['rowType'] == 'structure' and r['name'] == 'RAW_DATA']
    assert any(r['physicalOffset'] == (0x3000 if bias == 0x1000 else 0x1000) and r['sizeBytes'] == '4097' for r in opaque)

print(f'Independent Python struct/byte checks passed for {checked} exact PE/Mach-O/fat fields, raw values/semantic bits, whole-input parent offsets, high unsigned values and unselected raw slices.')
