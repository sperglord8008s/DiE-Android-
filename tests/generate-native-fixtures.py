#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Small deterministic format fixtures made from documented headers, never executed."""
import hashlib
from pathlib import Path
import struct
import sys
import zipfile
import zlib

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=True)


def pe(bits):
    data = bytearray(0x400)
    def put(offset, fmt, *values):
        struct.pack_into("<" + fmt, data, offset, *values)
    data[:2] = b"MZ"
    put(0x3c, "I", 0x80)
    data[0x80:0x84] = b"PE\0\0"
    optional_size = 224 if bits == 32 else 240
    put(0x84, "HHIIIHH", 0x14c if bits == 32 else 0x8664, 1, 0, 0, 0,
        optional_size, 0x102 if bits == 32 else 0x22)
    opt = 0x98
    put(opt, "HBBIII", 0x10b if bits == 32 else 0x20b, 14, 0, 0x200, 0, 0)
    put(opt + 16, "II", 0x1000, 0x1000)
    if bits == 32:
        put(opt + 24, "II", 0, 0x400000)
        put(opt + 72, "IIIIII", 0x100000, 0x1000, 0x100000, 0x1000, 0, 16)
    else:
        put(opt + 24, "Q", 0x140000000)
        put(opt + 72, "QQQQII", 0x100000, 0x1000, 0x100000, 0x1000, 0, 16)
    put(opt + 32, "IIHHHHHH", 0x1000, 0x200, 6, 0, 0, 0, 6, 0)
    put(opt + 56, "IIIHH", 0x2000, 0x200, 0, 3, 0x140)
    section = opt + optional_size
    data[section:section + 8] = b".text\0\0\0"
    put(section + 8, "IIIIIIHHI", 6, 0x1000, 0x200, 0x200, 0, 0, 0, 0, 0x60000020)
    data[0x200:0x206] = b"\xb8\x2a\0\0\0\xc3"
    return data


for bits in (32, 64):
    (destination / f"pe{bits}.exe").write_bytes(pe(bits))


def rich_pe(bits):
    """IAT/ILT, ordinal imports, exports/forwarder, a resource and COFF symbols."""
    data = pe(bits) + bytearray(0x900)
    def put(offset, fmt, *values):
        struct.pack_into("<" + fmt, data, offset, *values)
    opt = 0x98
    optional_size = 224 if bits == 32 else 240
    directories = opt + (96 if bits == 32 else 112)
    put(0x86, "H", 3)
    put(opt + 56, "I", 0x4000)
    put(directories, "IIIIII", 0x2100, 0x100, 0x2000, 40, 0x3000, 0x100)
    sections = opt + optional_size
    for index, (name, virtual_size, address, raw_size, offset, flags) in enumerate([
        (b".text", 0x300, 0x1000, 0x200, 0x200, 0x60000020),
        (b".rdata", 0x400, 0x2000, 0x400, 0x400, 0x40000040),
        (b".rsrc", 0x400, 0x3000, 0x400, 0x800, 0x40000040),
    ]):
        at = sections + index * 40
        data[at:at+8] = name.ljust(8, b"\0")
        put(at+8, "IIIIIIHHI", virtual_size, address, raw_size, offset, 0, 0, 0, 0, flags)
    put(0x400, "IIIII", 0x2060, 0, 0, 0x20a0, 0x2080)
    for at in (0x460, 0x480):
        put(at, "III" if bits == 32 else "QQQ", 0x20c0, (1 << (bits-1)) | 7, 0)
    data[0x4a0:0x4ac] = b"fixture.dll\0"
    put(0x4c0, "H", 0x23)
    data[0x4c2:0x4d1] = b"fixture_import\0"
    put(0x500, "IIHHIIIIIII", 0, 0, 0, 0, 0x2170, 1, 2, 2, 0x2140, 0x2150, 0x2160)
    put(0x540, "II", 0x1000, 0x21b0)
    put(0x550, "II", 0x2180, 0x2190)
    put(0x560, "HH", 0, 1)
    data[0x570:0x57c] = b"fixture.dll\0"
    for at, text in [(0x580, b'fixture_"exp\0'), (0x590, b"fixture_forward\0"), (0x5b0, b"target.Export\0")]:
        data[at:at+len(text)] = text
    for at in (0x800, 0x820, 0x840):
        put(at, "IIHHHH", 0, 0, 0, 0, 0, 1)
    put(0x810, "II", 10, 0x80000020)
    put(0x830, "II", 101, 0x80000040)
    put(0x850, "II", 1033, 0x60)
    payload = b"DIE_RESOURCE_PAYLOAD\0\xff"
    put(0x860, "IIII", 0x3080, len(payload), 65001, 0)
    data[0x880:0x880+len(payload)] = payload
    put(0x8c, "II", 0xc00, 7)
    symbols = [(b"die_func", 0, 1, 0x20, 2, 0), (b"abs_sym", 0x1234, -1, 0, 2, 0),
               (b"undef", 0, 0, 0, 2, 0), (b".file", 0, -2, 0, 103, 1)]
    for i, (name, value, section, kind, storage, aux) in enumerate(symbols):
        put(0xc00+i*18, "8sIhHBB", name, value, section, kind, storage, aux)
    data[0xc48:0xc5a] = b"fixture-source.c\0\0"
    put(0xc5a, "8sIhHBB", b"local", 0x10, 2, 0, 3, 0)
    put(0xc6c, "IIIhHBB", 0, 4, 0x20, 2, 0, 2, 0)
    long_name = "long_é_symbol".encode("utf-8") + b"\0"
    put(0xc7e, "I", len(long_name) + 4)
    data[0xc82:0xc82+len(long_name)] = long_name
    return data


