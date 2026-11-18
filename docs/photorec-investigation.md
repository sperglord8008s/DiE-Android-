# PhotoRec whole-blob investigation

Open **File info → PhotoRec / whole-blob formats / stored-byte carving**.
The app compiles unmodified PhotoRec 7.2 format sources, commit
`281be432dd79121e08e0898887a9ee1f30fb3e96`. It complements
[Binwalk](binwalk-investigation.md), [MPV](media-investigation.md) and
[metadata/EXIF investigation](metadata-investigation.md), each with separate
validation and coverage. Source pins, GPL-2.0-or-later notices and standalone
build instructions are in [carving-engine](../carving-engine/README.md).

## Catalog and search scope

The original registration API supplies **349 format groups / 774 magic
patterns**. The complete pattern bytes, relative offsets, descriptions, original
enable/recover flags and maximum file sizes remain inspectable/exportable.
The whole-blob index searches **581 patterns at every byte**. **193 one-byte or
all-zero patterns are not searched in this mode**; each remains in the catalog
with `searched: false` and an explicit reason. They need a separately bounded
anchored scope, currently pending. These exclusions include generic text and
encrypted/low-discrimination signatures; absence of a hit cannot prove absence
of those formats. Catalog groups are not a format-validation coverage claim.

Nested and overlapping matches remain separate. Every occurrence identifies its
original `patternIndex` and physical `magicOffset`. The candidate start is
`magicOffset - magicRelativeOffset`. If that is negative, no callback is invoked;
the exact magic remains an unresolved record, with a null candidate start and
its observed magic coordinate as the navigable `physicalOffset`. This exception
is explicitly labelled in `physicalOffsetMeaning` and is never extractable.
An independent naive matcher checks every searched-pattern occurrence in fixtures.

## What each check establishes

The upstream header callback runs in standalone `safe_header_only=1` mode with
an empty previous-file recovery context. This differs from PhotoRec's live disk
recovery state and from fidentify's default `safe_header_only=0`. The source
header window is 64 KiB, borrowed from immutable acquired bytes with one zero-padded
suffix; actual bytes and zero-padded lookahead are explicit.
`headerAccepted` means this callback accepted its header, including any padded
context. It is not full-file validation.

Where supplied, the original `data_check` callback processes 64 KiB blocks with
the previous block in the other half of its buffer. The final partial block is
zero-padded, as exposed in the report. The audited generic `data_check_size` callback ignores payload contents: it runs
against the actual available source length without copying every unused block.
`dataCheckMode` and zero processed payload bytes disclose this declared-size path;
it is not content integrity verification. Other data callbacks use streamed blocks.
Returned statuses preserve the upstream
enum: `DC_SCAN=0`, `DC_CONTINUE=1`, `DC_STOP=2`, `DC_ERROR=3`; null means no data
callback was run. The final calculated size, consumed source bytes, min/max,
offset-ok/error state, time, flags and returned extension remain visible.
Raw unsigned 64-bit calculated/minimum/offset values use exact decimal strings;
they cannot silently become rounded JSON floating-point numbers on Android.
Extractable source-bounded lengths and ordinary file coordinates remain numbers.

A positive calculated length after **DC_STOP**, within actual remaining source
bytes and upstream minimum/maximum, with no offset error, is a **reported stored
extent**. Only that interval enables exact byte export, hashes/entropy and hex.
The adapter never takes a length from the next hit or EOF. Missing terminators,
out-of-source sizes, data errors and header-only formats retain null lengths.
Header-calculated sizes outside PhotoRec's own data-check contract are preserved
without invoking a data callback; an actual 7z header fixture exercises a value
beyond signed 64-bit and checks that its decimal digits remain exact.

