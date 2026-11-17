# Scan report schema v1

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
