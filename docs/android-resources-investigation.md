# Compiled Android XML and resource-table investigation

**File info → Binary Android manifest / resource tables / compiled XML** opens
the selected raw binary XML/resources.arsc file or APK. The actual source-built
Google binary-resources 32.5.1 parser supplies the resource models; a bounded
wire inventory preserves the original stored records and physical locations.
This complements the main report's PackageManager summary. No inspected class,
resource, application or shared library is loaded or executed.

## Acquisition and identity

A raw file is copied into an owned private snapshot and hashed in full. An APK
gets an owned snapshot of the **whole parent archive**, not just its directory
or selected member. The initial view opens an unambiguous `AndroidManifest.xml`
when present. Root views can choose every non-directory `.xml`/`.arsc` member,
including names outside `res/`. Names select candidates; plain XML is rejected
explicitly rather than mislabeled as compiled XML.

Selected members use `ArchiveStore`: a complete bounded directory is revalidated
on the extraction handle, names must be unique, expanded size and CRC32 must
match, and an acquired SHA-256 identifies the uncompressed bytes. Member names
never become output paths. Parent mutation/expiry cannot change an acquired
report or later member selection. Replacing a successfully parsed member closes
the old child; failed candidates are deleted while the previous valid child is
retained. Closing the provider deletes its owned parent and child.

Reports retain the published source-JAR SHA-256, compatibility-patch SHA-256,
engine/adapter versions, input SHA-256/length and, for APK members, parent
SHA-256, member ordinal/name/compression/CRC/expanded length and acquired hash.
The patch and full publisher NOTICE are described in
[the engine module](../resource-engine/README.md).

## Evidence tables and navigation

All fourteen tables are available through Root views. Search covers complete
decoded record values and excludes shared explanatory boilerplate. Interactive
rows have bounded previews; complete selected records and all records in the
selected table export through streamed JSON, independently of UI filter/page.

| Table | Stored evidence and interpretation |
|---|---|
| Chunks | Every declared supported/opaque chunk, parent, type, exact offset/header/length, raw header bytes and Google model class |
| Pools | String/style counts, flags and data-area coordinates |
| Strings | Every logical pool index, exact decoded UTF-16 identity, encoding, length-prefix/payload/terminator coordinates and raw style offset |
| Spans | Every stored markup ordinal/name and inclusive UTF-16 start/end positions, including style holes in the pool inventory |
| Packages | Package ID/name/raw name code units, type/key pool relationships, public bounds and stored/absent type-ID adjustment |
| Types | Type/configuration candidates, every exposed pinned-engine qualifier field, complete raw configuration bytes/unknown fields, grammar byte and offset-layout flags |
| Offsets | Every dense/offset16/sparse slot, including original absent sentinels, logical indices, physical slot widths and scale/base for relative offsets |
| Entries | Every defined wire ordinal, including shared offsets/duplicate sparse indices, key/type/configuration relationships, logical resource ID and exact compact/ordinary/bag extent |
| Maps | Every complex bag mapping in stored order, repeated keys explicitly marked, parent/key identifiers and typed values |
| XML events | Namespace, start/end element and CDATA records, comments/line numbers, observed parent/matching-start relationships and mismatch diagnostics |
| Attributes | Every stored attribute ordinal, duplicate names, namespace/name/raw lexical indices, exact typed bits and id/class/style selectors |
| Resource map | Every XML name-string to attribute resource-ID mapping |
| Type-spec flags | Every stored per-entry configuration/public/staged flag word |
| Libraries | Every stored dynamic package ID/name and exact raw name code units; no library loading |

Related actions connect elements to attributes, strings to their pool/styles,
values to their string, entries/slots to configurations and resource IDs to all
local definitions. Logical IDs are packed package/type/entry selectors, **not
file addresses**. Framework, dynamic, cross-package and split references can
remain unresolved. A type-ID adjustment changes identifier arithmetic, not file
coordinates. Duplicate definitions/configurations are not silently merged.

Physical actions target the exact selected **uncompressed** snapshot. The viewer
does not invent a position for an expanded value in its compressed parent APK.
A compact entry occupies eight stored bytes even though its decoded model has
a logical eight-byte `Res_value` in addition to logical header fields. Sparse
and offset16 offsets encode units of four bytes. Absent sentinels are not reads.

Typed values preserve type code, signed/unsigned integer bits and hex, float
IEEE-754 bits/nonfinite text, dimensions/fractions with mantissa/radix/unit and
logical string/resource references. Pixel conversion requires display metrics;
fraction interpretation requires a base. AAPT2's unused all-zero CDATA typed
field is reported as absent with its raw bytes, separately from a normal
size-eight `TYPE_NULL` value.

