# Offline tracker signatures and original metadata

**Options → Import Exodus database (JSON)** accepts a user-supplied snapshot.
**Options → Inspect imported Exodus rules / metadata**, also available under
APK / SDKs, opens every record, matching support/diagnostics, original top-level
metadata, complete record exports and exact original snapshot-byte export.
Browsing this catalog does not require a selected APK or an available previous
input. No database, API token or user file is downloaded/uploaded by the app.
The project does not redistribute an Exodus database.

## Matching evidence

The previous dotted-literal-prefix adapter missed normal slash signatures and
regex alternatives. Its replacement is an independent GPL adapter using the
complete source-built RE2/J 1.8 engine already used by LibChecker. These two
adapters have different contracts: LibChecker uses exact-first whole matching;
Exodus signatures use **regex search** and can associate several rules with the
same class. A dotted pattern remains a regex; it is never reinterpreted as a
Java package prefix.

The development reference is Exodus core
[`8a18f43f16f1727438c5dd8f18a8a31301e1df9a`](https://github.com/Exodus-Privacy/exodus-core/blob/8a18f43f16f1727438c5dd8f18a8a31301e1df9a/exodus_core/analysis/static_analysis.py),
source SHA-256
`4569ea6464d888f9edda362cc392247934db50bb598b35655e7dec99c1d230a1`.
Its actual signature compilation/detection methods supply the host reference.
Exodus core is AGPL-3.0; it is neither bundled nor linked into the app. No
reference Python methods were copied into the production adapter.

The adapter obtains **original defined-class descriptors** from dexlib2, removes
only the initial `L` and final semicolon, and searches the remaining slash name.
It does not reconstruct slash names from dotted display labels. Unicode, nested
class `$` characters and the original spelling remain evidence. Signatures of
three or fewer Unicode code points are ignored, following the audited upstream
gate. Other unsupported rules remain visible and contribute to partial coverage.
The report records every matching rule, its signature, exact original descriptor,
match interval in UTF-16 units, snapshot hash and collected metadata. Its first
witness uses deterministic UTF-16 descriptor ordering; upstream itself does not
guarantee the order of names extracted into its set.

Ordinary literals, escapes, alternatives, groups/noncapturing groups, anchors,
character classes and supported greedy/lazy repetitions use the compared
Python-compatible subset. Python `\w`, `\d`, `\s` and their supported complements
use **frozen Unicode 15.0** character ranges, matching Python 3.12 character data.
They do not use Android/JDK tables or RE2/J's older Unicode 6 tables. `\Z` is
translated to strict end-of-input. Word boundaries, flags, lookaround,
backreferences, named groups, possessive repetition, unsupported class syntax
and other unimplemented escapes produce explicit diagnostics. LF-containing,
isolated-surrogate and overlong names remain unsupported, preventing Python's
special final-LF `$` behavior from becoming an incorrect result. Supplementary
input code points remain supported. No case folding or normalization occurs.

## Complete import and source ownership

Import first reads at most 8 MiB, strictly decodes UTF-8, and validates complete
JSON structure with an explicitly bounded reader. Numeric tokens remain lexical
values for searching/string-field type checks, and are never converted through
Android's double-limited JSON reader. Duplicate names at **any** object
level, including escaped-equivalent IDs, are rejected rather than replacing
records. Invalid literals/numbers/escapes, trailing input, excess nesting and
structural budgets also reject activation. A valid snapshot has one `trackers`
object; non-object entries, missing/non-string signatures and unsupported
patterns are retained as inspectable diagnostics. An invalid replacement leaves
the previous snapshot untouched. A generated sibling staging file is atomically
renamed only after validation; this is not a power-loss durability guarantee.

Unknown fields, descriptions, categories, network signatures, timestamps,
version/source metadata and original numeric lexemes remain available. Rows
display bounded previews with explicit truncation markers; search examines the
full decoded original value. Every record/table JSON export includes its exact
original JSON value separately from display labels. This preserves large integers,
exponents beyond floating-point range, escape spellings and isolated escaped
UTF-16 units without numeric normalization. Character intervals in the imported
JSON are labeled **UTF-16 positions**, never selected-APK physical offsets.

The catalog owns a private copy of the complete original UTF-8 bytes. A later
import or deleted source cannot change an already acquired catalog or prepared
export. Exact snapshot-byte export retains root fields, whitespace and ordering.
Its SHA-256 identifies the actual imported bytes; user-supplied source/version
claims are not authenticated online. Pending exports use the shared staged-picker
contract and can finish after source expiry or activity recreation.

## Bounds and interpretation

In addition to the 8 MiB snapshot limit: 10,000 records; IDs at most 256 UTF-16
units; JSON depth 64 / 200,000 values; patterns at most 1,024 units / 512 Ki
aggregate units; compiled programs at most 8,192 instructions per pattern /
65,536 aggregate; 15-second cooperative compilation budget. Input is at most
120,000 original descriptors / 16 Mi UTF-16 units, with individual slash names
at most 16,384 units. Search stops at 2 million comparisons, 128 Mi compared
units, one billion estimated input-unit/instruction operations or a 25-second
cooperative deadline. Cancellation is checked during acquisition, validation,
rule/class iteration, search and export. An active RE2 operation must settle
before cancellation is observed; this is not a hard isolated-process deadline.

An exhausted search records unknown/not-fully-searched rules and marks the result
partial. Unsupported names/rules also prevent a complete-coverage claim. No
matches in a supported subset cannot establish tracker absence. A match verifies
only that a collected defined-class name fits a rule. Initialization, invocation,
collection, transmission, credential validity, vulnerabilities and malicious
behavior are not thereby observed. Network signatures are metadata, not traffic.

DEX/APK acquisition retains the separate report limits: bounded conventional
`classes*.dex` entries, checked expanded size/CRC, per-file/aggregate DEX budgets
and explicit partial errors. This differs from upstream's regex extraction over
dexdump text, which can include other references, and from its recursive nested
APK traversal. Unselected splits, obfuscation, arbitrary type references and
unselected nested archives remain outside this scope. The independent matcher
runs on the app worker in process; full Python-regex and engine/acquisition
equivalence, Binder isolation and handset behavior remain unverified/incomplete.

Imported database licensing is ODbL-1.0 / individual contents DbCL-1.0; retain the
actual snapshot's source/licensing context. Generated Unicode ranges carry the
full Unicode license, the original UCD URL/SHA-256 and a checked generation recipe
in `scripts/tracker-unicode.py` / `regex-engine/tracker-unicode-lock.json`.

## Reproduce verification

```sh
bash scripts/test-trackers.sh
# Optional: compile/audit a separately obtained local snapshot, without uploading it.
TRACKER_SNAPSHOT=/path/to/trackers.json bash scripts/test-trackers.sh
```

The development reference requires Python 3.12 with Unicode 15.0.0; CI selects
that runtime explicitly. Host fixtures execute the actual pinned upstream methods against synthetic
rules/classes, compare 924 searches/first witnesses/exact spans, and exhaustively
compare all Unicode scalar values of `\w`/`\d`/`\s` against the original pinned
UCD. They also cover alternatives, anchors, empty/short rules, unsupported
syntax, strict UTF-8/duplicate/depth handling, exact original numbers/Unicode,
budget exhaustion, cancellation, non-backtracking, atomic import preservation
and owned byte exports. The upstream source and Unicode source are fetched only
for development; `TRACKER_DEPS_DIR` and `EXODUS_REFERENCE_SOURCE` support cached
offline checks with checksum verification. All Android runtime data/code is
retained in Git; an APK rebuild needs no tracker database or reference download.

A separate development audit on 2026-11-18 accepted a public API snapshot with
SHA-256 `bf6922f7c95ceb130fcfabf96c32c615e60d62ca507a4b82dcd74b41061c5121`:
432 records, 428 compiled signatures, four ignored short signatures, zero
unsupported active signatures and 10,825 compiled instructions. The snapshot was
not added to the app/repository. This verifies acceptance of those exact bytes,
not an authenticated upstream release, known tracker behavior, matching ground
truth for an APK or coverage of other/future snapshots.

Android integration tests use the actual catalog, engine, smali DEX bytes and
ZIP streams, with test document-provider streams. They exercise complete
paging/metadata/diagnostics/search/Back, long original-record export, exact
snapshot export across expiry/recreation, actual DEX/archive report matches and
invalid import preservation. These are separate from real SAF/provider grants,
process death, handset/API 26/35 execution and parser-isolation verification.
