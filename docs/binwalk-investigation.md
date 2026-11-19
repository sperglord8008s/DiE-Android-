# Binwalk and whole-blob carving

Open **File info → Binwalk / whole-blob signatures / resource carving**. The integrated engine is actual Binwalk source, Cargo version **3.1.1**, commit `26713972e3f9f52dc37d4a421c4554b0bd9e82ef`. Its catalog currently contains **111 signatures / 251 magic patterns** spanning media, archives, executable/firmware structures, compressed data and other binary formats. The complete pattern bytes, names and descriptions are inspectable in Root views; source/adapter/worker identity and limits accompany every export. See [native build details](../binary-engine/README.md).

## Discovery and evidence

The adapter searches the **entire supported input**, retains nested/overlapping magic hits, and calls each corresponding upstream validator. It includes short signatures at every byte offset. Binwalk's normal map scanner is not used because it skips nested content, prunes conflicting results and infers unknown lengths from the next hit or EOF. This adapter makes no such inference.

Parser-accepted candidates appear by default. Root views also expose every candidate, unresolved/rejected magic, format groups and all patterns. Each record retains the raw magic position, parser-reported physical start, pattern index, parser status, reported size or null, raw confidence, description and upstream extraction-declined flag where supplied. Repeated equivalent pattern results can be deduplicated; raw match/callback counts remain separate from row counts. Panicked validators stay unresolved and make parser coverage explicitly partial.

Confidence is Binwalk's format-recognition confidence, not a security score or proof that a file fully decodes. Validation varies by format: for example, the pinned PNG extent walker uses chunk structure/termination and does not verify every CRC or decode image pixels. A reported extent is checked against the input's physical bounds. It enables **exact stored-byte** extraction, range hashes/entropy and hex; it is not a guarantee of an intact/authentic recovered object. An unknown/rejected extent remains null and cannot become an automatic carve-to-EOF. The original evidence bytes and SHA-256 remain authoritative.

This supplies broad signature-guided resource carving and JPEG recovery behavior. The separate [source-built PhotoRec tool](photorec-investigation.md) adds its original header/data callbacks and explicit catalog coverage. Neither tool establishes full PhotoRec/JPEG Digger coverage, fragmented-file reconstruction, filesystem/unallocated-disk recovery, every media codec, recursive external decompression or JavaScript execution. No external extractor commands or inspected applications run. Some engine validators decompress internally to determine a boundary, which can be expensive. ZIP member acquisition, richer file metadata and actual [MPV media probing](media-investigation.md) remain complementary tools with different evidence contracts.

## Source ownership and the worker boundary

The parent acquires an owned private snapshot and fresh SHA-256 before binding. Android dispatches to a non-exported service in `:binary_worker` with `isolatedProcess=true`. Only a read-only input descriptor, an output pipe and a request token cross the boundary; no input/output filesystem path is provided. The service refuses ordinary app-UID execution and has no in-process fallback. Adapter 2 uses a private `F_DUPFD_CLOEXEC` duplicate and positional reads, preserving the caller's descriptor and seek position. It refuses non-regular, write-only and `O_PATH` descriptors. Host path opens are nonblocking so a FIFO cannot stall before that gate. Report data travels through the pipe instead of exceeding Binder's transaction-size limit.

Cancellation/deadline messages identify the owning token. A rejected second request must not cancel a different active job. The worker terminates its isolated process on matching cancellation or final unbinding; the client treats process death, incomplete JSON, mode/schema/count mismatches and missing UID isolation as failures. A **30-second cooperative native deadline** is checked between acquisition reads and parser callbacks, after matching and before returning the result. A single upstream callback cannot be preempted by this observer; the separate worker cancellation/death mechanism remains necessary. The request watchdog is **45 seconds**, with cooperative platform/Binder scheduling rather than a hard real-time guarantee. The service/native loader and protocol compile in the ARM64 app, but actual isolated UID/descriptor transfer, crash recovery, cancellation timing, backgrounding and API 26/35 lifecycle require handset verification.

The other CDiE, DEX and metadata tools have not been moved into this worker; their existing in-process limitations remain. The isolated service reduces the new engine's privileges but is not a claim that arbitrary hostile samples are safe. Process-death cache reclamation, per-format decompression/memory budgets and broader malformed-input coverage remain pending.

