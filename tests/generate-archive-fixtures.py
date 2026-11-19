#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Independent standard-library archive fixtures; their data is never executed."""
import base64
import bz2
import ctypes
import ctypes.util
import gzip
import hashlib
import io
import json
from pathlib import Path
import sys
import tarfile
import zipfile
import lzma

out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
payload='Exact archive member 世界 🔬 with embedded NUL\x00 and end.\n'.encode()
manifest={}
def record(name, data, expected):
    (out/name).write_bytes(data)
    manifest[name]={'sha256':hashlib.sha256(data).hexdigest(),'entries':expected}
def member(name, data):
    return {'name':name,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()}
stream=io.BytesIO()
with tarfile.open(fileobj=stream,mode='w',format=tarfile.PAX_FORMAT) as archive:
    info=tarfile.TarInfo('folder/世界.txt');info.uid=1234;info.gid=5678;info.uname='owner';info.gname='group';info.mode=0o640
    info.size=len(payload);info.pax_headers={'mtime':'1234567890.123456789','SCHILY.xattr.user.example':'value-世界'}
    archive.addfile(info,io.BytesIO(payload))
    link=tarfile.TarInfo('link');link.type=tarfile.SYMTYPE;link.linkname='../../outside';archive.addfile(link)
    hard=tarfile.TarInfo('hard');hard.type=tarfile.LNKTYPE;hard.linkname='folder/世界.txt';archive.addfile(hard)
    directory=tarfile.TarInfo('folder/');directory.type=tarfile.DIRTYPE;archive.addfile(directory)
    empty=tarfile.TarInfo('empty');empty.size=0;archive.addfile(empty,io.BytesIO())
tar=stream.getvalue();tar_entries=[member('folder/世界.txt',payload),{'name':'link','link':True},{'name':'hard','link':True},{'name':'folder/','directory':True},member('empty',b'')]
for name,data in [('mislabeled.bin',tar),('tar.gz',gzip.compress(tar,mtime=0)),('tar.bz2',bz2.compress(tar)),('tar.xz',lzma.compress(tar))]:record(name,data,tar_entries)
record('raw.gz',gzip.compress(payload,mtime=0),[member('data',payload)])
record('raw.bz2',bz2.compress(payload),[member('data',payload)])
record('raw.xz',lzma.compress(payload),[member('data',payload)])
record('raw.lzma',lzma.compress(payload,format=lzma.FORMAT_ALONE),[member('data',payload)])
zipbytes=io.BytesIO()
with zipfile.ZipFile(zipbytes,'w',compression=zipfile.ZIP_STORED) as archive:
    archive.writestr('../outside.txt',payload)
    archive.writestr('duplicate',b'first')
    archive.writestr('duplicate',b'second')
record('mislabeled.apk',zipbytes.getvalue(),[member('../outside.txt',payload),member('duplicate',b'first'),member('duplicate',b'second')])
corrupt=bytearray(zipbytes.getvalue());at=corrupt.index(payload);corrupt[at]^=1
record('bad-crc.zip',corrupt,[])
record('embedded.blob',b'prefix without an archive\x00'+tar,tar_entries)
manifest['embedded.blob']['offset']=len(b'prefix without an archive\x00')
record('unknown.dat',b'unknown arbitrary bytes\x01\x02\xff',[])
# Real host codec encoders produce independent frame fixtures for the pinned adapter decoders.
zstd_name=ctypes.util.find_library('zstd')
lz4_name=ctypes.util.find_library('lz4')
if not zstd_name or not lz4_name:raise SystemExit('Host Zstandard/LZ4 runtime libraries required for independent frame fixtures')
zstd=ctypes.CDLL(zstd_name);zstd.ZSTD_compressBound.argtypes=[ctypes.c_size_t];zstd.ZSTD_compressBound.restype=ctypes.c_size_t
zstd.ZSTD_compress.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p,ctypes.c_size_t,ctypes.c_int];zstd.ZSTD_compress.restype=ctypes.c_size_t
buffer=ctypes.create_string_buffer(zstd.ZSTD_compressBound(len(tar)));size=zstd.ZSTD_compress(buffer,len(buffer),tar,len(tar),3)
if size>len(buffer):raise SystemExit('Zstandard fixture encoding failed')
record('tar.zst',buffer.raw[:size],tar_entries)
lz4=ctypes.CDLL(lz4_name);lz4.LZ4F_compressFrameBound.argtypes=[ctypes.c_size_t,ctypes.c_void_p];lz4.LZ4F_compressFrameBound.restype=ctypes.c_size_t
lz4.LZ4F_compressFrame.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p];lz4.LZ4F_compressFrame.restype=ctypes.c_size_t
buffer=ctypes.create_string_buffer(lz4.LZ4F_compressFrameBound(len(tar),None));size=lz4.LZ4F_compressFrame(buffer,len(buffer),tar,len(tar),None)
if size>len(buffer):raise SystemExit('LZ4 fixture encoding failed')
record('tar.lz4',buffer.raw[:size],tar_entries)
# Original hash-pinned upstream reference bytes; expectations are taken from its source tests.
import binascii
import struct
upstream=Path(__file__).resolve().parents[1]/'vendor/Archive/libarchive/libarchive/test'
def original_fixture(source_name, expected):
    lines=(upstream/(source_name+'.uu')).read_bytes().splitlines()
    begin=next(i for i,line in enumerate(lines) if line.startswith(b'begin '))
    data=b''.join(binascii.a2b_uu(line) for line in lines[begin+1:] if line and line!=b'end')
    record('upstream-'+source_name,data,expected)