The adapter deliberately does **not** run full `file_check` or rename callbacks,
optional libjpeg/zlib verification, PhotoRec's disk recovery or reconstruction
of fragmented files. Some full callbacks open/rename outputs or extract thumbnails;
they do not belong in a read-only descriptor adapter. A bounded extent can still
contain corrupt data: the upstream PNG data walker finds chunk termination but
does not check PNG CRCs or decode pixels. Tests preserve this distinction using
an actual bad-CRC file. JPEG fixtures include an ICC false EOI and nested PNG;
those markers do not shorten the reported outer JPEG range.

## Ownership, worker and UI

The parent owns a whole private input snapshot and its SHA-256. The shared
non-exported isolated worker receives a read-only descriptor, output pipe and
request token; it receives no filesystem path and refuses normal app-UID
fallback. Native `pread` preserves caller descriptor ownership/position and
checks source size/timestamps across acquisition. Upstream path opens and renames
are redirected to denying functions, auxiliary `photorec.sig` is disabled, and
timestamp-changing capabilities are not configured. No inspected app, external
extractor or recovery command is run. Native defects may still crash/hang the
worker; real OS isolation/cancellation has not been verified on a handset.

The default view shows bounded ranges. Other roots expose header-accepted files,
all raw candidates, unresolved extents, format groups and the complete catalog.
Each catalog row labels SEARCHED or EXCLUDED. Help shows the partial catalog
coverage before opening detail. Search/paging never narrows a complete selected
scope export. Exact range bytes and complete JSON are staged before SAF; pending
exports survive source expiry and recreation independently of the viewer input.
Normal close deletes its private input; recreation reacquires from the original
path when available. Persistent carving sessions and recursive extracted-file
handoff remain pending.

## Limits and verification

Limits: **64 MiB input**, **50,000 candidate occurrences**, **10,000 patterns**,
**8 MiB copied data per candidate / 256 MiB aggregate data-block copying**, **24 MiB native report**,
**30-second cooperative native deadline**, and the shared **45-second request
watchdog / 32 MiB pipe envelope**. Input/match/report/deadline caps fail with no successful prefix. Data-work caps preserve complete magic/header discovery, mark each limited candidate with null extent and an explicit reason, and expose aggregate counts in help/exports.
Expensive native callbacks may need the parent watchdog. The Java boundary checks
source/schema/configuration identities, counts, search policy, magic-relative
coordinates, callback scope and every extractable data-stop witness.

Run `bash scripts/test-photorec.sh` and `bash scripts/test-workbench-ui.sh`.
Host checks use the real engine and a separately implemented naive magic oracle:
all 77 fixture occurrences, overlapping JPEG/PNG, GIF subblocks/trailer and WAV
declared size, negative/relative starts, DICOM header-only evidence, missing JPEG
EOI, malformed PNG length, bad-CRC semantics, invalid/oversized descriptors,
caller position, budget exhaustion, complete exports and exact immutable carving.
Three API 28 Robolectric cases use actual host JNI/widgets with only OS transport
and destination streams substituted: unknown/bounded evidence, picker-delay
source-expiry/recreation, and catalog paging/full export including exclusions.
The full regression count is recorded in the continuation/parity documents.

ARM64 source compilation, static 16 KiB alignment and host/fixture verification
are separate from device verification. Real SAF/Binder/isolated UID/crash/cancel,
API 26/35 and 16 KiB Android runtime remain unverified. Complete PhotoRec feature
parity, filesystem/unallocated media, fragmented/JPEG Digger recovery, every weak
signature and exhaustive format/codec/integrity checks remain incomplete.

Large-blob limitation reproduced locally: the preserved 48 MiB app APK exceeds
50,000 raw occurrences (even rejected headers are retained). This version fails
explicitly with the examined byte position/dominant pattern instead of exporting
a successful prefix. Compact indexed raw-occurrence transport is a continuation
priority; successful small media/oracle fixtures do not establish that large APK
coverage. Rejected recovery state uses an exact dictionary-described tuple in
the native report and expands into named fields when viewed/exported; the
upstream callback does not guarantee a valid recovery object after rejection.
