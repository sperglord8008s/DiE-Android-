# Local report schemas v1

## Lazy investigation tables

`org.diemobile.evidence-table`, `schemaVersion: 1`, is the streaming table schema used by the DEX viewer. It preserves `kind`, `title`, `rowCount`, `scope`, `createdAt`, provider `metadata`, engine/input `provenance`, and every `rows` record in the selected table. `view.filter`, zero-based `view.page`, and `view.pageSize` describe the captured display; they never restrict the exported rows. A single-record export identifies its original index in `metadata.recordSelection`. Physical offsets refer to the selected input snapshot; provider fields distinguish identifier records, encoded content and associated locations. DEX containers and compressed archive members have explicit separate address domains/provenance. See [DEX contracts](dex-investigation.md).

Reports are staged before the document picker with a 256 MiB encoded UTF-8 budget. Unpaired UTF-16 units are escaped in JSON, and malformed unescaped text fails instead of silently substituting bytes. Failure/cancellation does not return a successful partial report.

Metadata providers add complete streamed values alongside explicitly bounded interactive fields: `fullRawValue` for tag/value records, `fullEngineDescription` for tags, and `fullPropertyValue` for XMP nodes. Selected-record exports invoke the same provider writer. This permits full binary Base64/array/text data without a second complete JSON DOM. Physical tag offsets remain null when unknown; `valueByteOffset`/`valueUtf16Offset` identify positions inside a returned value. See [metadata contracts](metadata-investigation.md). String/DEX tables retain exact original values and classification spans; group selection defines the export scope.

## Scan report

The primary Scan screen exports an object with `schema: "org.diemobile.scan-report"` and integer `schemaVersion: 1`. A consumer should check both before interpretation. Export is an explicit, local Storage Access Framework operation; the application does not upload reports.

The complete displayed analysis and captured scan options are serialized into an
owned private report **before** opening the document picker. Later source/view
changes cannot replace that report. Its default filename includes the first
twelve input SHA-256 digits. Pending report bytes survive activity recreation
and can finish even when the source or current scan result has expired. Picker
cancellation deletes the stage; destination failure reports possible partial
provider data and deletes the private stage. Preparation observes lifecycle
cancellation and has a 256 MiB encoded limit, depth 256 and two million JSON
nodes; failure exposes no successful prefix and does not open a destination.

UTF-8 JSON preserves every decoded UTF-16 code unit in names, keys, metadata and
native fields. Controls and surrogate units are escaped explicitly; isolated
surrogates are not replaced by `?` or U+FFFD. This is exact decoded identity,
separate from original encoded file bytes. JSON whitespace/pretty indentation
is not part of schema v1. The streamed encoder avoids a second complete rendered
report string and uses direct hexadecimal escapes for predictable per-unit work.

| Field | Meaning |
|---|---|
| `createdAt` | Captured report preparation time in ISO-8601 UTC, before picker delay; not the binary's creation time |
| `source` | Display name, exact `sizeBytes`, SHA-256 of the private input snapshot, and independent header identification |
| `options` | Captured desktop flags and enabled names, semicolon-separated include/exclude tags, automatic `typeId: 0`, and minimum preview string length |
| `provenance` | CDiE revision, Detect-It-Easy database revision, custom database enabled state and original ZIP SHA-256, elapsed milliseconds including acquisition/inspection, and `networkUsed: false` |
| `scan` | Native JSON object with `ok`, full `rows`, parser facts, engine log, flags, loaded signature count, in-process database generation and cumulative cache counters; a failure has `ok: false` and `error` |
| `facts` | Native format facts, a native unsupported/error response, or null if unavailable; the native flags state which fields are meaningful |
| `engineError` | Acquisition/binding/configuration or parser error that prevented a complete native scan |
| `inspection` | Whole-file entropy in bits/byte and bounded block-entropy, ASCII and hex previews |
| `explanations`, `apkAnalysis` | Contextual narrative reports; these are not independently typed evidence tables |
| `limitations` | Explicit limits of interpretation, backend parity, process isolation and coverage |