file1=b'                          file 1 contents\n'+b'hello\n'*3
file2=b'                          file 2 contents\n'+b'hello\n'*6
original_fixture('test_read_format_7zip_copy.7z',[member('file1',file1)])
original_fixture('test_read_format_7zip_lzma2.7z',[{'name':'file1','size':2844,'prefix':'The libarchive distribution '}])
original_fixture('test_read_format_rar5_stored.rar',[member('helloworld.txt',b'hello libarchive test suite!\n')])
rar_data=b''.join(struct.pack('<i',max(0,k*k-3*k+1)) for k in range(1,301))
original_fixture('test_read_format_rar5_compressed.rar',[member('test.bin',rar_data)])
for number in [1,2,3]:
    entries=[member('empty',b'')]
    if number>1:entries.append(member('zero',b'\0'*33000))
    entries += [member('dir1/file1',file1),member('dir2/file2',file2)]
    original_fixture('test_read_format_cab_'+str(number)+'.cab',entries)
original_fixture('test_read_format_iso.iso.Z',[{'name':'.','directory':True}])
# Positive member larger than the old 1.25 MiB confusion; native output is streamed to its FD.
large=bytes(range(256))*(16*1024*1024//256)+bytes(range(17))
large_tar=io.BytesIO()
with tarfile.open(fileobj=large_tar,mode='w',format=tarfile.USTAR_FORMAT) as archive:
    info=tarfile.TarInfo('large.bin');info.size=len(large);archive.addfile(info,io.BytesIO(large))
record('large-16MiB.tar.xz',lzma.compress(large_tar.getvalue(),preset=0),[member('large.bin',large)])
del large,large_tar
# Valid frames advertising excessive decoder dictionaries/windows must be refused explicitly.
large_xz=lzma.compress(payload,filters=[{'id':lzma.FILTER_LZMA2,'dict_size':128*1024*1024}])
record('budget-large-dictionary.xz',large_xz,[])
assert lzma.decompress(large_xz)==payload
raw_zstd=b'\x28\xb5\x2f\xfd'+bytes([0,(27-10)<<3])+((len(payload)<<3)|1).to_bytes(3,'little')+payload
zstd.ZSTD_decompress.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p,ctypes.c_size_t];zstd.ZSTD_decompress.restype=ctypes.c_size_t
plain=ctypes.create_string_buffer(len(payload));decoded=zstd.ZSTD_decompress(plain,len(plain),raw_zstd,len(raw_zstd))
assert decoded==len(payload) and plain.raw==payload
record('budget-large-window.zst',raw_zstd,[])
(out/'expected.json').write_text(json.dumps(manifest,ensure_ascii=True,indent=2)+'\n')
print('Independent tar/PAX/xattr/link/ZIP/duplicate/CRC/embedded/gzip/bzip2/XZ/LZMA/Zstandard/LZ4 fixtures generated')
