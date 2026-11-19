# Exact binary byte comparison

**File info → Binary comparison / exact differing byte ranges** captures the
selected file as the left input. **Root views → Select / replace right-hand
comparison input** acquires a second document. The original scan's left length
and whole-file SHA-256 must match before adoption. Both inputs become independent,
generated private snapshots; display labels never become filesystem paths.

This is the project's GPL-3.0-only, Java/JCA `aligned-byte-runs/v1` algorithm,
separate from format decoders, signature scanning and Qt comparison semantics.
It compares every paired byte at the **same physical offset**, coalesces
consecutive unequal bytes, and reports any one-sided tail beyond the shorter
file. Empty and identical files are supported. A middle insertion may change all
subsequent aligned bytes. A tail is a length observation; it does not establish
which edit operation occurred. No alignment, moved-block recognition, patch
synthesis, fuzzy score, semantic change or behavioral/safety verdict is inferred.

## Model and coverage

`BinaryComparison` uses two 64 KiB buffers and streams both complete inputs,
independently recomputing their SHA-256 during comparison. Actual read coverage,
length and digests must agree with acquisition. The backend rechecks both private
snapshots before publishing a replacement. Runs crossing a buffer boundary remain
one run. Equal bytes separate runs; no equal gap is silently merged into a change.

The summary exposes original names, full lengths/SHA-256, an ordered content-pair
ID, computation time, common/equal/different byte counts, each one-sided tail,
signed right-minus-left size difference, exact range count and identical-byte
status. Pair IDs hash the algorithm version and both ordered length/digest
identities; labels are separate metadata. They are identifiers, not signatures or
authenticity claims.

Each difference retains its original ordinal, kind, aligned physical offset,
independent left/right lengths and first 32-byte hex prefixes. Prefix length and
whether it covers the complete range are explicit. An absent side has zero bytes
and a null physical offset. Prefixes are previews, not extracted payloads or a
complete hex diff.

| Bound | Policy |
| --- | --- |
| Source | 256 MiB each / 512 MiB accepted pair |
| Replacement storage | An additional candidate snapshot can temporarily bring source storage to 768 MiB |
| Difference runs/tail | 100,000 total; overflow rejects the entire candidate comparison |
| Processing | 30-second cooperative budget, checked between reads and within byte loops |
| Source state | 128 KiB, bounded labels and canonical private restoration paths |
| Interactive/export | Existing 50/10/1-row pages, complete scoped exports and 256 MiB export staging |

Private-file reads, provider acquisition and cancellation remain cooperative; a
blocked read can need to return before cancellation settles. The processing
deadline applies to the comparator, not the provider's preceding acquisition.
Byte actions and exports can require their own additional private staging space.
Low storage or any exceeded bound fails explicitly; no prefix result is adopted.

## Ownership, replacement and navigation

`ComparisonBackend` separates acquisition, comparison, private source-state
publication and evidence views. A new right input is fully acquired, compared and
revalidated before a synced same-directory atomic source-state replacement. Failed
or cancelled acquisition/analysis/publication deletes only the candidate and keeps
the prior pair. The previous right snapshot is removed after successful adoption.
Host tests exercise actual publication failure as well as invalid retained state.

The shared supplemental-input viewer now distinguishes failure before adoption
from a provider stream's later close failure. If the backend has already accepted
the input, it captures the new restoration state, clears old navigation and loads
the accepted pair with an explicit diagnostic. It does not claim the old evidence
was retained. Providers without metadata-query support can still supply readable
streams. The bounded adoption diagnostic survives recreation.

Comparison ranges are aggregate records with no invented single-file physical
offset. Their actions select **Left exact corresponding range** or **Right exact
corresponding range** before hex, entropy/hash measurement or exact export. A
selected range retains full parent identity under `input`, separately from its
own `physicalOffset`/`sizeBytes`; a whole-input SHA-256 is never attached to a
shorter range as if it were that range's digest. One-sided tails offer only the
side that actually has bytes. Native hex receives a checked independent copy of
the selected side. Labels, filter/page state and Back/recreation use the existing
investigation viewer.

Range requests bind to the ordered content-pair ID. If an old ordinal is requested
after replacement, an explicit expired-scope record appears with null coordinates
and links to the current root views; it is never applied to replacement bytes.

Private source state retains both snapshot descriptors, captured labels and the
acquisition timestamp. Recreation validates owned paths, complete bytes and
identities before recomputing the comparison with the current algorithm. It can
survive original provider/parent expiry. A failed restoration preserves retained
files for diagnosis instead of deleting evidence. Normal close removes the
session's owned snapshots/state; configuration recreation retains them. Abrupt
process death can leave cache orphans; automated reclamation and power-loss
durability remain incomplete/unverified. This is not a persistent history manager
or a resumable/background job.

## Exports and verification

`org.diemobile.binary-comparison` version 1 contains the complete source-identified
summary and every original range, independent of filter/page. The evidence-table
wrapper remains available for selected tables/records. JSON and names preserve
exact UTF-16 units. JSON previews stage as `application/json`; textual/smali
scopes retain their text MIME type. Full report/range bytes are staged before the
destination picker, so source expiry and recreation cannot regenerate a different
already prepared export. Cached comparison observations can outlive source bytes;
recomputing after recreation needs both snapshots, while a prepared export remains
independent. Failed output can leave partial provider data and is reported as such.

`bash scripts/test-comparison.sh` compares actual files to an independent naive
byte oracle, including random inputs, empty/identical/asymmetric inputs, apparent
middle insertions and 64 KiB seams. A 100,000-run fixture streams the entire report;
100,001 runs fail. Source mutation, cancellation/recovery, input bounds, independent
side selection, whole-parent versus range identity, failed atomic publication,
stale ordinals, invalid restoration and source-expiry restoration are checked.

Five API 28 Robolectric cases use the real comparator/files/widgets and actual
native hex handoff; external document-provider streams are substituted. They
cover 81-run paging/Back/recreation, exact side/range bytes, complete filtered
exports across source expiry/recreation, failed/cancelled replacement and recovery,
post-adoption provider-close diagnostics/query fallback, and destination cleanup.
The full suite has 105 checks; inspect current CI for the committed revision.

Real SAF grants/providers, process death during publication/copy, low storage,
API 26/35, ARM64/device timing/accessibility and power loss remain unverified.
Semantic/report/structure/package comparisons, insertion alignment, moved content,
patches, arbitrary-engine batch comparisons and Qt parity remain incomplete.
