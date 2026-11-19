# Captured native scan-tree comparison

Open a saved desktop batch, tap an input or native detection row, choose
**Actions → Compare native report with #…**, then select the other captured input.
The original ordinals disambiguate duplicate display names. Any two stored native
reports in the same batch can be selected, including a failed native request.
Acquisition failures with no stored report are not offered as invented scans.

This tool compares returned CDiE tree records. It does not diff source bytes,
instruction semantics, package declarations, generic previews or every report
field. The separate binary comparator supplies aligned byte evidence. External
report import, cross-batch selection and broad package/structure/semantic
comparison remain incomplete.

## Matching and original evidence

`ScanComparison` captures immutable native rows and source/options/provenance
contexts from both checked `org.diemobile.scan-report` envelopes. All row fields
except the root row's numeric `parent` position participate in exact matching.
Object keys are canonically ordered; JSON scalar types, array order, every decoded
UTF-16 unit and unknown fields remain significant. Numeric spelling follows the
existing JSON value encoder; original report files retain their separate byte
identities. There is no case folding, Unicode normalization or inferred compiler
family/version equivalence.

An interned path includes the exact parent path, complete row fields and occurrence
order among identical siblings. Matching uses exact strings and parent identities,
not a digest-only approximation. Ordinary sibling reorder and resulting parent
index shifts do not change otherwise identical paths. Identical sibling groups
retain separate occurrences: their children cannot silently migrate between
collapsed parents. Insertion/reordering of indistinguishable duplicate groups can
change child alignment. This deterministic occurrence policy is explicit; it does
not establish semantic object identity.

Tables expose matched, left-only, right-only, differences, uncompared and all
records. Left-side order is retained, followed by previously unmatched right-side
rows in their original order. Each record includes both original row indices,
depths and complete raw native rows, or an explicit null for an absent side.
Every returned occurrence is accounted for. Actions jump back to the exact
original left/right native row or open either complete original report. Aggregate
comparison records have no physical file offset. Original input byte views retain
their separately checked ownership.

`left-only`/`right-only` means that a record path was not returned in the other
captured tree. It does not establish file absence, execution, malicious behavior,
vulnerability status or a causal explanation. If either scan failed, comparison
is not performed: successful-side records remain **uncompared**, with zero
left-only/right-only change counts. A failed/capped scan is unknown coverage,
never an empty negative finding. A successful CDiE request can still have upstream
format/archive/heuristic coverage limits.

## Context and bounds

The summary retains both original report creation times, sources, full captured
options, engine/database provenance, native top-level metadata/diagnostics and
comparison time. The enclosing batch ID and each input's stored-report
SHA-256/length identify the exact compared captures. Full original reports remain
available separately; comparison does not silently substitute current preferences.

`conditions.sameCapturedInputIdentity` compares recorded length/SHA-256 values;
`inputBytesReRead` is false. The report file itself is length/SHA-256 checked by
the batch store before capture. Native options, preview minimum, engine/signature
revisions, declared custom archive and effective enabled custom database are
separate conditions. `configurationDiffers` concerns native request/rule/engine
conditions: a changed preview string minimum or changed disabled custom archive
alone is not presented as a native execution difference. Revision equality is a
captured identity comparison, not proof of identical platform/runtime behavior.

| Limit | Policy |
| --- | --- |
| Native rows | 20,000 per tree, checked before typed indexing |
| Canonical row fields | 8 Mi-characters total, bounded while writing |
| Opaque field nesting/nodes | 64 levels / 1,000,000 nodes across both trees |
| Tree ancestry | Iterative parent identities; a 20,000-level tree does not recurse or copy complete paths |
| Processing | 30-second cooperative budget and cancellation |
| Source reports/export | Existing 16 MiB/report store and 256 MiB staged export bounds |

Exceeded limits reject the comparison rather than returning a successful prefix.
Only one pair is cached per viewer. Source report identities are checked when
capturing/rebuilding that pair; cached immutable observations can outlive later
source expiry. Original-row/report actions still validate the original stored
report. Recreation recomputes from checked retained reports; corrupt/missing
reports fail explicitly instead of producing a zero-difference table.

## Schema, lifetime and verification

`org.diemobile.scan-comparison` version 2 uses
`exact-native-tree-paths/v1`, both complete comparison contexts, source-report
identities, counts, condition/duplicate disclosures, bounds and every original
comparison `record`. Shared evidence tables provide selected scopes and records.
Complete comparison exports stream all records independently of filter/page,
preserve exact UTF-16 and stage before the destination picker. Session deletion,
changed preferences and recreation cannot replace an already prepared export.

`bash scripts/test-scan-comparison.sh` compares the implementation against an
independent explicit-ancestor-list oracle on random trees. Fixtures also cover
reordered indices, duplicate parent/leaf multiplicities, changed ancestry, opaque
object/array/type/Unicode fields, immutable contexts, relevant versus preview-only
settings, failed-scan unknown coverage, cancellation/recovery, malformed parents,
row/character/nesting limits and full export of a 20,000-level tree.

Three added API 28 cases run actual CDiE/Inspector batches through real widgets.
They verify identical captured trees, original-row/owned-source navigation and
Back/recreation; complete filtered export through session deletion/settings change/
recreation; and same-length stored-report corruption, explicit failure and recovery.
The batch viewer now has ten cases and the complete suite has 108 checks. Only
external document streams are substituted. Inspect current CI for the committed
revision; compilation/host fixtures do not establish real SAF, handset, process
death, low-storage or power-loss behavior. Full Qt/native heuristic equivalence
and program-level semantic comparison remain unverified/incomplete.

Batch interpretation captures are retained in each comparison context. An
unresolved requested interpretation has a null native selector and failed
coverage; it does not become an empty successful scan or a matching known
selector. Original request/catalog/source metadata remains exportable.

Schema 2 adds `nativeSelectorsResolved` and preserves each optional `scanTarget`
context. When a selector is unresolved and other known fields agree,
`sameNativeOptions`, `sameEffectiveOptions` and `configurationDiffers` are null
rather than claiming either equality or difference. A known flag/tag/engine/rule
difference remains a known difference even if a selector is unresolved. The
matching algorithm remains `exact-native-tree-paths/v1`; no old comparison files
are imported or rewritten.
