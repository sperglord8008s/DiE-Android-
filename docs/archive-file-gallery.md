# Archive file gallery and exports

Open a recovered archive from Gallerize, then choose **Gallery** in the native
archive browser. The same content-identified inventory now has a folder/list
view and a 24-card gallery. A file tap acquires its complete logical bytes and
opens the in-app preview. Hold a card for original metadata, analysis and
nested-archive actions. **Save file** stages an individual file. **Export files
ZIP** stages all matching pages, including descendants of displayed folders.

The original tree, All entries, parsing intervals, failed-attempt evidence,
classic ZIP, nested Back and workbench handoffs remain available. Gallery mode
and the existing folder/filter/page descriptors survive recreation.

Secondary actions (original evidence, report export, parsing interval, reload
and classic ZIP) are grouped under **Tools / evidence**. The two-line status
expands on tap or through **Status / limits**, retaining full source/launch/
failure/scope/budget text. A 360×640 phone-layout case verifies at least 200
pixels for the file pane, all existing tool actions and status expansion.

## Actual bytes before previews

Each automatic preview uses `NativeArchiveStore.acquire`: original source
identity, complete directory and selected index are revalidated, the decoder
must return complete bytes, declared/acquired sizes must agree and Java computes
the owned output's SHA-256 and CRC32. Names and extensions do not select an
archive decoder. Image/media headers choose a presentation hint only after
checked acquisition. Images get the bounded platform first-frame decoder;
media uses the existing platform metadata/scaled-frame helper; UTF-8 gets a
bounded text prefix. PDF cards open the existing page viewer. Nested archive
cards expose **Browse archive** after byte-based recognition.

Unsupported decoder/codec states stay visible and the file remains available
for explicit preview, Hex, tools and exact-byte export. A thumbnail does not
verify every frame, full file integrity or publisher identity. Preview
observations are transient; original native inventory evidence is unchanged.

Automatic page acquisition has separate limits: 24 cards, a known declared size
of at most 32 MiB per member, 96 MiB cumulative member bytes and 512 MiB sum of
source archive sizes across attempts. The last limit bounds repeated whole-source
identity work by input size; it is not an exact native read-work counter.
Unknown/larger members and a page budget refusal remain listed. Explicit
preview/save keeps the archive engine's ordinary 256 MiB member cap. Temporary
member snapshots are removed after thumbnail generation. Leaving a page cancels
its queued work and refuses stale results.

## Ordinary file outputs

`ArchiveFiles` and `ArchiveListing` separate payload export and scope selection
from widgets. Individual saves retain a usable extension/MIME. A recognized
header can correct a misleading suffix, such as a PNG named `photo.wav`.
Unrecognized types retain a constrained declared suffix or `.txt`/`.bin` hint;
these hints are not format validation. Names have an entry-index prefix and a
short sanitized basename. No original archive name becomes an output path.

ZIP exports contain checked decoded file payloads, **not header locations**:

- Index-prefixed flat filenames preserve duplicate entries independently.
- `_archive.json` records every payload's original name, index, size, SHA-256,
  CRC32 and acquisition-report path, plus the folder/filter/parsing scope.
- `_evidence/entry-N.json` retains each original complete acquisition report.
- `_evidence/archive-trail.json` retains the current archive chain, original
  launch findings and recorded failures.
- Directory/link/encrypted/special/over-budget entries are explicitly listed
  as excluded. No link is materialized. Any eligible member's acquisition
  failure fails the entire staged ZIP rather than silently skipping it.

Selection spans every matching page. In folder mode, a displayed virtual folder
includes all its descendants; a filter that selects a folder includes that
folder's contents. All entries retains unusual names and original indices.
Derived logical bytes have no invented physical source offsets.

Limits are 4,096 payload files, 240 MiB decoded payload, 32 MiB original evidence,
256 MiB encoded staging and a cooperative five-minute overall preparation
deadline. Existing native per-operation limits also apply. Each member is
acquired separately, so large archives with many entries can be expensive; a
single-pass native batch extraction remains future work. Narrowing the folder
or filter reduces work. Cancellation, source changes, IO, budget and decode
errors discard unfinished stages and temporary members. A completed stage is
independent of the source and survives the document picker and recreation.

## Verification

New actual-libarchive/Robolectric cases cover mislabeled PNG thumbnails,
byte-exact typed saves and direct preview handoffs; a 30-file export over two
gallery pages; complete acquisition-report identities; picker recreation after
source expiry; duplicate and unsafe names; recursive displayed folders; explicit
link/directory exclusions; a compressed 33 MiB member's automatic-preview refusal;
cancellation after a ZIP has started writing; and the compact-phone viewport. The older archive cases are
retained. All 188 host tests and both five-job source CI runs pass at 2dcfce3;
pre-alpha.15 is published. The release checklist records these results
separately from selected actual worker runtime contracts.

OS IPC and document providers are substituted in these host tests. Production
ARM64 gallery/SAF/codec/handset flows, full PDF rendering, animation and all file
types remain unverified or incomplete. This adds galleries for manually selected
archive levels; it does not automatically decompress every nested archive into
one recursively scanned gallery. See [native archive contracts](native-archives.md)
and [recovered file gallery](recovered-file-gallery.md).
