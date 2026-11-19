# Captured APK package sets

In **System Explorer → package details**, choose **Investigate captured base +
split APK set**. The input list is every base/split code path exposed by the
current user's PackageManager inventory. The Scan APK tab also opens this tool
for its one selected APK. Filenames do not establish base/feature/configuration
identity: the tool reads original compiled manifest declarations.

This is a read-only investigation of explicitly captured bytes. It does not
install or execute an APK, discover missing files, bypass UID/SELinux restrictions
or establish that a captured set is installable. Inventory paths are observations,
not permission to read them. Acquisition is sequential, not an atomic snapshot
of Android's package state. Updates during acquisition can produce a mixed set.

## Acquisition and source ownership

Each input gets its original ordinal/name, whole-file size and SHA-256. A remembered
Scan identity must match; rejection retains the observed digest in its diagnostic
without analyzing changed bytes as the old input. Every failed input remains a
source record. Failed copy/parse attempts consume their declared reservations
when applicable; a failed scope is not a successful empty package.

The existing ZIP reader inventories each captured APK and selects the exact root
`AndroidManifest.xml`. Duplicate names, unavailable lengths, CRC/size failures
and changed directories are explicit. The expanded manifest has its own SHA-256,
size, member ordinal, declared CRC and compression metadata. A CRC-failure fixture
uses a changed manifest that still parses on its own: it is rejected before the
resource engine runs. Whole APK identity is checked again after analysis.

The source-built Google binary-resources/wire adapter parses manifests through
the existing descriptor worker. AOSP apksig independently verifies each embedded
APK and collects every returned scheme signer, certificate, scoped lineage,
digest, source stamp and diagnostic. No detached `.idsig` is supplied in this
set scope; the separate signing workbench provides that selection workflow.
Signing still runs in process. Production worker UID/Binder behavior remains
device-unverified.

Owned APKs survive deletion of the original selected paths and Activity
recreation. Restoration validates private directory ownership and full APK
SHA-256/size, then re-extracts/re-analyzes their manifests. Unretained acquisition
failures keep their original structured diagnostics rather than growing nested
error strings on each restore. Reopening from the current file/inventory starts
a new acquisition. Normal close removes the session; cache eviction, orphan
reclamation and power-loss durability remain separate concerns.

Aggregate views use a generated, independently hashed public input-index JSON
file as their byte source. It is separate from private restoration descriptors.
Aggregate records have no invented APK offsets. **Exact acquired APK bytes** and
**Exact expanded manifest bytes** switch to the correct owned file before
hex/range/export actions. Nested attribute/chunk offsets always belong to the
expanded manifest. Child bytes can remain usable if the cached parent expires;
availability-by-path/length is explicitly separate from a content check.

## Declared fields and relationships

The adapter retains every attribute on relevant direct manifest/SDK/application/
uses-split nodes, including unknown names, raw typed values, original ordinals,
compiled attribute identities and child byte coordinates. Known scalar selection
uses original namespace/name with an optional public SDK 35 resource-ID check.
Duplicate attributes, conflicting IDs, resource references and repeated SDK/
application nodes remain unresolved; they are never replaced with convenient
defaults. Missing version/feature-related defaults are explicitly attributed to
the reviewed AOSP lite contract. SDK codename/literal fields are not resolved.

Version comparison preserves the unsigned composition of both stored 32-bit
version words as decimal text; original signed/unsigned words remain in the
attribute evidence. This is bit identity, not an Android version-ordering or
platform-validity claim. Literal empty/absent split names denote base under the
reviewed lite rule, while their original presence remains visible.

| View | Observation |
|---|---|
| Sources | Every selected slot, acquired identities, declared manifest member, parsed scopes and failures |
| Groups | Exact literal package + version bits + split identity; all duplicate definitions and base candidates |
| Dependencies | Base-file candidates, first feature parent/default base, configuration targets, every additional uses-split declaration and every target candidate |
| Split types | Original comma-token requirements and exact trimmed provider declarations, preserving duplicates/empty tokens and unknown provider coverage |
| Signing comparisons | Every captured base reference, or a clearly labeled first member when none is known; exact verified certificate DER-set equality and version-bit equality, independently nullable |
| Diagnostics | Acquisition/identity/parser failures, duplicate/missing candidates, unknown fields, non-feature configuration targets and conditional first-parent cycles |
| Per-source details | Original fields/nodes/uses-split records and all collected signing tables, with exact certificate/SPKI exports |