Each scan tree row preserves its native `text`, `category`, zero-based row position and `parent` index (`-1` for a root). A detection may have `name` and `signature`; group rows do not imply a rule ran. The model rejects invalid parent indices rather than dropping records. Search and 50-row display pages retain the complete model and export every returned row. Native output is capped at 20,000 rows / 8 MiB; exceeding that cap fails with an error rather than returning success with lost records.

`signature` is a database script source path. It is **not** a byte-match offset. Tree text preserves upstream labels, heuristic markers, versions and group offset domains. Compressed-member offsets may refer to the decoded member and cannot be used to navigate the parent file. `facts.entryOffset` is a physical file offset when nonnegative; `base` and `entry` are hexadecimal virtual-address strings, preserving unsigned 64-bit values. Zero-valued facts are not necessarily available facts: consult the native `flags`, format and error state.

Cache counters are process-wide diagnostics, not proof that a particular rule executed. A cached result remains evidence for matching input bytes and options; it is not a fresh execution profile. SHA-256 identifies the analyzed content; the display name is not a trusted identity. Reports may contain filenames, signatures, strings, package metadata and certificates from the input, so the user controls their destination.

`provenance.customDatabase.archiveSha256` identifies the exact originally imported ZIP, including its helpers, tag metadata and license notices. An empty string means no managed custom snapshot identity is available; it does not prove a legacy custom folder is empty. `enabled` records whether custom rules contributed to the configured scan. The in-process native database generation is useful for diagnosing reloads but is not a persistent database identity.

The primary UI shows loader base/entry virtual addresses for PE, ELF and thin Mach-O pages. Generic Binary, archive and DEX facts can contain default zero address fields; these do not establish a loader address or entry point. Physical entry navigation additionally requires native `GUI_FACTS_ENTRY_OFFSET` (`flags & 16384`) and a nonnegative offset. Segmented DOS addresses and other address domains require their own interpretation.

## Native tool report

Native Tools exports `schema: "org.diemobile.tool-report"`, `schemaVersion: 1`. It stages a private immutable JSON file before opening the local system picker. Changes to filters, controls or the Activity after preparation cannot change that pending export.

| Field | Meaning |
|---|---|
| `createdAt` | Report preparation time in ISO-8601 UTC |
| `input` | Display `name`, exact `sizeBytes`, nullable whole-input `sha256` and `identitySource`; the digest comes from the parent inspection, not a new whole-file hash performed by the tool |
| `request` | Captured `toolId`, native `mode`, `physicalOffset` (`-1` means automatic), `minimumStringLength`, `selector`, `nativeJson` and `attSyntax`; draft controls cannot relabel these results |
| `engineRevision` | Bundled CDiE source revision, or empty when not available |
| `signatureDatabaseUsed` | `false`: native table/byte tools do not run signature rules |
| `elapsedMs` | Time for that tool request and its provenance lookup |
| `view` | UI filter and zero-based page at preparation, plus an explicit all-returned-rows export scope |
| `nativeRecords` | Validated original [native record snapshot](native-records.md), including native failure responses; null for legacy/Java tool output or a binding/cancellation failure |
| `rawReport` | Legacy text/JSON output or explicit error text when `nativeRecords` is null |

Tool IDs: 0 structures, 1 memory map, 2 imports, 3 exports, 4 symbols, 5 resources, 6 disassembly, 7 hashes, 8 Unicode strings, 9 hex, 10 native entropy regions. Native modes retain their upstream numeric encodings. Fields irrelevant to a particular tool can still be captured and do not imply that tool used them.

Search and row paging never reduce the exported snapshot. Disassembly's `instruction-page` still covers one native decode window, with `metadata.nextOffset` identifying continuation; it is not a complete-file disassembly. Structured failures retain `ok: false` and `error`. A legacy `rawReport` may itself contain JSON; consumers should not treat it as a typed native table.

The parent input is a private snapshot in normal app use. A matching file length alone cannot revalidate a prior SHA-256 after external same-length modification. `identitySource` discloses whether an inherited identity was available; null means uncollected, not a known empty digest. Source/range bytes are never uploaded.

