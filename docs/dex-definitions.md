# Captured multidex definitions

The DEX viewer now supplies **Captured multidex sources / acquisition errors**
and **Class definitions across all captured DEX inputs** under Root views. Type,
method and field identifiers, encoded operands and method-handle members offer
**exact declarations across captured DEX inputs**. Class records also link to
every captured declaration of the same original descriptor.

These are declaration observations. Several matches remain several candidates;
the viewer never silently chooses an implementation. No match means only that
the exact declaration was not found in the stated successfully indexed scope.
Missing/failed scopes make absence and uniqueness unknown. No inspected class
is loaded, installed or executed.

## Acquisition and identity

Acquisition makes and SHA-256-identifies a complete private parent snapshot,
checking the previously supplied parent length/hash when available. ZIP/APK
selection includes every non-directory name ending `.dex`, case-insensitively,
plus an explicitly selected origin. Nonstandard/asset DEX names remain candidates;
their presence does not establish that Android loads them. Nested APKs, other
splits, encrypted/renamed code and dynamically obtained files are not discovered
automatically. Raw DEX/041 containers use the selected immutable DEX snapshot.

The existing `ArchiveStore` revalidates the full directory and member uniqueness,
checks expanded size/CRC32 and supplies the exact expanded SHA-256. Each parsed
DEX hash must agree with acquisition. A remembered origin must match the
corresponding captured member, so an old origin cannot be mixed with changed
parent/sibling bytes. The explicit origin reserves its declared bytes first;
source ordinals and result order still follow original directory/entry/class
indices. Failed inputs consume their declared reservation when applicable.

The index keeps owned member snapshots, one class-name map and detached source
metadata. It does not retain every parsed DEX document in memory. Source records
include original member indices/names, declared sizes/CRC, actual snapshot
sizes/SHA-256, every parsed container-entry header and exact failure reasons.
Unsupported/corrupt/over-budget sources remain visible; an unreadable source is
not a successful zero-class result. Valid acquired bytes whose DEX parsing fails
can still be exported with their checked identity.

Each index also owns a generated JSON manifest with its own size/SHA-256. That
manifest is the byte source for aggregate tables; it is not mislabeled as an APK
or DEX. Parent and member identities remain separate. An expired manifest cannot
change its remembered identity. Availability flags check path/length only;
content is rechecked against its SHA-256 before loading/exporting a member.

The complete parent is released after acquisition. Captured member navigation,
smali, source-byte exports and reports can continue if the original parent is
deleted. Closing the backend releases all its owned files, including failed
candidates and a successfully built index cancelled just before publication.
Activity recreation rebuilds from an available parent and captured origin query;
it does not persist an entire live index across process death. Already prepared
exports remain independent and can finish after parent/parser expiry.

## Exact matching and relationships

The pinned Google smali/dexlib2 3.0.10 reader supplies every original record and
native pool/declaration index. Class definitions match exact UTF-16 descriptors;
all duplicates, including shared 041 entries and duplicate owners in different
members, remain separate observations. No normalization or case folding occurs.

Method declarations require the same declared owner, name, **all parameter
types and return type**. Fields require the same owner/name/type. The identifier
owner must also agree with the class-data declaration owner. A malformed foreign
identifier owner produces a partial-query diagnostic rather than an invented
implementation. Declaration iteration uses the existing `skipDuplicates=false`
APIs, preserving direct/virtual and static/instance ordinals.

This is not an ART linker. Inherited members, interface/default resolution,
dynamic dispatch, reflection, external framework/other-split definitions and
runtime reachability remain outside the contract. Arrays/primitives have no
class-def record and are not silently converted to their component class.
Opcode use/caller scans still have their existing selected-entry scope.

Each result retains target source SHA-256, member/entry/class/declaration/type
indices, exact signature and physical **target DEX** location. Method/field
locations identify pool items; declaration ordinals and code bodies have distinct
locations. Aggregate rows deliberately have no generic physical byte range:
their target offset does not point into the index manifest or compressed APK.
**Open this exact captured declaration** selects the right owned file/table/page,
after which ordinary bytecode/debug/smali/hex/range relationships apply.

Full logical source names appear in source metadata; bounded previews accompany
rows and search examines the full source name. Complete scoped JSON exports keep
every collected candidate independent of filter/page. Index/source/lookup errors
are structured, and partial tables identify their scope prominently. Query
diagnostics retain up to 256 messages/4,096 units each, with total/omitted counts.
Budget failure does not return a successful partial match list.

## Budgets and remaining verification

Parent snapshots: 256 MiB; inventory: existing 20,000 entries / 8 Mi name units;
DEX candidates: 128; input: 32 MiB per DEX and 96 MiB aggregate declared-byte
reservation; class index: 120,000 declarations / 16 Mi UTF-16 descriptor units.
All classic/041 string/header limits of `DexDocument` still apply. Oversized
candidate inventories fail acquisition; failed/skipped individual scopes keep
the successful inputs with explicit partial coverage.

Exact member searches cap 200,000 declarations, one million parameter
comparisons, 32 Mi compared UTF-16 units and 50,000 matching declarations. Index
and lookup have separate 30-second cooperative deadlines/cancellation. An active
upstream parsing/formatting operation must return before a check settles.
Parsers still run in process; hard isolation and handset/API 26/35/16 KiB runtime
behavior remain unverified. None of these limits establishes ART acceptance or
arbitrary hostile-input safety.

## Reproduce checks

```sh
bash scripts/test-dex.sh
# Optional local real-APK audit; no sample is uploaded or redistributed.
DEX_REFERENCE_APK=/path/to/file.apk bash scripts/test-dex.sh
```

Host fixtures use real serialized consumer/owner DEX files with overloaded
parameters/returns, field types, external/inherited references and method/field
handles. They verify all duplicate scopes/encoded class-data declarations and 041 shared absolute locations,
correct target bytes/smali, complete staged exports, parent expiry, corrupt ZIP
CRC exclusion, foreign identifier owners, same-length parent/cache mutation,
captured manifest identity, 128-source/aggregate-byte limits, failed candidate
cleanup and late cancellation/retry. A saved aggregate query reopens with its
original APK/DEX acquisition mode.

Four API 28 Robolectric cases exercise actual engines, ZIPs and Android widgets:
cross-member method navigation after parent expiry; 129-class paging/source
search/Back/recreation; complete filtered ambiguity export across deletion and
pending-picker recreation; and exact acquired-source export. Only destination
document streams are substituted. Real SAF grants, Android Binder/device
execution, full split/framework/inheritance resolution and release validation
remain separate acceptance work.
