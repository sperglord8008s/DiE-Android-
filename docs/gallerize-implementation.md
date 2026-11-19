# Gallerize: content map, previews and exact grids

The **Gallerize** tab next to File info opens a content-first view of the selected
input. It accepts up to 256 MiB, copies/identifies the exact selected bytes, and
uses the existing isolated-worker PhotoRec adapter. Original accepted headers,
reported bounded artifacts, nested/overlapping ranges and every unassigned gap
remain distinct. The summary puts primary byte-header type and observed
composition first. Multiple separate reported artifacts are explicit; neither
header recognition nor full range coverage proves homogeneous content.

## Evidence and ownership

`GallerizeSession` owns the input and complete original native JSON capture with
separate SHA-256 identities. The carving backend borrows that owned input,
avoiding a second file-sized acquisition. Its original validation, 774-pattern
catalog, 581 every-byte searches, weak-pattern exclusions and full-file-check
limits remain unchanged. Restoration verifies both original identities and
rebuilds the view from the same captured report, without rescanning. Parent
source expiry therefore does not relabel or regenerate evidence. Changed or
missing captures fail explicitly.

`ContentMap` keeps each accepted native row's original index and exact reported
physical range. Unknown lengths remain null and enable no automatic carving.
Rejected/raw findings are retained in the complete export; each native card can
show its exact original witness. The separate PhotoRec tool starts its own new
capture when selected. Native decoding/data-stop evidence is not a complete
integrity, provenance, safety or behavior verdict.

Reported bounded ranges are unioned only to compute coverage and unassigned
gaps; individual duplicate/nested/overlapping cards are retained. Strict
printable UTF-8 runs of at least eight code points in those gaps become text
candidate cards. CR/LF/tab are accepted; invalid/overlong/surrogate encodings,
controls, BOM and runtime-unassigned code points separate runs. Unicode
classification uses the runtime's Character tables, not a claimed frozen Unicode
version. A 512-code-point preview is explicitly bounded while the original byte
extent stays complete. Text inside reported artifacts, UTF-16, other encodings
and classified indicators remain available through the existing Strings tools.
A text candidate is not an inferred text-file boundary.

The map has a 50,000-card limit. Budget/cancellation/parser failures publish no
successful partial map. Source/grid tools remain available when acquisition
succeeded; the separate raw PhotoRec/Strings tools have their own limits. The
native packet is bounded to 32 Mi-characters / 64 MiB encoded storage, in addition
to the native adapter's stricter actual report/work limits. Complete map exports
stream every card, every raw native finding and the entire native catalog,
independently of gallery page/group. They stage before the document picker.

## Gallery and local previews

The labeled grid pages 24 cards, with image, audio/video, text, archive, document,
unassigned and header-only groups. `ContentGalleryAdapter` bounds thumbnails to
256 × 256 pixels and deduplicates requests across recycled tiles. Decode failures
are visible. Source names never become extraction paths.

Selecting a known range can preview it, show original-offset hex/native evidence
or export its exact bytes. Preview acquisition rechecks the complete source
identity and makes a separate owned SHA-256 input, retaining original source hash
and offset. `ContentPreviewActivity` survives parent closure/source expiry and
recreation using that owned range. It offers automatic and manual image,
audio/video, UTF-8, PDF and hex modes, plus metadata/MPV/string/archive/native
investigation of the same derived bytes.

Implemented renderers are bounded platform image decoding (first frame, at most
2048 × 2048 pixels), strict UTF-8 (1 MiB read preview), hex (4 KiB preview), paged
PDF (1536-pixel maximum dimension) and local descriptor-based MediaPlayer
controls. Playback requires Play, pauses on leaving the screen and exposes
platform errors; no inspected app is executed and no input URL is forwarded.
The app still has no INTERNET permission. MPV probing remains the separately
pinned, protocol-restricted isolated engine; this platform playback wiring is
not a claim of complete MPV playback or codec support. Animations, video
thumbnails, additional document renderers, MPV playback fallback and hard
isolation of platform/Java preview parsers remain incomplete. Device decoder,
PDF rendering, audio focus/platform lifecycle and real media playback remain
unverified where host transport/shadows cannot supply those runtime semantics.

## Byte and entropy grids

`ByteGrid` offers mean unsigned byte value (0–255 grayscale) and local Shannon
entropy (0–8 bits/byte, blue to yellow), with explicit legends. Every source byte
participates; no prefix or pixel sampling is substituted for a whole-input grid.
The chosen width × height partitions bytes in row-major order. For
`used=min(input length, width*height)`, populated cell `i` is:

```
[floor(i * length / used), floor((i + 1) * length / used))
```

Trailing unused cells are magenta with no value/range. An empty input has no
populated cell. One-byte entropy is zero, regardless of the byte's value.
Colors are measurements, not format/packing verdicts or a decoded image claim.
Each cell opens exact original-offset hex or an owned-range preview. Grid
exports include every cell/range/value, exact IEEE hexadecimal values and the
captured source identity.

Automatic byte layout uses one byte per cell when possible and selects an exact
familiar square such as 640 × 640 for a matching length; entropy targets about
1 KiB/cell. Common square/rectangular presets and arbitrary dimensions remain
available, with persistent manual overrides and an explicit return to Auto.
Bounds are 1–2048 per dimension and 1,048,576 total cells. Measurement streams
64 KiB reads with one 256-bin local histogram; result arrays, bitmap/runtime and
input/capture storage are separate memory costs.

## Verification and continuation

`scripts/test-gallerize.sh` uses actual PhotoRec JNI. Four concatenated PNG/JPEG/
WAV/GIF files plus Unicode text have independently checked exact ranges and
owned bytes, full findings/catalog exports and original-capture restoration.
Corrupted capture identity is refused without silently rescanning. Strict text
IO seams, long preview/full-range distinctions and independent byte means/
log2 entropy/complete partitions/grid exports are checked. A fully written
**80 MiB + 17 byte** fixture retains a PNG beyond 64 MiB and Unicode tail text;
the complete grid/source hash agrees with independent streaming SHA-256.

Eight Android host cases exercise actual native/model/widget paths: labeled
cards and image thumbnails; manual/preset grids and aspect-preserving touch-to-
hex coordinates/recreation; full filtered delayed exports; owned Unicode
previews through parent closure/recreation; image/manual-decoder failure and
recovery; local explicit playback/pause/error wiring; and cancellation/failed
output cleanup. MediaPlayer runtime and document output streams are substituted;
these checks do not establish handset decoder, isolated UID/Binder, real SAF,
process-death/storage pressure or API 26/35/16 KiB runtime behavior. Android CI
also compiles ARM64 and runs lint/static APK checks.

The broader source-built archive engine, richer animation/video/document
presentation, automatic sharing of composition observations with the primary
scan report, full device acceptance and the later sourced “What does it do?”
knowledge cards remain active work. ZIP/APK hierarchy and selected member
acquisition remain available now. Header recognition is not broader archive
decompression or universal obfuscation/encryption recovery.

Archive member handoff now receives the browser result from both the gallery
and a derived preview, checks the owned leaf directory and exact member SHA-256,
acquires an independent preview and releases the browser temporary. Derived
archive members explicitly have no asserted original physical offset. A real
outer-ZIP → nested-ZIP → Unicode-text UI case checks both handoffs, original
parent identities, source expiry and rejection of a forged external path.
Automatic preview also recognizes a strictly decoded printable UTF-8 header;
manual modes remain available. Unused grid cells use magenta to distinguish
them from valid grayscale byte measurements.
