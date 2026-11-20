# Source-built archive engine

This module builds libarchive **3.8.11** and bzip2 **1.0.8**, XZ/liblzma **5.8.4**,
LZ4 **1.10.0**, and Zstandard **1.5.7** from hash-pinned original source archives.
Zlib is the target system/NDK dependency and its runtime version is captured.
`source-lock.json` pins original archives, API/license headers and exact revisions.
The two original Zstandard CLI-test symlinks are explicitly excluded from the
prepared source directory; complete original archives remain available unchanged.
No upstream library source is patched. The small bzip2 CMake wrapper is ours.

The JNI descriptor API lists complete or explicitly failed metadata scopes and
acquires one regular member's logical bytes to a separate empty writable FD.
The native implementation never takes a sample pathname, creates a member path,
follows a link or launches an extractor. Positional source reads preserve caller
FD offsets and ownership. Output uses positional writes to a caller-owned empty
regular FD, refuses append/source-alias/read-only destinations, and returns no
successful acquired extent after a parser/CRC/budget failure. Sparse logical
holes have zero bytes; trailing extension requires an actual sparse witness.

Format/filter functions are registered explicitly. Program filters, mtree disk
resolution, external dictionaries/volumes, filesystem restoration and password
support are disabled. Available metadata includes normalized names/raw accessor
bytes, sizes/flags, times, UID/GID, modes/devices/links, ACL/xattr/sparse/Mac data,
format/filter state and errors. It is returned public-accessor evidence, not a
claim that every original stored header field or reader API is exposed.
Unconditional digest getter buffers have **unknown presence**; zeros are not
asserted declared/verified checksums. UTF-8 and C-string bytes remain separate.

`archive_read_header_position` is retained as a raw per-iteration observation,
without a physical-byte link or stable-identity claim. A direct upstream API
oracle proves CAB LZX reports different positions when member data is consumed
versus skipped. Stable entry comparisons exclude this one observation while
retaining both complete original reports. No offsets are rewritten to make
them look identical.

Limits are 1 GiB source, 20,000 entries, 16 MiB aggregate entry metadata / 4 MiB
per entry / 24 MiB report, 256 MiB selected logical member, 512 MiB transformed
stream work, 4 GiB source-read work and a cooperative 30-second budget. LZMA decoder memory estimates/limits and Zstandard windows are capped at 64 MiB
by adapter linker wrappers. These are codec bounds, not whole-process memory
limits. Valid excessive-dictionary/window fixtures are checked against independent
decoders, then refused by this adapter. Parser
callbacks are not hard preemptible and memory use includes upstream allocation.
The Android worker/client supplies a 45-second request watchdog and requires an
isolated UID with no in-process fallback. Host refusal checks and compilation
do not prove real Android transport, process isolation or safety for arbitrary
hostile input.
Partial/unsupported scopes retain observations and diagnostics without claiming
a complete inventory or successful acquisition. XAR is explicitly unavailable
in this build because optional XML libraries are disabled.

Build with JDK 17/CMake and the project's pinned SDK/NDK:

```
ARCHIVE_DEPS_DIR=<source-cache> bash scripts/test-archive-engine.sh
ARCHIVE_DEPS_DIR=<source-cache> bash scripts/build-archive.sh android
```

`DIE_OFFLINE=true` requires every original archive in the cache. Prepared source
bytes and API/license contracts are rechecked. CLI/test programs are not built
into the engine. `scripts/archive-notices.py --check` verifies the complete
license texts and original copyright/header catalog. Full sources, notices and
adapter code are required alongside eventual runtime distribution.

Host fixtures use independent Python TAR/PAX/xattr/ZIP/gzip/bzip2/XZ encoders,
system Zstandard/LZ4 frame encoders, and original pinned upstream 7z, RAR5,
CAB STORE/MSZIP/LZX and compressed ISO references. Complete payload hashes,
metadata/link/duplicate identities, CRC refusal, embedded scopes, FD positions
and a direct upstream header-position oracle are checked. One LZMA2 7z reference
has an upstream length/prefix expectation rather than an independent full-payload
hash; keep that distinction. Inputs are parsed as data and never executed.

The APK now packages this engine and complete notices. `ArchiveRunner` /
`BinaryWorkerClient` provide descriptor-only inspection/acquisition;
`NativeArchiveStore` binds reports and derived payloads to complete source
identities; `NativeArchiveSession` owns bounded nested captures; and
`NativeArchiveExplorerActivity` presents virtual folders, complete indexed
entries, raw metadata, nested Back, explicit scopes, checked previews/member
return and full current-chain exports. The original ZIP browser remains
available. See [Android workbench contracts](../docs/native-archives.md) for
implemented/host-verified/device-unverified scope and all remaining limits.
This module does not claim universal obfuscation, encryption, multi-volume or
fragmented recovery.
