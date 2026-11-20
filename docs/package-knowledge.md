# What does this package do?

**Options → What does a package do?** opens the offline community catalog without
requiring a selected file. The Android tools and APK / SDKs page also expose the
catalog. In the installed-package explorer, **What does this package do?** opens
the exact PackageManager-supplied package ID. These are separate entry points
from captured APK/split, signer, manifest, component and permission evidence.
The general catalog does not automatically associate the selected APK with a
community record.

An exact-ID overview puts the original practical description first, then shows
every original field, including unknown fields. Each field has its actual type,
full-value length, source record identity and contextual meaning. Complete field
and original-record actions use the paged text viewer. Source list/removal
categories are attributed community labels, not a tested verdict about this
device. No package is disabled, removed, installed or executed.

## Pinned source and duplicate identities

The unchanged bundled UAD-NG database comes from revision
`9fcad87bf78c52c4270504bc8880139d95a27a29`, under its original GPL-3.0 notice.
Its 1,631,714 exact bytes have SHA-256
`69e3e7f33a64f165217a58bb426aada13385f5427a5123e34d31041416f593a6`.
The production backend refuses a different content hash. Original licensing is
bundled as `knowledge/UAD-LICENSE.txt`; source identity/export actions include
the immutable upstream URL and license reference.

There are **5,402 original record occurrences and 5,399 distinct package IDs**.
Three IDs occur twice: `com.aura.oobe.kddi`, `com.aura.oobe.motorola`, and
`com.dti.motorola`. Their descriptions differ; two pairs also differ in list
category. Ordinary JSON object lookup keeps one occurrence and hides the others.
The new reader preserves every occurrence and exposes all matches. It never
chooses a preferred description or category. Original-record/field rendering
selects the exact source record index; ambiguous unindexed rendering is refused.
The existing APK summary now retains every matching community occurrence too.

`CatalogJson` has an explicit duplicate-*record* mode for this catalog only.
Tracker imports and other existing callers retain strict duplicate rejection.
Duplicate fields inside a record still refuse the snapshot; none occur in the
pinned UAD-NG source. Exact original record slices retain whitespace, numeric
tokens, escapes, Unicode and unknown fields. Positions describe UTF-16 units in
the decoded original JSON, not physical file byte offsets.

## Relationships and coverage

The source contains **85 declared relationship occurrences**. `dependencies`
means this source record claims the package requires the target; `neededBy`
means it claims the target requires this package. The viewer retains original
field, array position, duplicate occurrence, claimant record and target value.
Reverse references are an explicitly computed index over those exact IDs.
Absent target records, non-text values, duplicate target identities and missing
reciprocal declarations remain visible. A reciprocal flag means a corresponding
source claim exists, not that either claim was measured on this phone.

Empty/missing lists do not establish the absence of dependencies. Direct links
support navigation and Back; transitive closure, cycle analysis and runtime
dependency discovery are not implemented. Package IDs are case-sensitive with
no aliases or normalization. A missing exact match stays unknown. An ID can be
reused by another signer/vendor/version; this viewer does not authenticate an
installed package or establish what it actually executed.

Browse all occurrences, source list/removal groups, or coverage diagnostics.
Search examines complete source values, beyond display previews. Full table
exports retain every selected original record; exact original catalog bytes are
a separate export. Ordinary JSON readers will still collapse duplicate root
keys in that original file. Use the occurrence-preserving table export when
comparing records independently.

## Ownership, budgets and verification

`PackageKnowledge` handles evidence and exact-ID/relationship indexing;
`PackageKnowledgeBackend` owns acquisition, requests, navigation and rendering;
the existing investigation UI handles paging and exports. A private checked
snapshot survives source expiry and configuration recreation without rereading
the asset. Failed restoration remains a visible failure. A prepared export can
finish after its source snapshot expires.

Budgets: 8 MiB source bytes, 10,000 record occurrences, 1,024 UTF-16 units per
package ID, 50,000 relationship occurrences, plus the shared JSON depth/node and
interactive row/page/export limits. Display previews are explicitly labeled;
complete values and exports remain available within those bounds. Malformed
UTF-8/JSON, duplicate fields, wrong pins/types/record indices, cancellation and
budget failures do not publish a partial replacement.

Run `bash scripts/test-package-knowledge.sh` after `scripts/fetch-knowledge.sh`.
A 128 MiB JVM and independent Python parser reconstruct every field of all
5,402 actual source records, including duplicate IDs. Fixtures cover all 65,536
UTF-16 units, unknown fields, exact huge/negative-zero numeric tokens, raw slices,
full-value search, missing/duplicate/malformed/cyclic relationship declarations,
exact directions, snapshot restoration without reopening the source, pin/type/
budget refusals and complete delayed exports. Existing tracker fixtures verify
924 pinned-upstream comparisons and retain strict import rejection.

Six API 28 Robolectric cases exercise catalog pages/groups/search, ambiguous
overviews and exact occurrence rendering, full source export across expiry and
recreation, missing IDs and relationship Back, the real main/inventory entry
points, and duplicate-preserving APK summaries. The catalog/reader/IO/widgets
are real; installed/archive PackageManager metadata and output destinations are
controlled host fixtures. Real handset/PackageManager/SAF/process-death/heap and
API 26/35 acceptance remain unverified.

Future work includes user-contributed overlays/imports, more attributed sources,
version/device conditions and correlation with captured APK/signer evidence.
Descriptions, permissions, string matches and package associations alone do not
establish runtime behavior. The app remains offline with no INTERNET permission,
automatic URL fetch or telemetry.
