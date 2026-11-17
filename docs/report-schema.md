# Local report schemas v1

## Scan report

The primary Scan screen exports an object with `schema: "org.diemobile.scan-report"` and integer `schemaVersion: 1`. A consumer should check both before interpretation. Export is an explicit, local Storage Access Framework operation; the application does not upload reports.

| Field | Meaning |
|---|---|
| `createdAt` | Export time in ISO-8601 UTC; not the binary's creation time |
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
