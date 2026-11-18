# Canonical SDK and library name investigation

The main APK / SDKs report now uses the complete canonical LibChecker v5 matcher
catalog, replacing legacy exact-description lookup. **APK / SDKs → Canonical
SDK / library rules** opens all definitions, original matcher fields, effective
locale payloads, source identities and library-UUID relationships. Definitions
remain distinct from observed input-name associations.

## Canonical source and metadata

`scripts/compile-libchecker.py` invokes the actual compiler from pinned
LibChecker-Rules `4189fc0de0a0d67f0e729a5b9699a52e9ff3ffcf` (Apache-2.0), checks
the portable artifact's size/SHA-256/metadata, then retains **every active
matcher and complete effective per-matcher locale payload**. Current source has
3,053 matchers: 2,930 exact and 123 regex, with 2,934 detail payloads. Orphan
descriptions never become rules; no-detail matchers never borrow sibling or
library descriptions. Original labels, IDs, UUIDs, regex names, priority,
icon identifiers, detail paths, metadata extensions and all languages survive.
English labels/descriptions are used when available, with original-label
fallback; no translation is invented.

The catalog is a **local source build**, with the upstream compiler's dataVersion
45. This does not activate or claim a published upstream v5 data release. Report
provenance includes source revision, compiler revision, canonical content hash,
upstream portable-artifact SHA-256, the complete compiled catalog SHA-256 and
whether relevant source/compiler files had local edits. Runtime acquisition
checks the complete rule/detail inventory. The APK verifier checks the catalog
bytes against both the generated input and its full SHA-256.

The catalog browser owns a bounded private copy of the catalog itself; its byte
source and hashes do not refer to the selected APK. It pages/searches every
record, scopes by all ten types or unsupported regex, groups by library UUID and
retains Back/recreation. Full selected/table JSON exports preserve the original
rule object separately from its displayed label and include every effective
locale. UI filters/pages do not reduce complete table exports. Catalog browsing
can continue after the originally selected file expires.

## Matching contract

The app builds all 19 unmodified RE2/J 1.8 sources under BSD-3-Clause. Source
JAR/per-file identities and full licenses are retained; see
[the engine module](../regex-engine/README.md). The adapter follows the upstream
portable v5 contract:

1. Exact, case-sensitive equality is attempted across every row, including the
   literal name/pattern of a regex row.
2. Otherwise regex rows run in ascending `(priority,id)` order, with the first
   **whole-string** match winning. This is not a search for a substring or every
   possible association; all rule definitions remain inspectable.
3. ASCII digit escapes are normalized lexically, preserving escaped backslashes
   and character-class context. No Unicode/case normalization is applied.
4. Regex evaluation excludes LF, CR, NEL, line separator and paragraph separator
   in input. Unsupported portable syntax is retained as an explicit diagnostic,
   while literal exact lookup remains available.

All 123 regex rules in the pinned catalog compile. RE2/J avoids recursive
backtracking; the adapter limits patterns to 1,024 UTF-16 units, names to 16,384
units and catalogs to 20,000 rows. Rules outside the supported contract are not
silently treated as exact literal matches for different inputs.

## Observed input names

| Type | Collected evidence |
|---|---|
| 0 native | Conventional `lib/<ABI>/*.so` entry basenames in the bounded APK directory; these are names, not verified machine-code identities |
| 1 service | PackageManager-reported names and original compiled-manifest `service` name attributes |
| 2 activity | Reported/compiled `activity` and `activity-alias` names |
| 3 receiver | Reported/compiled receiver names |
| 4 provider | Reported/compiled provider names |
| 5 DEX | Distinct package ancestors derived from defined Unicode/MUTF-8 class names; report links each association to a witness class |
| 6 shared/static library | Compiled `uses-library`, `uses-static-library`, `uses-sdk-library`, `static-library` and `sdk-library` name declarations |
| 7 permission | Compiled permission request/definition/group/tree names; the current pinned catalog has no type-7 matchers |
| 8 metadata | Compiled `meta-data` names; current pinned catalog has no type-8 matchers |
| 9 action | Compiled action names, including names mentioned in visibility queries; a mention does not establish an implemented handler |

