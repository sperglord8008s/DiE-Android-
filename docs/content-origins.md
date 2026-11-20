# Independent preview origins

Recovered content previews can now own their discovery and acquisition evidence
beside the exact selected bytes. The identity block shows the relationship,
capture size and SHA-256. **Origin / captured evidence** opens the complete
capture with bounded text navigation and **Export complete text**. The byte
export remains separate. Closing the evidence dialog keeps its capture; leaving
the preview releases its owned bytes and origin. Configuration recreation
retains both. A prepared origin export survives later source/capture expiry and
the existing document-picker recreation path.

## Captured scope

| Entry path | Evidence retained independently of its parent |
| --- | --- |
| Binwalk / PhotoRec bounded finding → preview | Complete original selected row JSON, original native index/kind, captured provenance JSON and table metadata JSON, exact parent SHA-256/size and physical range. This is the selected finding's evidence, not a duplicate of every other scan row or the entire rule catalog. |
| Gallerize card → preview | Full card and gallery provenance, plus the complete original PhotoRec row when the card has one. Text runs, unassigned regions and whole-input cards retain their actual card meaning; their ranges do not become inferred recovered-file boundaries. |
| Native archive member → preview | Every original inspection report in the current nested archive chain, capture hashes, names and parent indices; original nested acquisition reports; retained failed attempts; original embedded-finding launch context; and the selected member's full row and acquisition report. Logical derived members have no asserted stored physical offset. |

Native preview results carry only a compact private descriptor through the
Activity result; large witnesses stay in an owned file. The shared result
launcher validates the selected bytes and sidecar before transferring ownership.
The native browser requests this result for Gallerize, recovered-finding and
preview callers. Its direct preview also captures evidence. The main screen's
**return and rescan** path and Classic ZIP fallback do not yet carry full origin
sidecars. A preview without one states that no full discovery capture accompanied
it; a failed identity check states that its origin is unavailable while leaving
independently checked content viewing available.

The native chain includes the currently retained path and failure records, not
all previously visited branches. Cross-Activity ancestry from an older preview
into a newly launched investigation is not yet inherited. Keep the earlier
origin export when following such a separate investigation. No archive parsing
tail is substituted for a reported recovered extent.

## File and ownership contract

`ContentOrigin` writes UTF-8 JSON Lines (`application/x-ndjson`): one `capture`
identity/relationship header, complete `witness` records containing `kind` and
`value`, and an `end` record with the witness count. `schema` is
`org.diemobile.content-origin`, version 1. Raw native JSON strings are retained
as strings, including unknown fields and original spellings; consumers can
parse those separately. `EvidenceJson` preserves every UTF-16 unit, including
isolated surrogates. **LF** separates records; Unicode U+0085/U+2028/U+2029 inside
JSON strings are data, so general Unicode `splitlines()` is not a record parser.

Bounds are explicit: 8,192-character identity header, 128 witness records and
256 MiB encoded capture/export. Exceeding them or failing a producer refuses the
combined acquisition and deletes its partial stages. Native raw evidence is
streamed without constructing a second complete parsed report tree. This does
not remove the engines' existing input/report/member limits.

The full subject SHA-256 is checked around creation. Restore verifies the
sidecar's complete size/SHA-256 and exact subject identity. Opening its viewer
checks the capture again; export checks it before/after staging and checks the
staged bytes against the captured digest. Page reads use the staged file's exact
length and bounded UTF-8 decoder. The SHA-256 identifies bytes, not a trusted
publisher or proof that an engine's interpretation is correct.

Only generated `content-origin-*.ndjson` leaves inside the private directory
can be restored or discarded. Foreign paths and symlink leaves are refused.
Handoff failure, cancellation, stale callbacks and destination failures clean
their own stages. Capture restoration does not rerun its native engines.

## Verification

`bash scripts/test-content-origin.sh` runs real private-file models in a 128 MiB
JVM, then independently reconstructs the JSON Lines with Python. It checks all
65,536 UTF-16 units, large unknown values, nulls, exact numeric tokens and raw
JSON spellings; source expiry and byte-identical export; same-length mutation;
wrong subject/type/size/path/symlink refusal; failed producers, witness budgets,
cancellation and cleanup.

Android host cases cover complete paged viewing, recreation and delayed exact
exports after source/capture expiry; Close versus ownership; altered-origin
refusal with usable byte viewing; cancelled/failed export destinations and
retry. Actual Binwalk/PhotoRec and Gallerize tests verify their produced captures.
An actual libarchive nested ZIP → TAR → member case compares every retained
native report and acquisition after browser/source expiry. Only OS transport
and document destinations are substituted for those JNI cases.

Handset/SAF/process-death/cache-pressure/isolated-UID/16 KiB runtime acceptance
remains unverified. See [text paging](text-paging.md), [recovered content](recovered-content.md),
[native archives](native-archives.md) and the [feature checklist](FEATURE-PARITY.md).