## Physical range report

`schema: "org.diemobile.range-report"`, `schemaVersion: 1` records a newly measured half-open physical interval, independent of any native format interpretation.

| Field | Meaning |
|---|---|
| `inputName`, `inputSha256` | Display name and nullable inherited parent-inspection digest |
| `fileSizeBytes` | Expected source length checked before and after range I/O |
| `physicalOffset`, `lengthBytes` | Exact stored interval `[offset, offset + length)`; validated against source bounds and the 256 MiB operation budget |
| `rangeSha256`, `rangeCrc32` | Digests measured from exactly those bytes, as lowercase hex strings |
| `entropyBitsPerByte` | Shannon entropy of the selected byte histogram; zero for an empty range |
| `interpretation` | Explicit reminder that entropy is not a behavior/security verdict |

Native resources can also export their exact stored bytes directly as `application/octet-stream`. The export does not decompress content, interpret code pages, synthesize virtual tails or infer an unknown symbol extent. Cancelled/failed destination writes may leave partial provider data and are reported as such.

APK signing evidence uses the shared `org.diemobile.evidence-table` schema plus `org.diemobile.apk-signing` source provenance version 1. It distinguishes combined supplied-input verification from raw apksig aggregate flags, retains every scoped record and original identity, and never assigns derived certificate/lineage data an invented APK byte offset. See [signing tables and exact binary exports](apk-signing-investigation.md).

Captured package-set tables also use `org.diemobile.evidence-table`, with `org.diemobile.package-set` source provenance version 1 and an independently hashed `org.diemobile.package-input-index`. Every row preserves source/table/original ordinals and streams its full captured record regardless of preview/filter/page. Aggregate coordinates stay null; APK and expanded-manifest byte views select independent owned sources. See [package-set contracts](package-set-investigation.md).

PhotoRec uses native `org.diemobile.photorec-evidence` version 2 plus the shared `org.diemobile.evidence-table` wrapper. `photorec-patterns` retains every original pattern and explicit searched/excluded policy; `photorec-candidates` retains every searched occurrence and raw header/data state. `headerAccepted` is separate from a positive bounded data-stop extent. Negative derived starts retain the magic location with null `candidateStartPhysicalOffset`, labelled `physicalOffsetMeaning` and no extractable length. Complete source identity/configuration/limits accompany filtered or selected exports. See [PhotoRec contracts](photorec-investigation.md).

`org.diemobile.photorec-capture`, version 1, is the complete indexed capture.
`provenance.input` retains owned source name/size/SHA-256; `engineReportMetadata`
retains native schema/configuration/counts/limits, and `patterns`,
`rejectedRecoveryStates`, `rows` retain every original indexed record. In native
schema 2 an ordinary rejected row is `[patternIndex, magicOffset, recoveryStateIndex]`;
its physical start/window sizes follow from the catalog/input and its raw
state follows `rejectedRecoveryStateFields`. Other rows are original objects.
Row order is unchanged. Reconstruct the native packet by combining metadata
with the three arrays. Expanded evidence-table exports retain all named fields.
Both forms preserve every occurrence; capture staging is independent of
filter/page and survives pending-picker recreation/source expiry.

Saved option catalogs use `org.diemobile.scan-profiles` version 1 and `org.diemobile.cdie-options/v1`. UUID-keyed records retain original metadata and exact numeric/UTF-16 values; effective settings follow the existing ScanOptions contract. Catalog exports preserve captured source bytes/SHA-256 identity across later edits and picker recreation. A scan report still records its actual captured options and engine/database identities; it does not yet attribute a named profile ID. See [profile contracts](scan-profiles.md).

Desktop batches use `org.diemobile.desktop-batch` version 1: one captured option/engine envelope plus every planned ordinal input/outcome, independent whole-input/report identities and a complete nested `org.diemobile.scan-report` or explicit null. The evidence-table viewer retains original native rows and source ordinals; aggregate coordinates are null and selected input views own their own bytes. Full batch/individual reports stage before the picker and reject stored-report corruption. See [batch schema, bounds and lifecycle](batch-scanning.md).