The compiled-manifest path reuses the private `resources` worker and actual
Google parser. It extracts an unambiguous manifest with directory, expanded
size/CRC32 and SHA-256 checks. `android:name` is selected through its compiled
resource-map ID (`0x01010003`), with an explicitly labeled namespace/name fallback
only when no mapping exists. Relative component names are resolved against an
unambiguous literal manifest package, retaining the original spelling. Conflicts
or nonliteral/referenced names produce diagnostics instead of invented values.
Attribute ordinals, source selection method and physical offsets refer to the
exact **uncompressed manifest**, never a guessed compressed-APK position. The
temporary child is deleted on success/failure/cancellation.

Manifest names are source observations, not Android platform-acceptance or live
component evidence. Unknown/reference-derived values, framework/split resolution
and malformed relationships can remain incomplete. The underlying binary XML
viewer retains raw records and all typed values. DEX matching uses definitions,
not runtime loading/call observations. Names can be renamed, imitated or
obfuscated. A match is neither a malware verdict nor proof that an SDK was used.

## Scope, budgets and remaining work

Archive, DEX and resource acquisition retain their existing documented limits.
The main metadata summary inspects at most 10,000 directory entries and 500
PackageManager entries per component category; these limits remain explicit.
DEX package evidence is limited to 250,000 distinct ancestors, 16 Mi UTF-16 units
and 64 hierarchy levels. The human-readable APK summary has a 16 Mi-character
budget and labels overflow partial; complete raw resource/DEX/catalog tools have
their own scopes and budgets. Canonical catalog copies are at most 8 MiB.

Canonical regex matching and detached manifest-name interpretation run on the
app's background worker; RE2/J is not a separate isolated process. The resource
decoder uses the existing descriptor-only isolated-service integration. Actual
Android Binder/isolated UID/deadline behavior remains device-unverified.
PackageManager/DEX/signing parsers retain their separately documented in-process
and coverage limitations. No inspected app/class is executed or installed, and
no engine/database update or reputation request happens at runtime.

Remaining SDK scope includes full split/framework reference resolution,
fingerprint/version providers such as Flutter/AndroidX details, tracker-engine
compatibility, observed live use and broader device validation. User-imported
Exodus data now has a separate regex-search adapter with frozen Unicode classes,
complete original metadata/catalog exports and explicit unsupported syntax; see
[tracker contracts](tracker-investigation.md). Its compared subset does not imply
complete Python regex or upstream acquisition equivalence. UAD-NG remains a
separate exact package-ID/community-guidance catalog.

## Verification

`scripts/test-knowledge.sh` builds the actual canonical artifact and RE2/J source,
checks every rule/detail, compiles all 123 regex and runs all 19 shared upstream
cases. Real native/service/DEX/shared-library/action rules, no-detail/locales,
original object exports, UUID scoping/paging, owned catalog cleanup, cancellation,
unsupported syntax, input limits and a nested-repetition non-backtracking case
pass. `scripts/test-resources.sh` additionally uses real AAPT2 ordinary/sparse
APKs to verify all eight compiled-manifest name records, Android attribute-ID
selection and relative activity/receiver resolution through the actual parser.

Four API 28 Robolectric cases exercise the actual catalog/engines and widgets:
catalog pages/locales/UUID Back/recreation; complete filtered export and reload
after selected-file expiry; real archive regex plus derived DEX package evidence;
and AAPT2 manifest component/static-library/action matches with child cleanup.
Only OS resource-worker transport is replaced. They bring the full suite to 55.
ARM64 build, lint and static APK checks are separate from handset/API 26/35/16 KiB
runtime and real SAF grants; those remain unverified.