Pool strings can export their exact encoded payload or decoded UTF-16LE code
units without a BOM. UTF-8 text export preserves representable values and
explicitly refuses isolated surrogates; JSON/UTF-16LE/original-byte exports
retain those units exactly. JSON escapes every surrogate code unit. Length
prefixes and terminators remain available through the original physical range.
Prepared exports own their bytes before the document picker and survive input
expiry or activity recreation.

## Boundaries and limits

The private non-exported `BinaryWorkerService` receives read-only descriptors
and a report pipe, with a `resources` engine selector. It refuses ordinary-UID
execution and has no in-process fallback. Resource adapter loops observe a
25-second deadline/cancellation; the existing parent has a 45-second request
deadline and requests termination of the matching isolated job. Bounds checks
precede Google model allocation. Actual Android Binder/isolated UID behavior,
process death and cancellation timing remain **unverified on a device**.

| Budget | Limit |
|---|---|
| Raw compiled input / expanded member | 64 MiB, known exact length |
| Owned APK parent | 256 MiB; ZIP inventory additionally uses the documented ArchiveStore limits |
| Worker report / transport | 32 MiB; over-limit reports fail rather than return a successful prefix |
| Chunk count / nesting | 100,000 / 64 |
| Pool strings | 200,000 logical records |
| Decoded text | 4 Mi UTF-16 units per string, 16 Mi units in aggregate, counting aliases |
| Defined entries / values / style spans | 200,000 each |
| Entry offset slots | 1,000,000 overall, at most 65,536 per type |
| XML attributes | 4,096 per element, exact supported 20-byte stride |
| Dynamic library entries | 256 per library chunk |

Supported wire layouts include UTF-8/Android modified UTF-8/UTF-16LE pools,
extended UTF-16 lengths, style holes/spans, binary XML, 284/288-byte package
headers, dense/offset16/sparse tables, compact entries and complex maps. Unknown
configuration bytes and raw flags remain visible. Known opaque overlay/staged
alias chunks keep exact ranges and mark semantic coverage partial. Unrecognized
upstream chunk/type enums, unsupported strides/layouts, malformed references,
count/range errors and output-budget errors fail explicitly. Unused/padding
bytes remain in the source, rather than being claimed as decoded fields.

`wireCoverageComplete` means every declared record in this accepted supported
inventory was retained. `semanticCoverageComplete` records whether recognized
opaque chunks were encountered. Neither establishes a balanced XML document,
platform acceptance, valid package signing, device resource selection, component
export/runtime behavior or compatibility with every Android format extension.
Canonical reconstructed XML, full device/framework/split resolution, overlays,
staged aliases and cross-split correlation remain incomplete.

## Verification

`scripts/test-resources.sh` compiles the full Google source with AutoValue under
Java 8 and exercises the actual parser plus report/backend. Generated fixtures
cover Unicode/MUTF-8/NUL/isolated surrogates, extended lengths, style holes,
duplicate attributes/maps, every supported entry layout, absent slots, aliases,
legacy package headers, exact ranges/exports, 2,051-string paging/full-value
search, parent/member SHA-256 ownership, source expiry, failure preservation,
staging and cancellation. Source tests check publisher NOTICE bytes, source
identity/idempotence and refusal to overwrite local edits/new files/symlinks.

With Android platform/build-tools 35, the same script builds independent AAPT2
reference APKs containing ordinary and forced-sparse/collapsed/deduplicated
resources, locale/night/SDK configurations, arrays/styles/plurals, scalar typed
values, namespaces and CDATA. Element/attribute counts are compared to AAPT2's
own dump, dimension/fraction decoding is checked and both APKs' manifest,
resource table and compiled XML are parsed. `DIE_REQUIRE_AAPT2=true` makes missing
SDK tools an error; Android CI requires these references. Without them the
script explicitly reports that this part was not run.

Five API 28 Robolectric viewer cases use the actual Google parser/report and
Android widgets. Only the OS worker transport is substituted. They cover
duplicate attribute/string relationships and Back/recreation; configurations,
aliases/compact extents/maps; paging/full-value search/complete filtered export;
exact code-unit picker export across expiry/recreation; and failed-member
recovery/cleanup. Full APK compilation/lint/static packaging checks are separate.
No handset/API 26/35/16 KiB runtime or real document-provider grant is claimed.
