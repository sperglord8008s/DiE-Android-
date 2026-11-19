# Gallerize and content-first investigation: active development requirements

These are accepted user requirements. The [implemented first content/gallery/grid
slice](gallerize-implementation.md) records its precise verification and limits;
this requirements list is not a claim of complete implementation or device
verification. Existing binary, Android, metadata, rule and export functions must
remain accessible throughout the reorganization.

- Put primary file type, composition evidence and useful observations first.
  Combine the redundant File 101 / Reference presentation, retain complete
  explanations, and provide individual plus global expand/collapse controls.
- Group tools by purpose. General bytes/strings/hashes, media, contents/carving
  and Android investigation precede ancillary executable-format tools. Preserve
  every existing native mode and expert option.
- Add **Gallerize**: digest the entire supported input and show labeled content
  cards for media, text spans, archive contents and other observed artifacts.
  Preserve exact original offsets, extents, native witnesses, overlap/nesting,
  uncertainty and source/derived identities. Never equate a magic hit or an
  unknown extent with a completely recovered file.
- Preview supported images, animation, audio/video, text and documents in-app.
  A gallery must work for selected whole files and bounded embedded resources.
  Decode failures remain visible. Inspected applications are never executed.
- Offer byte/entropy grids with automatic sensible layouts and explicit common
  dimensions such as 640 × 640, arbitrary bounded dimensions, legends, exact
  cell/range navigation and alternative modes. Automatic presentation is an
  aid; users can choose and retain other views/settings.
- Show archive hierarchy, including nested selection. Add a source-pinned FOSS
  archive engine for substantially broader formats and decompression. Detect
  contents independently of filename/extension, investigate embedded and
  supported disguised forms, preserve original bytes and explicit transform
  provenance. Unknown obfuscation, encryption and unsupported methods must not
  be presented as universally solved. No external automatic extractor commands,
  path-based archive writes, symlink materialization or sample execution.

The concrete end-to-end acceptance fixture is several concatenated files plus
text. Gallerize must identify/display the supported artifacts, preserve their
original ranges and text, and retain every relevant raw finding. Separate
fixtures cover mislabeled archives, nested/overlapping resources, malformed
inputs, source expiry, cancellation, complete exports and manual overrides.

Implementation will be committed in coherent tested slices. The feature-parity
checklist and continuation notes must distinguish the parts actually implemented
from these remaining requirements and from real Android verification.

## Following priority: What does it do?

After the content/gallery/archive work, add linked knowledge cards that connect
practical descriptions with technical mechanisms, original sources and concrete
analysis evidence. Support attributed, versioned community contributions alongside
package/component/SDK/service/API observations and relationships. Preserve source
versions and confidence, distinguish declarations/capabilities from observed use,
and let a reader move from a plain-language role to full technical references.
This is an investigation/understanding workbench; package-removal operations are
not part of this request. Existing UAD, LibChecker and tracker data are starting
sources, not substitutes for evidence about a particular installed version.

## Recovery workflow and interface direction (20 November 2026)

The maintainer explicitly prioritizes Binwalk/PhotoRec recovered-file exports,
their gallery/preview handoff and consistent formatting. Preserve the dense,
desktop-inspired workbench: aligned facts, clear headings, compact purposeful
controls, monospace for actual data and expandable proportional explanations.
Avoid ornamental cards, oversized rounded controls, decorative color systems
and appearance settings that obscure analysis actions.

Next functional acceptance: a bounded native finding can be acquired from its
checked parent identity, viewed locally through the shared owned preview,
opened in Gallerize, and exported without losing its original source range or
witness context. Unknown extents remain visible and non-extractable. Preserve
all complete raw/native/catalog exports and their staged-picker lifetime.
This acceptance is pending beyond the current release identity slice; existing
stored-range export and Gallerize preview coverage remains documented separately.

The checked range/preview/export and private whole-input handoff subset is now
implemented and host checked in the recovery slice; see
[recovered-content contracts](recovered-content.md). A complete persistent
per-preview native-origin sidecar and broader investigation history remain
pending; parent tool reports retain their complete native evidence.
