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

mach = bytearray(0x1001)
struct.pack_into("<IIIIIIII", mach, 0, 0xfeedfacf, 0x01000007, 3, 2, 2, 176, 0x200085, 0)
struct.pack_into("<II16sQQQQIIII", mach, 32, 0x19, 152, b"__TEXT", 0x100000000,
                 0x2000, 0, len(mach), 7, 5, 1, 0)
struct.pack_into("<16s16sQQIIIIIIII", mach, 104, b"__text", b"__TEXT", 0x100001000,
                 1, 0x1000, 4, 0, 0, 0x80000400, 0, 0, 0)
struct.pack_into("<IIQQ", mach, 184, 0x80000028, 24, 0x1000, 0)
mach[0x1000] = 0xc3
(destination / "mach64.bin").write_bytes(mach)

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
