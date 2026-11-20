# Recovered files in Gallerize

Gallerize opens on **Files**. It scans the same privately owned input with both
PhotoRec and Binwalk, then presents their actual bounded files together.
Tap a file to open its in-app preview; tap an archive to browse its contents.
Use **Save file** on a tile for the exact recovered payload, or **Export files
(ZIP)** for every file in the selected group, across all gallery pages. The ZIP
contains ordinary files with usable extensions, plus a recovery manifest.

Images, audio/video and text retain the existing thumbnail/media/text previews.
The shared viewer offers platform image/audio/video rendering, paged UTF-8,
PDF pages and Hex. Decoder availability depends on the platform and format.
Header recognition alone does not establish a file's complete extent or make
an unsupported codec viewable. Existing manual view controls and full tools
remain available. Long-press a tile for ranges, original findings and expert
actions; complete reports and grid exports are under **Tools / evidence**.

## Discovery and file identity

`GalleryDiscovery` retains each engine's original JSON capture and SHA-256,
checks it on restoration and borrows the gallery's input instead of making a
second whole-file copy. Source expiry and screen recreation reuse those exact
captures. They never silently rescan a missing or changed completed capture.
An engine failure is explicit and leaves the other engine's files available.

PhotoRec retains its 256 MiB input limit and existing header/data-check scope.
Binwalk retains its separate 64 MiB whole-input limit. Above 64 MiB, the gallery
explicitly skips Binwalk and scans the complete supported input with PhotoRec;
it does not substitute an undisclosed prefix scan. These are input budgets,
not bounds on total native/Java memory or guarantees of completion.

`ContentMap` merges only matching start, reported length and normalized format.
Every original engine/row/range witness remains attached. An unknown-length
finding at the same start and type accompanies a bounded file without gaining
that file's boundary. Distinct reported lengths remain alternative file cards.
Nested and overlapping files remain available. Coverage and outside-artifact
text runs use the union of both engines' bounded ranges.

For example, PhotoRec accepts the generated gzip header without supplying a
length, while Binwalk reports its complete stored stream. The combined gallery
can save and browse that gzip file using Binwalk's boundary and retains
PhotoRec's original null length in the attached findings. Neither record is
rewritten to imply agreement about a boundary.

Complete map JSON retains the original PhotoRec `nativeEvidence` and
`nativeCatalog` fields and adds `binwalkNativeEvidence`, `binwalkNativeCatalog`
and per-engine completion/failure/capture provenance. An unsuccessful engine's
report is null, with its diagnostic retained. Existing PhotoRec-only sessions
restore their original scope without adding a new Binwalk scan.

## Payload exports

`RecoveredFiles` owns file naming, MIME selection and payload export. Names are
generated from source range and a constrained format extension; an untrusted
native label never becomes a directory path. JPEG aliases become `.jpg`, gzip
becomes `.gz`, and printable UTF-8 runs become `.txt`. Names describe recovered
bytes and do not claim to reconstruct an original filesystem filename.

Individual saves check the whole captured source identity before and after
copying the exact range. Gallery, native finding and preview exports preserve
format extensions and MIME types through the document picker. Unsupported
formats remain exportable as exact bytes.

ZIP exports include actual payloads, not just findings or header locations.
Each safe flat entry has its own SHA-256 in `_recovery.json`, source coordinates
and every attached discovery. Duplicate file cards are deduplicated while their
witnesses remain in the manifest. The bundle is limited to 4,096 distinct files
and 240 MiB total uncompressed payload; binary staging has a separate 256 MiB
encoded cap. Budget, cancellation and changed-source failures remove unfinished
stages. Prepared ZIPs and individual exports survive source expiry and picker
recreation. Provider write failures remain visible.

**Files** excludes unassigned gaps and unknown-length headers. Text runs are
exact candidate text spans, not inferred original text-file boundaries. Other
view groups preserve header-only and unassigned evidence. Whole-input/manual
byte exports remain accessible through the existing views and actions.

## Verification and remaining work

Host integration uses both real native engines over concatenated JPEG, PNG,
WAV, GIF, gzip, MP4 and Unicode text, independently generated or retained owned
fixtures. Single saves and ZIP payloads match every original byte. Independent
Python reconstructs the ZIP and checks every native range witness against the
original report. Other checks cover duplicate findings, alternative lengths,
unknown extents, per-engine failures, capture corruption, source expiry,
cancellation and mutation cleanup.

Android host tests exercise real native discoveries with substituted worker IPC
and document streams. They check direct file-tile preview, PNG MIME/filename/
bytes from both tile and preview save paths, and a 29-image ZIP spanning multiple
gallery pages, including discovered nested compressed payloads. Original
header-only/manual/archive/grid cases remain covered. The fully written
80 MiB + 17 byte fixture checks a PNG beyond 64 MiB, its exact typed export,
Unicode tail, whole-input identity and complete entropy grid while Binwalk is
explicitly skipped.

The release checklist records full-suite/build/CI results separately. Existing
actual x86 Android media-helper and worker checks do not establish full gallery
or SAF acceptance. Production ARM64, handset UI/providers, all media/document
formats, complete PhotoRec file checks, fragmented recovery, MPV playback
fallback and recursively recovered archive-member galleries remain incomplete.
