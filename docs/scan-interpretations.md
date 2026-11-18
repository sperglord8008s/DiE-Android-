# Primary scan interpretations

The primary Scan page now offers **Next scan interpretation** using the actual
CDiE `gui_backend_file_types` catalog, already used by the desktop laboratory.
It lists Automatic, the preferred concrete interpretation and other detected
compatible choices, including Binary and supported text/archive aliases. This
is the native compatible list for the current input, not a claim that every
known format can be forced onto arbitrary bytes.

Selecting a row changes the next request. **Scan / Rescan** applies it; existing
results keep their captured interpretation until the new scan completes. An
explicit selection runs that native scan pass, even when All types is enabled.
The primary native facts request uses the same selector as the signature tree.
Header identification from the independent Inspector remains a separate source
field. Choosing Binary for an ELF does not erase its ELF header evidence or
prove the file is valid in every interpretation.

The request stores the exact native name, not a spinner position or an ID
borrowed from another input. Each rescan reacquires the complete input and
resolves the name against its fresh compatible catalog. Same-name/same-ID
repetitions remain in the captured catalog and resolve consistently; a missing
name or different IDs for the same name fail explicitly. There is no automatic
fallback for an unresolved explicit choice. Selecting Automatic recovers it.
A different input URI starts with Automatic. The same URI can supply changed
bytes on rescan; its current catalog and new content identity are used.

This selection belongs to the primary input. Saved option profiles retain their
existing complete flags/tags/preview-minimum contract; desktop batches continue
to request Automatic. Neither reinterprets a profile's formerly opaque fields
or stores backend IDs as portable settings. Full Qt settings compatibility,
profile/batch interpretation policies and arbitrary-format forcing remain
separate work.

## Captured evidence

The added optional `scanTarget` member of `org.diemobile.scan-report` contains
`org.diemobile.scan-target`, version 1:

| Field | Meaning |
|---|---|
| `request` | `selectionMode` automatic/compatible-name and exact `formatName`; empty name denotes Automatic |
| `resolvedTypeId` | The backend selector passed to facts and scan, or null for an unresolved explicit choice |
| `resolutionError` | Why an explicit name could not be resolved; no successful empty scan is invented |
| `compatibleFormats` | Complete original decoded native response, including choice order/repetitions, signature count and unknown members; null if not collected |
| `source` | SHA-256 and exact size of the complete privately acquired input |
| `engineRevision` | Captured engine source revision, not a format label |
| `scope` | Input-specific selection and backend-ID domain explanation |

`options.typeId` agrees with this captured request. Zero means the requested
Automatic policy, not evidence that format detection succeeded. A successful
native scan must return the same selector. An unresolved explicit request uses
null rather than being mislabeled zero. Its requested name, complete compatible
catalog, resolution error and ordinary engine failure remain exportable.

Report attachment checks exact source SHA-256/size, known engine revision and
the successful native scan's selector. Draft controls, reopened dialogs and
later option changes cannot relabel an earlier result. Staging captures this
context before the picker; recreation, later draft selection and source expiry
cannot alter the pending report bytes. Rescans after recreation resolve the
saved name again. Format dialogs close when a new scan starts, and their
callbacks also reject stale source contexts.

The adapter accepts at most 4 Mi characters of native catalog JSON, 4,096
choices and 4,096 UTF-16 units per name; unsupported/malformed/over-limit catalogs
fail with an explicit diagnostic. Catalog names are compared exactly, without
case or Unicode normalization. Automatic remains the known selector zero if
no manual catalog can be collected. Normal main input/report limits still
apply. Signature rules and native parsing retain the existing cancellation and
in-process/device-verification limitations.

## Verification

`scripts/test-models.sh` covers stable names versus nonsequential IDs, repeated
and ambiguous records, wrong names/case, unknown Unicode metadata, saved state,
source/engine/selector mismatches, fractional source sizes and unresolved
report fields. It checks complete captured scope rather than a displayed row.

`scripts/test-native.sh` uses actual native candidate discovery and separate
known ELF/Binary rules on a generated ELF fixture. Binary selection runs only
the Binary rule, with All types both disabled and enabled; facts use its actual
selector. Repeated Binary/Automatic calls with result caching enabled preserve
their distinct passes, and the subsequent automatic scan still runs the ELF
rule. The inspected ELF is never executed.

Three additional API 28 Robolectric cases run real input acquisition, native
catalog/facts/scans and primary controls: explicit selection and immutable
pending report through draft changes/recreation; rescan-by-name restoration and
new-input Automatic; stale-dialog rejection, unresolved exported diagnostics
and recovery. Test output streams substitute for real document providers.
These checks do not verify handset/ARM64 runtime, real SAF grants, process
death, provider revocation or exhaustive desktop format semantics.