## Navigation, exports and limits

The shared viewer supplies 50/10/1-row pages, decoded-field search, raw details, format/catalog links, Back/recreation, exact range measurement and immutable SAF staging. A candidate/group export contains every returned record in that scope, regardless of display filter/page. Catalog scope exports every compiled magic pattern. Source identity, raw match/accepted/rejected/panic counts, known-versus-unknown extents, worker metadata and mode choices remain explicit.

Binwalk requires a contiguous slice for its original validators. Adapter 2 therefore retains a **file-sized native copy**, read with requests of at most 64 KiB; it does not claim PhotoRec's streaming-window memory bound. Before/after metadata checks cover device/inode/length/mtime/ctime through acquisition and analysis. They detect ordinary source changes, not an adversary restoring timestamps; the parent's private, SHA-256-identified snapshot remains authoritative. Native output exposes actual payload bytes, read operations, descriptor guarantees, deadline and callback-preemption limits. Memory used by validators/decompression, report values, allocators and the runtime is additional. Legacy adapter-1 captures remain readable without receiving adapter-2 guarantees.

Limits: 64 MiB input, 20,000 raw magic matches, 10,000 catalog entries at the Java boundary and 32 MiB native/pipe report. Exceeding a limit returns an explicit failure rather than successful truncated evidence. The candidate model validates reported input size, source revision, row/catalog counts, physical bounds and requested mode. Private snapshots are deleted on failed acquisition or normal viewer close; pending exports survive independently.

## Verification and reproducibility

Use `bash scripts/test-binwalk.sh` with JDK 17 and Rust 1.90.0. It builds the real Linux JNI library and generated image/audio data. Fixtures verify JPEG extents across an ICC-embedded false EOI, a valid PNG nested inside that JPEG's metadata, an outer PNG, WAV RIFF/gzip extents, unknown ELF length staying null, malformed matching PNG headers remaining unresolved, the full actual catalog, granted descriptor ownership, invalid descriptors/input budgets, complete scoped reports and exact immutable carved bytes. A corrupted catalog count is rejected without leaking an input snapshot. These are actual upstream parsers, not synthetic native output.

Two API 28 Robolectric checks validate the private/isolated service manifest and refusal of ordinary UID execution, bringing the Android suite to **39 checks**. They do not create a genuine isolated OS UID or verify production Binder transfer. The Binwalk candidate UI uses the shared fixture-tested viewer, but its complete handset path is still unverified. Android CI cross-compiles the real ARM64 library and checks signing, offline packaging, source/license assets, packaged service flags and **both** native libraries' static 16 KiB alignment. Actual 16 KiB Android runtime remains pending.

The engine and dependencies are openly licensed and pinned; full Binwalk, resolved crate/native and Rust standard-library notices are bundled. The desktop CLI/plotting browser-download dependency is not compiled. Once Git/Cargo/toolchain/SDK caches exist, offline builds preserve app privacy and require no data upload.

## Positional acquisition regression

The previous adapter duplicated an FD and then sought/read it. POSIX duplicates
share an open-file description, so a real host scan moved the caller's position
from byte 73 to 4,096. Adapter 2 fixes this with `FileExt::read_at`; the JNI
regression now retains byte 17 while returning the same semantic evidence as
the host path API. Some upstream encrypted-firmware patterns are generated from
a HashMap; catalog indices are local to each report. Comparisons resolve each
index to its exact pattern instead of assuming global numbering.

The host script also runs six Rust tests: exact multi-read bytes, CLOEXEC and
independent ownership, unreadable/O_PATH/directory/oversized inputs, nonblocking
FIFO refusal, timestamp-change/short-read detection, acquisition-budget failure
and an already-expired deadline. The actual JNI fixtures scan a complete sparse
64 MiB file with PNGs spanning a 64 KiB read boundary and ending at EOF. Corrupt
IO-accounting reports fail without leaking snapshots; original captures and
prior media/nested/extent/export fixtures still pass. These are host checks;
ARM64 compilation and static APK checks are separate from handset execution.