Multiple candidates remain multiple candidates. One observed match does not
establish uniqueness when another input/field is unknown. Unknown groups are
not inferred from filenames. The reviewed AOSP lite parser ignores later
uses-split nodes; this tool retains them and distinguishes them from the first
conditional parent. An explicitly empty `configForSplit` is not silently
converted to base: the reviewed dependency loader searches for that literal
split name. Configuration targets that are not known features are diagnosed.

Cycles describe the currently unambiguous observed first-parent candidates,
not a measured runtime classloader. Exact certificate inequality can accompany
legitimate rotation/platform targeting; shared parsed lineage data is not an
update authorization or publisher-trust decision. Full signing scopes remain
inspectable. Verification ranges retain each manifest minimum through API 35.

Reference knowledge is pinned to AOSP `android-15.0.0_r1` ApkLiteParseUtils and
SplitDependencyLoader source hashes in `package-engine/source-lock.json`.
Those platform classes are not bundled or executed. Name validation, full
platform parsing/installation, device targeting, framework/split resource
resolution and cross-split DEX/runtime linking remain incomplete.

## Paging, exact exports and limits

Search uses complete captured values, independently of bounded interactive
previews. Complete table, entire-set and selected-record JSON exports stream
original records regardless of search/page. UTF-16 code units are preserved in
keys and values. A preview that exceeds 96 Ki-characters after JSON escaping
becomes an explicit marker; it does not replace the stored/exported value.
The shared 256 MiB export staging limit can require choosing a narrower table
or source; no successful prefix export is substituted.

| Budget | Bound |
|---|---|
| Explicit inputs | 64 |
| APK each / aggregate declared reservation | 256 MiB / 512 MiB |
| Expanded manifest each / aggregate declared reservation | 8 MiB / 64 MiB |
| Relevant attributes / nodes / uses-split per manifest | 50,000 / 4,096 / 256 |
| Retained attribute-to-resource-ID associations | 100,000 |
| Copied manifest declaration characters / collected set model | 16 Mi / 64 Mi |
| Relationship rows / association references / encoded characters | 50,000 / 250,000 / 32 Mi |
| Split-type list text / tokens | 8,192 units / 256 |
| Acquisition / graph cooperative deadlines | 90 seconds / 30 seconds |
| Selected combined table records / escaped row preview | 100,000 / 96 Ki |
| Private restoration state | 2 MiB |

Limits fail explicitly. A graph failure leaves acquired source evidence
inspectable instead of publishing a shortened successful graph. Upstream CPU,
I/O and provider behavior can delay cooperative cancellation; this is not hard
worker/memory isolation. APK/signing/resource engines also retain their own
documented limits.

## Verification and continuation

`tests/generate-package-reference.py` packages seven independent SDK 35 AAPT2
reference manifests. The workbench UI script requires these alongside the
existing resource references. The three manifest cases use real parser output
and serialized malformed fields to check identities, full version bits,
SDK/feature/configuration flags, duplicates/references, repeated/nested nodes,
cancellation and original attribute preservation.

Four document cases check otherwise-parseable CRC rejection before parser entry,
observed cycles/wrong-kind/empty targets, failed-scope reservation consumption,
the real per-APK and input-count guards, late cancellation cleanup, remembered
identity diagnostics, escaped preview bounds, full Unicode search and complete
exports. Aggregate reservation behavior also uses a deliberately lowered public
reader budget, rather than allocating 512 MiB of fixture data.

Five viewer cases use actual AAPT2 APKs/signatures/parser output and widgets:
simulated PackageManager inventory handoff; correct parent/type/signer mismatch
navigation; exact acquired APK/manifest exports across deletion/recreation;
complete filtered set exports; and duplicate/missing scopes with stable retained
failures. Provider/worker transport and the inventory service are test substitutes.
The full Android suite reaches 83 checks. Actual normal-UID installed paths,
real SAF, ARM64/handset/API 26/35, isolated-worker IPC, platform acceptance and
incremental installation remain unverified. `.apks`/`.xapk`/AAB targeting/import,
manual multi-document set construction, complete signing policy, framework
resolution and cross-split code/resource linking are future work.
