# Saved scan option profiles

Open **Options → Saved scan option profiles**, or the equivalent button in the
desktop scan laboratory. Create a named snapshot of the current shared settings,
inspect its full record/effective values, rename it, replace its settings from
the current controls, apply it or delete it. Duplicate display names retain
distinct UUIDs. Applying updates the settings used by Scan and the laboratory;
use Scan/Rescan to collect evidence with those settings. The primary options
editor closes before launching profiles so a stale string minimum cannot undo
an applied profile.

## Request scope

The `org.diemobile.cdie-options/v1` contract includes every current desktop flag:
Deep, Heuristic, All formats, Recursive, Verbose, Aggressive, Overlays, Resources,
Archives, Profiling, Hide unknown, Custom DB, Sort and Result cache. It also
captures include/exclude tag filters and the preview string minimum. Effective
tag values follow the existing `ScanOptions` normalization; original imported
declarations remain in the full record until a settings edit serializes the
effective values.

Profiles reuse current engine/database selection. Those identities and actual
request flags/tags are captured separately by scan reports. A saved profile
does not establish equivalent output across different database revisions.
Manual laboratory format selection and other tool/range settings are outside
this contract. Desktop Qt INI/QSettings compatibility, batch jobs, profile-based
comparisons and per-scan profile-ID attribution remain pending.

## Data and publication

The catalog schema is `org.diemobile.scan-profiles`, `schemaVersion: 1`, with
`optionsContract` and a `profiles` object keyed by canonical lowercase UUIDs.
Each record has `name`, optional `description` and an `options` object containing
`flags`, `minimumStringLength`, `includeTags`, `excludeTags`. Local creation and
updates add UTC event timestamps. Imported timestamps/unknown fields remain
source metadata, not authenticated creation evidence.

The complete current catalog and each original record retain SHA-256 identities.
The shared bounded catalog reader rejects duplicate keys, escaped-key aliases,
invalid JSON/UTF-8, unsupported schemas, noncanonical IDs, malformed settings
and nonintegral/out-of-range controls. Unknown root/record/options fields and
original numeric tokens, including values beyond Java numeric ranges, remain
available. Known-setting edits preserve those fields and every decoded UTF-16
unit; original full catalog imports/exports retain their exact UTF-8 bytes.
`TrackerJson` now supplies its unchanged Exodus contract through the same reader;
upstream matcher/numeric/Unicode regressions still pass.

Storage is private `scan-profiles/catalog.json`. Edits capture the current
catalog SHA-256; a newer revision rejects stale publication until Reload. A
complete validated replacement is written to an owned temporary file, synced,
and atomically moved in the same directory. Failure/cancellation removes only
that temporary file and retains the prior catalog. Unsupported atomic moves
fail explicitly. This is same-process revision checking; directory-fsync,
power-loss and externally modified private-file behavior remain unverified.

## Import, export and lifecycle

**Import / replace catalog** reads and validates the whole selected document
before showing its count, names and SHA-256 for replacement. Inspecting that
captured catalog keeps the approval available. The final action uses the
validated bytes rather than rereading the provider. It replaces the saved
catalog; applying a profile is a separate action. Invalid/cancelled imports keep
the prior catalog. The original picker revision is retained across recreation,
and publication refuses concurrent changes.

**Export complete catalog** stages the captured UTF-8 bytes before opening SAF.
Later catalog changes and activity recreation cannot change that pending export.
Picker cancellation/destination failure discards staging; a destination failure
may leave partial provider data and is labelled accordingly. Real SAF grants,
process death during I/O/import approval, orphan-stage reclamation, recovery of
an already damaged private catalog and handset behavior remain unverified or
incomplete. An unpublished import approval currently must be reopened after
activity/process loss. Published profiles are durable files subject to the
filesystem limits above.

Limits: **4 MiB catalog**, **256 profiles**, **256 Ki-characters per record**,
**64 JSON levels / 200,000 values**, **128 characters per numeric control token**,
256 UTF-16 units per name, 16,384 per description, 4,096 per tag filter, all
14 supported flag bits and a **3–64** string minimum. Preview dialogs show at
most 65,536 units and explicitly preserve complete values in export.

## Verification

`bash scripts/test-profiles.sh` uses actual files and failure injection to check
all flags, exact integral controls, UUID/name behavior, complete unknown numeric
and Unicode metadata, disk reload, stale/cancelled/failed publication, strict
imports and size/count/depth/control budgets. `scripts/test-trackers.sh` still
passes 924 actual-upstream-method comparisons and frozen Unicode/prefilter cases.

Six API 28 Robolectric cases exercise actual widgets/catalog files: save/apply
followed by an actual CDiE JNI request; stale dialog/reload/rename/settings/delete;
complete export through catalog replacement/recreation; import picker recreation,
validation/inspection and captured-byte replacement; cancelled/failed output
cleanup; and the primary options entry/application refresh. Only document streams
are substituted. The real-backend case also exposed and verifies the bundled
installer's creation of parents for nested relative asset leaves. Full regression
counts are recorded in the parity/continuation documents. These checks do not
establish real providers, flash durability or handset behavior.