for bits in (32, 64):
    (destination / f"pe{bits}-rich.exe").write_bytes(rich_pe(bits))
(destination / "resource-payload.bin").write_bytes(b"DIE_RESOURCE_PAYLOAD\0\xff")
for count in (10000, 10001):
    many = pe(32) + bytearray(count * 18 + 4)
    struct.pack_into("<II", many, 0x8c, 0x400, count)
    for index in range(count):
        struct.pack_into("<8sIhHBB", many, 0x400 + index * 18, f"S{index:07}".encode(), index % 256, 1, 0x20, 2, 0)
    struct.pack_into("<I", many, 0x400 + count * 18, 4)
    (destination / f"pe-symbols-{count}.exe").write_bytes(many)

mach = bytearray(0x1001)
struct.pack_into("<IIIIIIII", mach, 0, 0xfeedfacf, 0x01000007, 3, 2, 2, 176, 0x200085, 0)
struct.pack_into("<II16sQQQQIIII", mach, 32, 0x19, 152, b"__TEXT", 0x100000000,
                 0x2000, 0, len(mach), 7, 5, 1, 0)
struct.pack_into("<16s16sQQIIIIIIII", mach, 104, b"__text", b"__TEXT", 0x100001000,
                 1, 0x1000, 4, 0, 0, 0x80000400, 0, 0, 0)
struct.pack_into("<IIQQ", mach, 184, 0x80000028, 24, 0x1000, 0)
mach[0x1000] = 0xc3
(destination / "mach64.bin").write_bytes(mach)
high_mach = bytearray(mach)
struct.pack_into("<Q", high_mach, 56, 0xffff800000000000)
struct.pack_into("<Q", high_mach, 136, 0xffff800000001000)
(destination / "mach64-high.bin").write_bytes(high_mach)
zero_fill_mach = bytearray(mach)
# A declared zero-fill section has no stored payload even with a nonzero offset.
struct.pack_into("<I", zero_fill_mach, 168, 1)
(destination / "mach64-zerofill.bin").write_bytes(zero_fill_mach)
(destination / "nops.bin").write_bytes(b"\x90" * 9000)

dex = bytearray(112)
dex[:8] = b"dex\n035\0"
struct.pack_into("<III", dex, 32, len(dex), 112, 0x12345678)
dex[12:32] = hashlib.sha1(dex[32:]).digest()
struct.pack_into("<I", dex, 8, zlib.adler32(dex[12:]))
(destination / "empty.dex").write_bytes(dex)

with zipfile.ZipFile(destination / "archive.zip", "w") as archive:
    archive.writestr("fixture.txt", b"DIE_ZIP_MEMBER_FIXTURE", compress_type=zipfile.ZIP_DEFLATED)

for name, data in {"truncated-elf.bin": b"\x7fELF\x02\x01", "truncated-pe.bin": pe(32)[:96],
                   "truncated-mach.bin": mach[:16]}.items():
    (destination / name).write_bytes(data)

# Universal Mach-O fixture: unlike process execution, opening either slice is static.
fat = bytearray(0x4001)
struct.pack_into(">II", fat, 0, 0xcafebabe, 2)
for i, (cpu, offset) in enumerate([(0x01000007, 0x1000), (0x0100000c, 0x3000)]):
    struct.pack_into(">IIIII", fat, 8 + i * 20, cpu, 3 if i == 0 else 0, offset, len(mach), 12)
    content = bytearray(mach)
    struct.pack_into("<II", content, 4, cpu, 3 if i == 0 else 0)
    fat[offset:offset+len(content)] = content
(destination / "mach-fat.bin").write_bytes(fat)
