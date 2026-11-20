# Gallery archive links and explicit inspection ranges

Every Gallerize card has an actions menu, including header-only findings.
Bounded cards retain in-app viewing, exact stored-byte export and all existing
range tools. Header-only cards offer their native witness, Hex, explicit range
selection and archive inspection without inventing a recovered size.

**Inspect archive / compressed stream here** opens the native browser with a
checked `ArchiveLaunch`. For an original native finding, its observed start
defines an explicit parsing tail. The original known/null reported extent stays
unchanged. For a whole-input, text/gap, grid or manually selected interval,
the browser receives that exact chosen interval. A parsing interval is not a
recovered-file boundary. Native content identification remains independent of
the source filename.

The launch retains the complete original card, gallery provenance and associated
native row, when present, in `galleryContext`. Current source SHA-256 is checked
around construction. Original native-card geometry must match its retained
witness; stale/changed projections are refused before archive parsing. The native
browser retains this context through scope changes/restoration, complete reports
and the independent preview origin capture. Selected-member results use the
existing checked private handoff.

## Manual inspection bytes

**Choose inspection byte interval…** accepts decimal or `0x` hexadecimal offset
and length. The dialog shows the captured input size and the existing 256 MiB
preview budget. Defaults select the card's reported interval or a bounded window
from an unknown-extent card's observed start. These editable values are displayed
before acquisition; they do not become the native finding's extent.

`ContentMap.Card.inspection` validates a nonempty in-bounds interval and the
operation budget. The separately owned preview retains the exact chosen bytes,
parent SHA-256/offset and an explicit inspection label. Automatic/manual viewing
modes and checked byte exports remain available. An associated original finding
is retained even when the selected range starts elsewhere or has a different
length. The origin states this relationship instead of claiming a native
recovered extent. Grid-cell preview origins now carry the same explicit-range
meaning. A first decoded image/frame does not verify a complete media file.

Malformed, negative, empty, over-budget and out-of-bounds intervals fail visibly
and leave the gallery usable. Cancel/destination/source-lifetime behavior uses
the existing ownership mechanisms. The dialog itself is not restored after
recreation; an acquired preview has its independent lifecycle.

## Verification

`bash scripts/test-gallery-ranges.sh` compiles and calls the actual pinned
PhotoRec and libarchive JNI libraries in one JVM. An independently generated
gzip behind a 37-byte prefix produces a real unknown-extent gallery finding,
a checked parsing tail and the exact independently expected decoded member.
Tests preserve null extents, explicit-versus-associated geometry, complete
gallery/row context, UTF-16 names including an isolated surrogate, source expiry,
restoration without rescan, complete origin export and stale/cancel/budget
refusals. Python independently reconstructs gallery/origin JSON and UTF-16 units.
Gallery report headers/cards now use the lossless shared JSON writer too.

The existing `scripts/test-gallerize.sh` passes the real four-file/text fixture,
complete evidence/catalog exports and fully written **80 MiB + 17 bytes** case,
including an exact PNG beyond 64 MiB and the full byte/entropy grid. Three new
API 28 widget cases cover actual header-only actions/launch requests, manual
hexadecimal coordinates with exact owned bytes/unchanged witness after parent
expiry, and invalid-coordinate recovery. Existing recovery/archive cases remain
part of the regression suite.

These checks do not establish actual Android UID/Binder/SAF/media/PDF/
process-death/16 KiB runtime acceptance. Arbitrary deobfuscation, passwords,
universal archive/media coverage and inherited origins across later independent
investigations remain incomplete. See [archive launches](archive-launches.md),
[origins](content-origins.md) and [Gallerize](gallerize-implementation.md).
