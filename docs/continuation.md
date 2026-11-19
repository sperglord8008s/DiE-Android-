# Development continuation point

The verified workbench is on main through PR #1 and continues as GlassDigestor in `sperglord8008s/DiE-Android-`. Active development uses `codex/workbench-parity`; the application remains `org.diemobile` with the existing debug signing configuration. The published `0.6.0-pre-alpha.1` checkpoint is immutable; current development is `0.6.0-pre-alpha.2` / code 8. See [versioning](build-versioning.md). Full desktop parity and handset verification remain incomplete. Read AGENTS.md and the three design/parity documents before changes.

## Implemented and fixture-verified in this work

- Primary captures now export JSON/XML/CSV/TSV through the same immutable prepared ownership path. Typed XML and reversible tabular metadata/native rows preserve all UTF-16 units, unknown fields and failed-scan scope; independent standard-library parser reconstruction/large-row/budget cases and two additional primary-view cases pass (110 full-suite checks). Real providers/storage/process death and Qt serialization identity remain unverified. See [format contracts](report-formats.md).

- Captured native tree comparison is available from batch row Actions. Exact fields/ancestry/duplicate occurrences, checked report identities, relevant native versus preview-only conditions, original-row links and complete delayed exports retain every returned record and explicit failed-scan unknown coverage. Independent random/deep/field/budget fixtures and three additional real-CDiE viewer cases pass (108 full-suite checks). Cross-batch/import/program/package semantics and real provider/device/storage behavior remain incomplete. See [scan comparison contracts](scan-comparison.md).

- Exact aligned binary comparison: every paired byte/run/tail, independently verified whole-input identities, two owned snapshots, fixed side/range coordinates, atomic candidate replacement, stale-pair rejection and complete prepared exports. Independent random/seam/100,000-run/mutation/publication/retention fixtures and five comparator/widget/native-hex cases pass (105 full-suite checks). A shared viewer fix refreshes accepted input after later provider-close failure and tolerates unavailable metadata queries. Semantic/report/package diff, alignment/background jobs and handset/process-death/durability flows remain incomplete. See [comparison contracts](binary-comparison.md).

- Desktop document batches now capture complete plans/options/database revisions, privately acquire each full input, run real CDiE/facts/inspection and checkpoint every outcome. Source/report identity, incomplete/cancelled scopes, exact byte ownership, complete staged exports, configuration worker retention and active/damaged-session deletion have host and seven Android cases (100 full-suite checks). Directory/background/resumable jobs, diff/manual formats/profile attribution and device/provider/process-death/power-loss behavior remain incomplete. See [batch contracts](batch-scanning.md).

- Saved scan option profiles: all 14 shared flags, tag filters and minimum; immutable UUIDs/options, strict lossless bounded imports/exports, source hashes, revision-checked sync/atomic publication, CRUD/apply and pending-export ownership. Host file/malformed/metadata cases, shared tracker references and six widget/real-CDiE-request cases pass (93 full-suite checks). The real request exposed a nested-relative-asset installation issue; leaf parents are now created before copying. Manual format/tool settings, Qt compatibility, named batch/report profile attribution and diff, import-approval process death, damaged-private-catalog recovery and handset durability/providers remain incomplete. See [profile contracts](scan-profiles.md).

- PhotoRec 7.2 now compiles all 358 unmodified format/support sources: 349 registered groups / 774 catalog patterns, 581 searched at every byte and 193 explicit one-byte/all-zero exclusions. Header/data-only evidence preserves every searched occurrence, overlaps, negative/relative coordinates, raw statuses and unknown extents; bounded data-stop intervals enable exact immutable carving. Host naive-oracle/media/CRC/truncation/FD/budget/export fixtures and four actual-JNI viewer cases pass, bringing the full local suite to 87 (zero failures/errors/skips), plus ARM64 assembly/lint and static APK checks. The deterministic corresponding-source ZIP includes the original pinned archive and standalone adapter/build inputs. Full file checks, weak-pattern anchored scopes, codec decoding, fragment/filesystem recovery and real isolated UID/SAF/handset behavior remain incomplete/unverified. See [PhotoRec contracts](photorec-investigation.md).

- Captured package sets: the installed-package explorer hands off every exposed base/split path, and Scan opens a one-file scope. Whole APK/expanded manifest identity and CRC checks precede original field/attribute selection, exact package/version/split candidate graphs, split-type associations and verified DER-set comparisons. Source failures/duplicates/unknown values remain explicit; owned metadata/APK/manifest/certificate exports survive parent expiry/recreation. Three manifest, four document/model and five viewer cases bring the suite to 83 with independent AAPT2/real signatures; real installed paths/SAF/worker/handset and full targeting/signing/framework/runtime policy remain incomplete. See [package-set contracts](package-set-investigation.md).

- DEX: pinned Google smali/dexlib2 + baksmali 3.0.10 and Android Guava, complete licensing/source notices and standalone dependency checksums. The main report uses Unicode/MUTF-8 indexing, table counts, Adler32/SHA-1 consistency and DEX SHA-256; ZIP duplicate-name/size/CRC/budget failures are explicit. APK / SDKs opens lazy typed metadata/bytecode/debug/exception/payload/reference tables, real method smali and physical byte actions. Complete scoped exports, 64-location Back/recreation, captured versus draft filters, classic versions and 041 shared containers have host fixtures. Four report and six viewer Robolectric cases join the previous fourteen UI cases. See [DEX investigation](dex-investigation.md).
- Exact multidex declarations: a separate owned index hashes the complete parent, CRC/size-checks all named DEX candidates and retains class/source metadata independently of live parsers. Type/method/field/handle links preserve every exact declared-owner/signature candidate, including duplicates/041 shared addresses; wrong identifier owners, failed scopes and limits remain explicit. Jumps select the correct captured file/table before byte actions; full staged JSON and source-byte exports survive parent expiry. Host fixtures cover overloads, encoded duplicates, CRC/parent/cache mutation, 128-source/96 MiB limits, late cancellation and recovery. Four actual-engine API 28 cases bring the suite to 67; inheritance/framework/split/runtime linking, process isolation and handset flows remain incomplete. See [multidex contracts](dex-definitions.md).
- String investigation: 43 built-in rules, 16 overlapping groups and full rule catalog/context/identity. Whole-input UTF-8/anchored UTF-16 scanning and exact DEX MUTF-8 classification preserve physical match/range evidence; private byte/text snapshots keep exports and hex independent of source expiry. All-rule host fixtures, six string viewer cases and one added DEX viewer case pass (31 Android checks total). See [string contracts](string-investigation.md).
- Collected metadata: source-built metadata-extractor 2.22.0 (Apache-2.0) plus Adobe XMP Core 5.1.0-jc003 from pinned AOSP BSD source, with a complete compatibility/XML gate patch. Directory/tag/error/XMP views retain raw types/relationships and stream complete values to JSON; exact binary/text values are exportable. Host JPEG/EXIF/GPS/ICC/XMP/PNG/WAV fixtures and six metadata Android cases pass (37 Android checks total). See [metadata contracts](metadata-investigation.md).
- Binwalk: unmodified upstream source 26713972 (3.1.1), 111 signatures/251 patterns and actual validators, with an explicit full-blob/overlap adapter, complete unresolved/candidate/catalog evidence and exact bounded stored-byte recovery. Linux JNI fixtures cover nested media, JPEG/ICC false EOI, unknown extents, descriptor ownership and immutable carving. ARM64 builds and both native-library 16 KiB checks pass. The private isolated worker is wired; two manifest/UID-refusal Robolectric cases bring the suite to 39, but real Binder/isolated UID/crash/cancellation flow is not device verified. See [Binwalk contracts](binwalk-investigation.md).
- MPV: actual libmpv/FFmpeg runtime from the hash-pinned official mpv-android 2026-09-17 ARM64 release, with source-built descriptor/typed-node adapter and complete source/dependency/submodule/license locks. Fifty-three audited file/engine getters, every descendant/error, full runtime property names, decoder/demuxer/protocol catalogs, guarded options and offline upstream references are exposed. Real host WAV/Matroska/PCM/FFV1/SubRip/Unicode/long-tag/chapters/ownership/export/reference-HTTP fixtures and four actual-JNI viewer cases pass (43 Android checks). OS transport is substituted in these viewer tests. Source layout preparation passes; the complete upstream NDK r30 runtime source rebuild and actual ARM64/isolated UID/Binder/device flows remain unverified. See [media contracts](media-investigation.md) and [source acquisition/build](../media-engine/README.md).
- Compiled Android resources: source-built Google binary-resources 32.5.1 plus documented Java 8/UTF-16/style-hole/legacy-package patch and bounded wire inventory. Fourteen tables, local ID/string/configuration links, every supported stored entry/map/slot, whole-parent APK identity and complete JSON/encoded-byte/UTF-16LE exports are implemented. Actual source-engine/report/ownership/boundary tests and independent AAPT2 ordinary/sparse/collapsed/deduplicated references pass; five viewer cases bring the suite to 48. Worker transport is substituted in viewer tests. Opaque overlays/staged aliases, canonical XML reconstruction, framework/split resolution, real Binder/isolated UID/platform acceptance and handset flows remain incomplete/unverified. See [resource contracts](android-resources-investigation.md) and [source build](../resource-engine/README.md).
- Remaining additional user scope: full PhotoRec/JPEG Digger/fragment recovery, anchored weak-signature mode, full file/integrity checks and exhaustive media coverage. Continue worker-device validation where a device exists, Android split/framework correlation and the desktop gap list. Never claim strings/signature candidates or MPV initialization prove behavior or complete decoding.
- Whole-file hashing: all eight CRC32/MD4/MD5/SHA1/SHA224/SHA256/SHA384/SHA512 algorithms, using existing pinned MIT xxfclib plus unmodified 0BSD RHash MD4. A separate small `libdiehash` descriptor bridge, owned input and native/acquisition SHA-256 agreement supply typed File info records and the expanded Native Tools hash entry with selectors/text/JSON and fresh identity. RFC/JCA/OpenSSL/descriptor/budget/ownership fixtures and three actual-JNI cases pass (51 Android checks). Original CDiE C report API remains four algorithms. OS transport/ARM64 device behavior remains unverified. See [hash contracts](hash-investigation.md) and [engine source](../hash-engine/README.md).
- Canonical SDK/library matching: the actual pinned LibChecker v5 compiler retains all 3,053 active matchers and 2,934 effective locale payloads. Every one of its 123 regex rules compiles in the complete source-built RE2/J 1.8 engine, with all 19 upstream shared contract cases passing. The report matches native/component names, defined DEX package ancestors and original compiled-manifest component/library/permission/metadata/action names. Complete catalog/UUID/locale/original-record exports, source/compiler/content hashes and source-dirty state are exposed. Host/AAPT2/four Android cases pass (55 Android checks). Real worker/device behavior, framework/split resolution, SDK version/fingerprint providers and full tracker compatibility remain incomplete. See [SDK contracts](sdk-investigation.md).
- Offline Exodus: an independent source-built RE2/J regex-search adapter replaces dotted-literal matching, using original defined-class descriptors, the upstream short-signature gate and frozen Unicode 15 classes. It retains every imported rule/unknown field/top-level metadata, exact original numeric/Unicode JSON and SHA-256-identified snapshot bytes; strict imports reject duplicate keys, invalid UTF-8/depth/size errors and preserve the prior snapshot on failure. Unsupported syntax/names and budget-exhausted searches are explicit partial coverage. Host fixtures compare 924 searches to actual pinned upstream methods and exhaust Unicode scalar properties; five actual-engine/DEX/archive/import/export Android cases pass (63 checks total). Full Python syntax, dexdump/nested-APK equivalence, separate isolation and handset behavior remain incomplete. See [tracker contracts](tracker-investigation.md).
- Primary report exports now stage the complete captured scan/options/identity before the document picker, preserving exact Unicode and pending bytes across later state mutation, input expiry and activity recreation. Picker cancellation and destination failures discard staging; the original source remains unchanged. A direct streamed encoder preserves all 65,536 UTF-16 units in keys/values without substitution and avoids a second rendered report string. Three main-view cases bring the suite to 58; shared resource/metadata/MPV regressions pass. Real provider/process-death/copy-in-progress and handset behavior remain unverified. See [report contracts](report-schema.md).

- The main Scan workflow uses the pinned desktop CDiE backend. Scan/laboratory settings share one migrated preferences store. Results retain exact source paths, hierarchy, parser facts and diagnostics; 50-row searchable pages retain the full model. Physical entry offsets open hex/disassembly. Export schema v1 captures the original input SHA-256, scan options, engine/database identities and native JSON.
- Bundled databases install into revision-specific private snapshots. Custom ZIP imports enforce path, duplicate, CRC, compressed/expanded size, member size and count limits. Original ZIPs, helpers, tags and license notices are retained. Native load checks precede atomic active-pointer changes; immutable paths invalidate native caches when switching/rolling back. Archive SHA-256 and enabled state are exported.
- APK signing: unmodified build-verified AOSP apksig 8.9.2 now has full typed signer/certificate/DER/SPKI/extensions/scoped rotation/digest/source-stamp/diagnostic evidence, API range, explicitly selected detached v4 inputs and complete exports. Private snapshots plus an atomic latest-pair pointer survive recreation/source expiry, with failed copy/cancel preserving the previous pair. A real corrupt-tree fixture reproduces upstream v4 aggregate-status/error omissions; the adapter exposes raw flags while refusing combined success. Actual RSA/EC/DSA, rotation at APIs 28/33/35, v3.1/v4/v4.1, stamp/malformed/budget/ownership fixtures and four API 28 cases pass (71 suite checks). Real SAF/platform/handset, exhaustive algorithms, split policy and hard isolation remain incomplete. See [signing contracts](apk-signing-investigation.md).
- Request-owned cancellation prevents a queued request from losing a cancel signal or cancelling another scan. Java acquisition/inspection can observe cancellation. Deterministic queued-cancel/recovery and streaming-inspector fixtures pass. Active native interruption remains cooperative; some provider/metadata/script operations must return first.
- Native cache keys include the snapshot path as well as content/options/database identity. Repeated laboratory scans can reuse a retained snapshot; primary rescans deliberately reacquire into a new private path and normally miss that cache. Cache efficiency across primary snapshots remains future work.
- Linux JNI fixtures validate known-rule matches, unmodified bundled GLIBC library detection/exact source, edited-rule execution, custom-DB gates, imported tag include/exclude precedence, cache hit/content invalidation, imported snapshot activation and database reload/rollback. Generated PE32/PE64/thin-Mach-O fixtures validate structures, supported maps, entry mapping and instruction decoding. Truncated headers fail explicitly. DEX/ZIP fixtures validate identification only.
- Typed structures/entropy/maps/imports/exports/symbols/resources/instructions reuse the pinned native readers. All fields and exact unsigned values survive into detached models and exports. Host fixtures cover named/ordinal imports, IAT versus record offsets, export forwarders, COFF/UTF-8/absolute symbols, a known resource payload, 10,000 records and over-limit errors, high Mach-O VAs, virtual tails and disassembly domains/continuation. See [native-records.md](native-records.md) for the schema, budgets and limitations.
- Native Tools implements searchable 50-row tables, per-field contextual details, physical/VA hex/disassembly links, 64-location Back history, exact range entropy/SHA-256/CRC32, resource-byte extraction and full-table versioned JSON reports. Requests are captured independently of draft controls. Byte/report staging precedes the document picker and a pending export survives activity recreation. Table cancellation is checked before/after native reads; it does not interrupt an active parser.
- Nine API 28 Robolectric UI/lifecycle cases use the actual Linux JNI: 10,000-row paging/search/Back; full filtered-table export; disassembly row/block pages and manual architecture; unsigned VA navigation; range digest export; draft-versus-displayed mode preservation; queued cancellation/recovery; resource export across picker delay and recreation; and usable map/disassembly result space at 360×640. Parameters/actions now scroll while analysis/cancel/paging/search remain outside the scroll area. These use a test provider output stream. They do not verify real SAF grants, a handset layout, ARM64 execution or API 26/35 behavior. A separate JVM checks unavailable-JNI failures.
- `ArchiveStore` separates bounded ZIP inventory/extraction from the browser. Nested browsing restores parent folders/filters/pages and survives recreation, with explicit depth/retained-byte/text budgets. Complete-directory revalidation, expanded length/CRC32 checks, SHA-256, duplicate-name rejection and generated private output paths have host fixtures. Five additional Robolectric cases exercise real ZIP streams, Back/recreation, leaf ownership, failure/cancellation recovery and the 16-level boundary. This brings the UI suite to fourteen cases. See [archive-acquisition.md](archive-acquisition.md); cross-scan parent history, complete ZIP fields/provenance and abandoned snapshot cleanup remain pending.

## Reproduce checks and build

Use Linux, JDK 17, Python 3, a C/C++ compiler, CMake 3.22.1, Android SDK platform/build-tools 35 and NDK 28.2.13676358. Set `JAVA_HOME` and `ANDROID_HOME`. The wrapper pins Gradle 8.11.1 and its distribution SHA-256.

```sh
bash scripts/test.sh
bash scripts/test-apk.sh
bash scripts/test-models.sh
bash scripts/test-dex.sh
bash scripts/test-strings.sh
bash scripts/test-metadata.sh
bash scripts/test-resources.sh
bash scripts/test-binwalk.sh
bash scripts/test-mpv.sh
bash scripts/fetch-engine.sh
bash scripts/fetch-knowledge.sh
bash scripts/fetch-metadata.sh
bash scripts/fetch-binwalk.sh
bash scripts/build-binwalk.sh android
bash scripts/build-mpv.sh android
python3 scripts/fetch-mpv-sources.py --bundle
python3 scripts/mpv-notices.py --check
python3 scripts/mpv-references.py --check
bash scripts/test-native.sh
bash scripts/test-hashes.sh
bash scripts/test-knowledge.sh
bash scripts/test-trackers.sh
./gradlew --no-daemon :app:assembleDebug :app:lintDebug
bash scripts/verify-apk.sh
bash scripts/test-workbench-ui.sh
```

For offline host tests, `APKSIG_JAR`, `APKSIG_SOURCES_JAR` and `JSON_JAR` accept cached dependencies but still verify their pinned checksums. `NATIVE_BUILD_DIR` retains an incremental native build directory; `test-native.sh` defaults to a disposable build and `test-workbench-ui.sh` defaults to `app/build/host-native`. Once engine/knowledge sources, Gradle/Maven artifacts and SDK components are cached, use `./gradlew --offline` for the build. The UI test script generates fixtures under `app/build/host-ui-fixtures`; to rerun without rebuilding them, invoke `./gradlew --offline :app:testDebugUnitTest` with the same `-PhostNativeLibraryDir`/`-PnativeFixturesDir` properties if their defaults were overridden. The host JNI library and fixture directory are declared Gradle test inputs, so native/fixture changes invalidate an old passing result. They must exist before invoking the test task directly; the script builds them first. JUnit 4.13.2/Robolectric 4.14.1 and its API 28 Android runtime are test-only dependencies. Retain both the Gradle cache and `app/build/test-runtime-home` (Robolectric runtime cache) for offline test runs. First-time dependency acquisition is build-time network activity; the app has no INTERNET permission and performs no automatic database downloads.

The workflow has separate Android/lint/package/UI, Linux JNI, APK/model, Binwalk and media jobs. The `die-android-arm64-debug` artifact contains the APK, adjacent SHA-256 file and `build-manifest.txt` with the source revision, bundled data revisions and signer identity. Packaging checks verify the APK signature, application ID/API levels, absence of INTERNET permission, bundled revision/license assets, ELF LOAD segment alignment and ZIP library placement for 16 KiB pages. Static alignment is **not** an Android 16 KiB runtime test. Debug keys, build paths and timestamps can differ between environments; bit-identical signed artifacts are not claimed. No release-signing identity was replaced. Preserve the last successful build's APK as fallback when doing device work.

Host dependency acquisition now uses a shared checksum-verifying staging helper
for DEX/runtime dependencies, host JSON, apksig and resource-test processors.
Transient HTTP failures get bounded retries; incomplete or wrong-checksum files
are removed before publication, while existing mismatched cache bytes fail
without being replaced. `DIE_OFFLINE=true` refuses missing inputs without a
download attempt. CI shares public host inputs through ignored `.ci-cache/`
paths and an Actions cache keyed by dependency scripts/locks; every use still
checks its pin. This prevents each test step from redownloading the same JARs.
`bash tests/test-dependency-fetch.sh` exercises reuse, interrupted/incorrect
downloads, edited-cache preservation and offline no-network behavior. Actual
offline DEX, signing and required AAPT2 fixtures validate the helper's integration.

Engine: `f055daa56fd3feef1db4d6dbc187cb292de24ea9`; signature data: `7ef816ee64ee36b4884da6b930a11542742affb7`. Knowledge versions remain pinned by `scripts/fetch-knowledge.sh`. No vendor checkout edits are required.

## Next priorities

Tracker adapter 2 adds shared required-literal indexing without changing RE2/J
matching authority. All active patterns in the audited public snapshot are
indexed; complex patterns fall back to ordinary search. The 191-pattern
differential and 400-rule / 30,003-name workload fixtures preserve results/spans
and make only three actual regex calls in the large synthetic case. The API 28
tracker cases also check exposed index counts and real DEX report work. Retain
the existing scope/unsupported/deadline/device limitations; see tracker docs.

1. Inspect the newest PR-head Actions jobs first. Fix regressions before adding features. Run the required host and Android checks for any changed native contract.
2. Verify handset flows: SAF/share/rescan, database activation/failed-import preservation/restart/rollback/export, native/evidence paging and help, range/resource export with real providers, offset/VA navigation, cancellation at each phase and recreation. Repeat on API 26 and current Android, including a real/emulated 16 KiB environment. No connected-device flow was exercised in this session. Review the dense Native Tools layout on small displays, rotation and screen readers. Current parameter/page/filter and pending export survive recreation; the full Back history does not.
3. Continue inherited/framework/cross-split DEX resolution beyond exact captured declarations, framework/split resource relationships, tracker compatibility and SDK fingerprint/version providers. Canonical LibChecker matching now retains all active rules, including regex and all ten evidence type adapters; this does not prove code identity/use or implement separate SDK version providers. Binary XML/resources.arsc has source-built parser, wire evidence and navigation fixtures; opaque overlays/staged aliases and canonical XML reconstruction remain incomplete. PackageManager summary metadata, the compared Exodus regex subset, and native DEX/ZIP recognition remain partial despite separate smali/resource fixtures. Prefer pinned, license-audited upstream APIs/engines over duplicate parsers. Add package/split relationships and cross-scan parent history alongside the fixture-tested nested archive browser. The resource provider already hashes and owns its complete parent APK.
4. Extend the existing typed structures/entropy and dedicated string/hex models with broader native format/field coverage, maintaining native limits and exact domains. Whole-file hashes now have a dedicated typed adapter/viewer; string investigation already has a separate classified model. Review any new contract against pinned `cdie_*_data` headers. Add broader format/malformed and resource-language/overlap fixtures and detailed native field references without claiming complete DataStructs parity.
5. Extend the implemented captured base/split declaration graph into full targeting/signing policy across package sets, broader desktop heuristic/reference comparisons and malformed-input coverage. Separate APK rotation/v3.1/v4/v4.1 signing fixtures and typed ownership/export now pass; platform acceptance and exhaustive algorithms remain unverified. Follow the detailed gaps in FEATURE-PARITY.md (archive families, directory/background batches and diff/full Qt profiles, format-specific/fuzzy hashes and complete per-field guidance).
6. Verify the Binwalk/PhotoRec/MPV/compiled-resource/hash isolated-worker OS boundary on a device, then migrate the other native/parser engines with cancellation/resource budgets. Do not infer arbitrary-hostile-input suitability from the current manifest/host checks. Add interrupted-import/stale-staging cleanup, power-loss recovery and snapshot storage reclamation. Existing atomic rename tests do not prove power-loss durability. Native script testing still has no cancellation scope.

The optional privileged process/service bridge remains absent. Do not infer live service state from manifest declarations or bypass ordinary UID restrictions. The complete linked/data license audit and reproducible release signing remain pending despite the bundled notices.

The binary engine additionally requires Rust 1.90.0 and `aarch64-linux-android`. `DIE_RUST_ROOT` can point to a standalone toolchain and `BINARY_BUILD_DIR` retain Cargo output; Cargo's cache/source checkout and toolchain must be retained for offline use. Gradle forwards `--offline` to its native build. The worker is private and unprivileged; it is unrelated to the still-absent Shizuku/root bridge. Its isolated-process design does not migrate the other parsers or imply handset verification.

Media host/UI tests require the libmpv2 runtime and FFmpeg (or MPV_HOST_LIBRARY for a real compatible host runtime). MPV_DEPS_DIR caches the exact official Android runtime; MPV_SOURCE_DIR caches all 23 dependency/submodule archives. The CI component-source artifact retains these archives and the source-build preparation recipe. Source layout preparation is verified; the complete upstream NDK r30 engine source build is not executed locally. The app build uses verified official runtime bytes and rebuilds the descriptor adapter from source. See media-engine/README.md for offline flags and exact scope.

Resource source/host tests use `RESOURCE_DEPS_DIR` for the pinned Google source JAR, AutoValue and build-only annotations. Gradle prepares/verifies all managed sources and rejects local vendor edits rather than overwriting them. `DIE_REQUIRE_AAPT2=true bash scripts/test-resources.sh` requires SDK platform/build-tools 35 and independent packaging/dump comparisons; without that flag/toolchain the script explicitly reports the reference-fixture omission. Source and all Maven caches must be retained for an offline rebuild. See resource-engine/README.md and docs/android-resources-investigation.md for source identities, exact limits and supported wire layouts.

Whole-file hash host references additionally require OpenSSL 3 with its legacy provider for development-only MD4 comparison; the app does not invoke or link that reference command. Both `diemobile` and `diehash` host targets are built by test-workbench-ui.sh. Gradle declares both host libraries as test inputs, so direct test-task reruns need both files. RHash source is retained/checksummed in Git; no extra source download is needed for the new digest engine. The existing engine checkout supplies the other pinned crypto contexts. See hash-engine/README.md.

Canonical knowledge is regenerated by fetch-knowledge.sh through the actual pinned upstream compiler. Keep generated-assets/knowledge/libchecker-v5.json and its SHA-256, or rebuild from the pinned source checkout with compile-libchecker.py; no runtime network is used. All 19 RE2/J Java sources/full licenses are retained in regex-engine and verified before compilation, so this module needs no extra source/runtime dependency download. The catalog is a local source build of dataVersion 45, not activation of an upstream online release. Source revision, compiler/content hashes and relevant source-dirty state are recorded. test-knowledge.sh builds a temporary canonical reference; test-workbench-ui.sh now also requires/generates AAPT2 manifest fixtures under app/build/host-ui-fixtures/android-resources. See docs/sdk-investigation.md.

Tracker reference checks require Python 3.12 / Unicode 15.0.0 (selected explicitly in CI), and fetch only the SHA-256-pinned Exodus Python source and Unicode 15 source for development. `TRACKER_DEPS_DIR` caches these inputs and `EXODUS_REFERENCE_SOURCE` accepts a checked local source path. The Android artifact bundles no Exodus core/database or Python runtime; the independent adapter, full Unicode notice, frozen ranges and source/generation locks are retained in Git. Regenerate the frozen table with `python3 scripts/tracker-unicode.py --source /path/to/UnicodeData-15.0.0.txt`; ordinary builds check its retained identity without a Unicode download. See docs/tracker-investigation.md.

The optional `DEX_REFERENCE_APK=/path/to/file.apk bash scripts/test-dex.sh` audit indexes a locally provided real APK without uploading it, printing its exact SHA-256 and source/class coverage. Keep it sequential with APK assembly so the audited file cannot be overwritten during acquisition. The standard synthetic tests remain self-contained and do not need a downloaded APK. Definitions run in process, have separate 30-second cooperative acquisition/lookup deadlines, and use private generated snapshot names. See docs/dex-definitions.md for source/record/text/byte budgets and limits.

APK signing builds strictly pin and SHA-256 verify the published binary through `:app:verifyApksig`. Host `scripts/test-apk.sh` also checks the exact source archive and reviewed public/input contracts from `signing-engine/source-lock.json`. Keep `APKSIG_JAR`, `APKSIG_SOURCES_JAR` (or the sibling `*-sources.jar` cache) and `JSON_JAR` for a fully offline run. Fixture keys are generated with the JDK keytool only in development; no inspected code is executed. The signing provider remains in process with cooperative read/deadline boundaries.

The workbench UI script also generates/requires SDK 35 AAPT2 package references under `app/build/host-ui-fixtures/packages`. `:app:verifyPackageContracts` checks retained AOSP reference identities/SDK selector IDs without network activity. To audit separately retrieved originals as well, run `python3 scripts/check-package-contract.py /directory/containing/the/two/source-files` using names in `package-engine/source-lock.json`. Package set acquisition/signing/graphs retain explicit cooperative budgets; compiled XML uses the existing descriptor worker. The set scope supplies no detached idsig and does not implement APK-set archive/device targeting or cross-split DEX/resource linking.

PhotoRec source builds require CMake/JDK 17 and NDK 28.2.13676358 for ARM64. `PHOTOREC_DEPS_DIR` caches the SHA-256-pinned `testdisk-7.2.tar.gz`; the default is `.ci-cache/photorec`. `DIE_OFFLINE=true` refuses downloads. Run `bash scripts/test-photorec.sh`, `bash scripts/build-photorec.sh android` and `python3 scripts/package-photorec-sources.py`; the source ZIP/checksum are in `app/build/photorec-sources`. The CI artifact is `photorec-upstream-and-adapter-sources`. Preserve the original archive and source ZIP for FOSS redistribution. All 358 translation units are built without optional JPEG/zlib/GUI/filesystem libraries, with path opens/renames denied. See carving-engine/README.md for the exact contract and standalone bundle rebuild.

PhotoRec adapter 2 resolves the reproduced 50,000-hit transport failure with
lossless rejected-occurrence/state dictionaries and lazy expansion. All 109,023
occurrences of a local 50,223,887-byte APK complete; its 256 MiB-heap host audit
streams 176,292,786 expanded characters or about 2,305,975 complete compact
capture characters. Source SHA-256 and exact scope are in the PhotoRec contract.
The 75,000-hit fixture checks every coordinate/original ordinal/full export,
dictionary rejection and exact reconstruction; four real-JNI viewer cases
include compact-capture source expiry/picker recreation. Large/random inputs
can still exceed 250,000 records / 24 MiB output; failure remains explicit.
Anchored weak patterns, full file checks, fragmentation and handset validation
remain the next carving priorities.

Profile host checks run with `bash scripts/test-profiles.sh`; `scripts/test-trackers.sh` covers the shared `CatalogJson` extraction through the existing Exodus facade. Catalog JSON retains opaque raw number tokens via the shared streaming encoder. Android tests use actual catalog files and native engine output; the operating-system document provider is substituted. Continue batch/report comparison against captured request/input identities rather than mutable current settings.

Batch host checks run with `bash scripts/test-batch.sh`; CI also runs them in the APK/model job. `BatchScanWorkbenchTest` uses actual CDiE JNI and the existing ELF fixture. Private batches remain under `filesDir/desktop-batches`, capped at eight sessions; preserve them when testing interruption and explicitly delete through the tool when unneeded. Complete reports can outlive input bytes, but byte actions require whole-source revalidation. Never resume an incomplete journal against a newly activated database or mutable preferences.

Comparison checks run with `bash scripts/test-comparison.sh` and `ComparisonWorkbenchTest`; the existing native fixture setup provides real hex JNI. Comparison source state lives in private cache sessions, with both 256 MiB inputs and an optional temporary replacement candidate; account for up to 768 MiB source storage before separate byte/export staging. Normal close removes owned state, configuration recreation retains it, and failed restoration preserves diagnostics. Do not treat a one-sided tail as proof of insertion/deletion or apply an old range index to a replaced content-pair ID.

During the batch CI run, Android assembly/lint/100 checks and native/binary/APK-model jobs passed while the media job remained in its external apt setup for more than 20 minutes. Host media dependency steps now set explicit connection/retry limits and a 12-minute step ceiling; setup failures remain visible and can be retried without claiming engine verification. The packaged MPV runtime/source pins are unchanged.

The bounded media CI setup then exposed the actual Ubuntu 26.04 issue: `libmpv-dev` pulled the complete unused development/graphics dependency tree through a slow regional mirror and timed out. The adapter already compiles against retained pinned headers. Host setup now installs only `libmpv2 ffmpeg --no-install-recommends`; shared runtime discovery accepts an explicit library, pkg-config, or the compiler-resolved versioned SONAME without the development symlink. Runtime-only discovery with pkg-config disabled exercises the real JNI/media/C-boundary suite locally, and the helper is included in the MPV corresponding-source bundle. Preserve the 2a49de3 fallback while this isolated CI fix is verified; scan-report comparison is under development separately.

The runtime-only CI fix passed all five jobs in run 44290253981 at head 1f41a7d, including the 105-test APK and actual MPV JNI/C-boundary/source checks. A checked exact CI APK/checksum/original clean manifest is preserved locally under `/workspace/work/fallback/1f41a7d-ci`; GitHub PR checkout used synthetic merge faf806a806fec5d88812e63ac5da3b5c64da1b32, whose complete source tree equals 1f41a7d. The run/artifact/head/merge/tree/APK mapping is retained at `/workspace/work/ci-build-provenance-1f41a7d.json`. This is source identity evidence, not a claim of bit-identical debug signing. Superseded 2a49de3/57c4a72 fallbacks were subsequently reclaimed for build space; the current 912e05d local, 1f41a7d CI, 398ec18 regression and both original fallbacks remain available.

Native report comparison host checks run with `bash scripts/test-scan-comparison.sh`; `scripts/test-batch.sh` also compiles the shared model. Batch evidence caches one pair and rebuilds from checked original reports after recreation; complete prepared exports are independent. Do not collapse duplicate parent instances, turn failed scans into absent detections, use a preview minimum as a native option, or treat a disabled custom archive as an effective native rule change.

Report serialization host checks run with `bash scripts/test-report-formats.sh`; Python standard-library XML/CSV readers reconstruct the complete JSON values and exact UTF-16 identity. These schemas intentionally use JSON-encoded strings/cells, not replacement of XML-forbidden code units. Keep first-row metadata and native ordinal records together; empty/failed/null/missing scan rows are distinct. The primary Save report remains JSON, with other complete formats under Reports / Log.

The first complete format-suite run hit a database asset parent-directory creation failure with constrained local workspace storage; the record is `/workspace/work/report-formats-release-build.log`. Directory creation now uses `Files.createDirectories`, retaining precise filesystem errors and accepting concurrent directory creation. Redundant owned fallbacks were reclaimed and a fresh complete 110-test/assembly/lint/APK run passes (`report-formats-release-build-2.log`). This does not establish device low-storage/power-loss behavior. Keep heavy host fixture generation sequential with tight-disk full builds.

Native structure continuation: adapter/reference policy v1 is implemented over
`cdie_file_info_visit_slice`, without another format parser. Run
`scripts/test-native.sh` for real PE/ELF/thin/fat fixtures, independent Python
byte expectations and the exact C variant serializer. Twelve NativeWorkbench
cases include three structure cases; the combined suite has 113 checks. Keep
reviewed field domains conservative: PE data-directory addresses include the
security/certificate file-offset exception, fat offsets need a captured slice
base, and zero-fill sections have no stored range. Additional format families,
semantic relationships/per-field references, resource limits under memory
pressure and real device/parser isolation remain pending. See
[native-structures.md](native-structures.md).

Native entropy continuation: `cdie_entropy_read` supplies exact whole-input
counts, all eligible regions and a complete contiguous block partition.
`scripts/test-native.sh` additionally compares the original report and a Python
byte Counter/log2/IEEE oracle across 18 inputs/612 measurements/4,608 bins;
callback exceptions and cancellation recover cleanly. Fourteen NativeWorkbench
cases include two entropy cases; the combined suite has 115 checks. The shared
native request observer serves structures/entropy; other native tools still
lack active cancellation/isolation. Interactive zoom/charts, more region/fat
semantics, real SAF/ARM64/crash/memory-pressure and hard resource verification
remain pending. See [native-entropy.md](native-entropy.md).

Primary interpretation continuation: `ScanTarget` is an immutable input-specific
request/catalog/capture model. Main reacquires bytes, reads the real native
compatible catalog and applies one resolved selector to both facts and scan.
It attaches source/engine/selector-checked context to captured reports without
changing the profile catalog contract or batch Automatic policy. Run
`scripts/test-models.sh` and `scripts/test-native.sh` for duplicate/malformed/
identity/report and actual known-rule/All-types/cache cases. Three new real
MainActivity cases bring the combined suite to 118. Full Qt format forcing,
profile/batch interpretation policy, broader raw/malformed fixture coverage and
real device/SAF/process-death behavior remain pending. See
[scan-interpretations.md](scan-interpretations.md).

PhotoRec continuation after the v0.5.0-alpha save point: adapter 3 / native schema
2 replaces file-sized native allocation with owned pread FD windows, raises
PhotoRec acquisition to 256 MiB and leaves Binwalk at 64 MiB. `blob_input.h`
contains the parser-independent IO/lease/padding/identity layer. Run
`scripts/test-photorec.sh` for old naive/dense/callback fixtures plus standalone
reader tests and sparse/fully written 80 MiB and 256 MiB scale/cap cases. The
input-read buffer payload bound is 1.25 MiB, not a file-size or whole-process RSS
cap. Old compact captures remain accepted under their original limit. The new
corresponding-source ZIP includes both new tests and is rebuilt offline. Actual
device behavior and all prior full-file/weak-signature/fragment-recovery gaps
remain pending. See [PhotoRec contracts](photorec-investigation.md).

The immutable `v0.5.0-alpha` save point remains at `55ebdde`; subsequent development is labeled `0.6.0-alpha-dev` / code 6 to distinguish its APKs from that checkpoint.

Binwalk adapter 2 continuation: `binary-engine/src/input.rs` separates owned
CLOEXEC positional acquisition from upstream contiguous-slice parsers. It fixes
a reproduced caller seek-position change, refuses non-data/non-regular FDs,
checks ordinary source changes and reports exact copy/read/deadline contracts.
`scripts/test-binwalk.sh` now runs six Rust IO/budget tests and actual JNI
position/legacy/accounting/64 MiB read-seam/EOF cases. The original upstream and
Cargo pins remain unchanged. Keep its file-sized-copy limit distinct from
PhotoRec's 1.25 MiB input-read windows; callbacks still require the isolated
worker for external cancellation. See [Binwalk contracts](binwalk-investigation.md).

PhotoRec adapter 3 at `1b6596b` passed all five CI jobs in run 44468890115,
including 118 Android host checks, source packaging and versioned checkpoint
packaging. Its CI savepoint artifact is 14503528311; the immutable local
`0.6.0-alpha-dev-1b6596b` checkpoint records the matching source/byte identities.
The later Binwalk adapter-2 local run also passes all 118 checks, ARM64 assembly,
lint, signing/offline/identity/fourteen-library 16 KiB checks, required core/native
suites and the shared PhotoRec streaming regressions. Real device verification
remains pending.

Desktop batch interpretation continuation: schema/adapter 2 captures a separate
`ScanTarget.Request` and resolves the exact name against every acquired input's
complete catalog. `DesktopBatchAnalyzer` uses one per-file selector for native
facts/scanning; unresolved names store explicit failed reports and retain bytes.
`ScanTarget.Capture.read` checks saved source/engine/resolution/selector claims
without rewriting original/unknown evidence. Batch-wide `options.typeId` is null
for named policy; actual per-file selectors are in nested reports. Schema-1
Automatic journals stay readable. Run `scripts/test-batch.sh`,
`scripts/test-models.sh` and `scripts/test-scan-comparison.sh`; three new actual
native Android cases bring the complete suite to 121. Cancellation ownership
starts before a queued catalog call; do not move it behind engine serialization.
Per-file overrides, arbitrary forcing, saved-profile attribution and real device
flows remain incomplete. See [batch contracts](batch-scanning.md).

The first 121-test combined run hit workspace disk exhaustion while the profile
fixture copied the bundled rule database; 120 checks passed. The owned generated
MPV source ZIP was reclaimed for test headroom, retaining every pinned source
archive, and is regenerated offline after verification. This is a local storage
constraint, not a skipped check or evidence of device low-storage handling.
Comparison schema 2 retains the interpretation contexts and distinguishes null
unknown selector-dependent conditions from known flag/rule/engine differences;
the exact tree-path algorithm remains version 1.

A fresh combined run passes all 121 checks with zero failures/errors/skips,
ARM64 assembly/lint and signing/identity/offline/fourteen-library static 16 KiB
verification. The complete MPV source bundle was regenerated and all original
source pins verified. Binwalk adapter 2 at `e676f91` also passed all five CI jobs
in run 44470495585. The newest verified local development checkpoint supersedes
the older owned `0.6.0-alpha-dev-1b6596b` copy for disk space; its earlier CI
artifact remains available. Keep the frozen 0.5.0-alpha checkpoint, original
fallbacks and current source/checkpoint manifests.

Binder registry continuation: fixed normal-UID command capture is separate from
parsing and the viewer. `BoundedProcessOutput` owns IO/deadlines/cleanup;
`BinderServiceCapture` writes a new source-identified envelope; `BinderRegistry`
retains raw syntax/diagnostics/duplicates/unknowns and explicit visibility
uncertainty; `BinderRegistryBackend` owns separate envelope/output coordinates.
Recreation checks the original capture identity without rerunning the command,
and prepared exports outlive source expiry. The exact AOSP reference and full
Apache-2.0 notice are pinned, packaged and verified at build/APK time. Run
`scripts/test-system-services.sh`; three Android host cases bring the complete
suite to 124. The original normal-UID service explorer used to display all
output/error lines as registrations; that evidence error is corrected. Actual
Android command/UID/SELinux/provider/handset behavior and privileged diagnostics
remain pending. See [Binder contracts](binder-registry.md).

The per-tool [input budget/verified scale matrix](input-budgets.md) separates
implemented limits from actual sparse/fully written/real-APK host checks and
from device acceptance. PhotoRec's 1.25 MiB input-read buffer payload is not a
file-size or whole-process memory cap. A fresh full Binder integration run passes
all 124 tests (zero failures/errors/skips), ARM64 assembly/lint and APK signature,
offline, identity, source-reference and fourteen-library static 16 KiB checks.
The corresponding MPV component-source archive was regenerated with all pins
verified. Batch interpretations at `24e8b2b` passed all five CI jobs in run
44473098270, including checkpoint/source packaging.

The user has expanded the active scope to a content-first **Gallerize** view,
collapsed/expandable definitions, prioritized file/composition summaries, grouped
tools, labeled in-app media/text/contents previews, configurable byte/entropy
grids with intelligent defaults and full overrides, and broader content-based
archive recognition/decompression including embedded/disguised cases. Preserve
all existing functions. The acceptance fixture is multiple concatenated media
files plus text, visibly identified with exact source ranges. See
[gallerize-workbench.md](gallerize-workbench.md); that document records requested
work and is not an implementation claim.

Presentation foundation continuation: `ExpandableHelp` owns persistent individual
and global definition state, separate from facts/tool groups and scan settings.
`ToolCatalog` keeps all native IDs 0–10 and prior provider/expert workflows behind
purpose-based groups. Main prioritizes type/identity, removes the redundant
Reference tab, and restores old page 2 to File info; `ReferenceSections` retains
the guide with observed facts expanded. Generic/native views also collapse only
help; `statusSummary` keeps MPV/resource/carving incomplete/partial facts visible.
A targeted 27-case run exposed then verified the MPV status separation; the
PhotoRec host contract now explicitly checks the factual status API. Run
`scripts/test.sh` (which includes test-presentation), native tests and the full
127-case Android suite. See [content-presentation.md](content-presentation.md).
Gallerize/media rendering/grids and the broader archive engine remain active
next work; prototype header recognition is not that archive implementation.
The pinned libarchive release selected for investigation is 3.8.11, source
commit 03036ffa409a2aa1c5bbad35be2202641a040fda; no libarchive integration is yet
claimed. Keep the f56e397 working Binder checkpoint while this slice is verified.
The f56e397 CI package-install timeout was infrastructure-related; rerunning the
job passed all five checks in run 44477726846.

The presentation foundation passes all 127 Android host checks with zero
failures/errors/skips, ARM64 assembly and lint, required core/native suites and
PhotoRec streaming regressions. Coverage assertions now check the visible
status API independently of optional help. Source/reference/signature/offline/
static alignment verification and corresponding-source archives accompany the
checkpoint; real device behavior remains unverified. Local build headroom was
increased by reclaiming only the owned extracted-CMake installer ZIP and unused
NDK LLDB debugger library; compiler/linker/runtime inputs and original pinned
engine source archives remain retained.

Gallerize continuation: `ContentMap` maps accepted/native-row identities, bounded
artifacts, unassigned unions/gaps and strict gap UTF-8 spans; unknown extents
stay null. `GallerizeSession` owns source/capture SHA-256 identities and restores
the original capture without rerunning native work. Its PhotoRec backend borrows
the existing input, preserving caller ownership on failure/close. `ByteGrid`
streams every byte into exact manual/automatic row-major cells and exports all
values/ranges/IEEE representations. `ContentGalleryAdapter` owns one 24-card
page and deduplicates bounded thumbnails; GallerizeActivity handles navigation/
staged exports, and ContentPreviewActivity owns separately acquired exact ranges
for first-frame images, text/hex, local playback controls and paged PDF wiring.
The main tab uses tag 8, preserving old Reference/page mappings and every native
or provider action. APK verification checks both new activities remain private.

Run `scripts/test-gallerize.sh` with the existing PhotoRec/JSON dependencies. It
checks four concatenated media files plus Unicode text, exact derived bytes,
full native/catalog exports, capture corruption/refusal/restoration, UTF-8 IO
seams, independent means/log2 entropy/partitions/IEEE exports and a fully written
80 MiB + 17-byte input with a PNG beyond 64 MiB and Unicode tail. Seven Android
host cases bring the full suite to **134, zero failures/errors/skips**. The
combined ARM64 assembly/lint, required core/native, PhotoRec streaming, shared
Binwalk and APK signature/offline/identity/source-reference/fourteen-library
static 16 KiB checks pass. MPV/PhotoRec source ZIPs were regenerated and verified.
Platform media/PDF decoding, actual ARM64/isolated UID/Binder/SAF, process death,
performance and 16 KiB runtime remain separate unverified acceptance work. See
[gallerize-implementation.md](gallerize-implementation.md) for exact scope,
budgets, missing animation/video thumbnails and other remaining requirements.

Presentation at `46f95c0` passed all five CI jobs in run 44546813328; its savepoint
artifact is 14543560216. The verified local content-presentation checkpoint
supersedes the older owned Binder checkpoint for space; the frozen 0.5.0-alpha,
original fallback APKs and current checkpoint remain preserved. Binder's CI
savepoint 14538634930 remains available in run 44477726846.

Broader archive investigation: original libarchive 3.8.11 source commit
03036ffa409a2aa1c5bbad35be2202641a040fda, codeload archive SHA-256
87d05df74305b7f16b784852e49f434e09ce2b3bb29423461f717fc988742a78.
A host-only source-library prototype exists under
`/workspace/work/archive-integration/host-library`, with logs and the original
archive alongside it. This is **not Android archive integration**. The host
prototype currently found zlib/zstd, not bzip2/liblzma/lz4. The existing Cargo
cache includes liblzma-sys 0.4.9's original XZ 5.8.4 library sources. Pin/build
all selected codecs for both host/Android; do not claim missing codecs work.
Register only reviewed format/filter functions; never use external-program
filters, path extraction or disk readers. The mtree reader has an optional
`checkfs` path-opening mode (default false); exclude it or explicitly keep all
filesystem resolution disabled. An all-formats/all-filters helper can clear
warnings or register external-program fallbacks, so it is not the intended
adapter contract. Next: descriptor-only bounded acquisition, typed complete
archive entries, safe generated-name member bytes and content-based/nested/
embedded browsing, then richer rendering and the sourced knowledge cards.

Archive-result correction: the first Gallerize release opened the ZIP browser
without receiving its selected-member result. Both Gallerize and ContentPreview
now use a result request and the shared `ArchivePreviewLauncher` /
`ArchiveMemberHandoff` boundary. It rejects paths outside the browser's owned
leaf directory, acquires the exact expected SHA-256 into a separate preview,
releases the old temporary, and labels original physical offsets as unavailable
for decompressed members. A real outer ZIP → nested ZIP → Unicode text case
exercises both callers and parent closure, with forged-path refusal. Printable
strict UTF-8 headers now select text automatically; manual overrides remain.
Unused grid cells are magenta to distinguish them from valid grayscale data.
The targeted 13 checks and complete **135-case suite** pass with zero failures,
errors or skips, plus core/80 MiB host fixtures, ARM64 assembly/lint and static
APK checks. Actual handset/SAF/media/worker behavior remains unverified.
Gallerize at `11b5af9` passed all five CI jobs in run 44552943212; its immutable
CI savepoint artifact is 14545440333 and APK artifact is 14545510241.

Broader archive core now exists in `archive-engine/`, with fully pinned original
libarchive/bzip2/XZ/LZ4/Zstandard source archives, complete API/license contracts,
verified notices and reproducible host/ARM64 builds. `ArchiveEngine` is the JNI
contract; `archive_reader.cpp` owns regular positional input, explicit format/
filter registration, complete or explicitly failed metadata scopes and exact
selected-member logical output to a separate empty writable FD. No sample
pathname, disk reader, path extraction or external-program filter is accepted.
Source metadata/xattr/ACL/sparse/link/Mac state and raw/UTF-8 accessor bytes remain
separate. Public digest getters return buffers even when unset; their presence
is unknown rather than a claimed declared checksum.

`test-archive-engine.sh` passes **25 captures and 37 exact acquisitions**, including
independent TAR/PAX/ZIP and gzip/bzip2/XZ/LZMA/Zstandard/LZ4 frames, pinned 7z,
RAR5, CAB STORE/MSZIP/LZX and compressed ISO references, a **16 MiB + 17-byte**
member, embedded scopes, duplicate names, link/CRC/refusal/FD/size checks and a
direct unmodified CAB header-position oracle. CAB positions vary when data is
read versus skipped; both original observations are retained, excluded from
stable identity and never promoted to physical offsets. LZMA memory estimates/
decoder limits and Zstandard windows are capped at 64 MiB through linker
wrappers; valid excessive-dictionary/window inputs are independently decoded
then refused here. Those caps do not bound whole worker memory. Android
cross-compilation/static 16 KiB alignment and direct program/path-import checks
pass. The source ZIP was unpacked separately and rebuilt offline with the same
host fixtures successfully; original archives, corresponding-source bundle and
logs remain retained. The owned temporary standalone rebuild is reclaimed after
verification. The unused NDK libclang-cpp tool-support library was reclaimed;
compiler/linker/libclang C API inputs, sources and APK checkpoints remain intact.

**Next integration is still required:** add descriptor-only archive inspect/
selected-output IPC to the isolated worker/client, source-bound typed inventory
and acquisition validation, hierarchy/nested Back/owned byte transfer, exports/
preview links and APK notice/source packaging. Current app archive UI remains
ZIP-family; do not claim the new core is exposed in the APK yet. New CI steps
compile the ARM64 core, run real host fixtures and upload corresponding sources.
See [module contracts](../archive-engine/README.md). Keep the current working
Gallerize APK while this is wired. No device runtime, worker UID/Binder/cancel/
crash/SAF, decoder/PDF/media or 16 KiB runtime behavior is verified.

## GlassDigestor identity and numbered pre-release (20 November 2026)

The project display name is now GlassDigestor. The repository URL and
`org.diemobile` application ID remain stable; Detect It Easy/CDiE is credited as
an engine. The maintainer selected the generated lens/layers logo. Android's
adaptive icon uses the unchanged original artwork; provenance/license are in
`docs/branding/README.md`.

`version.properties` now supplies the numbered `0.6.0-pre-alpha.1` / code 7
checkpoint. Debug versions append a Git revision and optional `.dirty`; the APK,
installed version, build details, verifier and checkpoint packager share that
contract. Scripts resolve AGP's actual output metadata instead of expecting
`app-debug.apk`. See `docs/build-versioning.md` and release notes. The prior
`v0.5.0-alpha` tag remains immutable.

Archive-core commit `455ec6a7b204e5e224b879311fa397f8f424c4c1` passed all five
jobs in run `44565494822` (#117), including the actual archive host fixtures and
ARM64 core build. Its saved checkpoint is artifact `14553815003`; archive
corresponding sources are artifact `14553021058`. The old execution workspace
was replaced; its local scratch/APKs/signing key are no longer present here.
Pushed source and retained CI artifacts are the recovery points. The current
workspace was restored from the remote branch and pinned dependencies. Debug
signing configuration is unchanged; independent keys must not be presented as
the same signer.

The unfinished archive worker/browser edits from that replaced workspace were
not committed. Reapply the descriptor-only `ArchiveRunner`/worker acquisition
protocol, source-bound metadata/payload store and broader archive tree as the
next functional slice. Preserve the existing ZIP browser and its checked
member handoff. The broader libarchive core is source/host/ARM64 verified,
**not yet an exposed app feature** in this pre-release. Device/SAF/isolation/
playback verification and remaining desktop parity work remain outstanding.

The numbered/branding slice passed local ARM64 assembly, lint and the updated
APK verifier, including the installed label/version and all 14 native libraries.
A cold Gradle build also reproduced a preparer refusal of an empty precreated
output directory. The preparer now adopts only an empty unmanaged directory;
unknown files, source edits and symlinks still fail, with a regression fixture.

## Default branch and repeatable publication

The verified GlassDigestor checkpoint was integrated through PR #1 into main
`1fa7bb39cde2342049112d0648ed83bb93899758`. Its tree equals development commit
`a30a1646677cbcc86a2dbe2f65d1358f14499edc`. All five CI jobs in run
`44606318262` (#119) passed, and the same development tree passed 135 local
Android host tests with zero failures/errors/skips.

The captured CI source is the generated merge commit
`119e255a9150099f6c54b09a9a6f161e0d68c1c2`; the pre-release uses that exact
revision, not a relabeled rebuild. Its APK SHA-256 is
`2b47ab8c3787cba625cc3939275649e50dfdd24145a8f83e0f1beb5a0d8d60de`.
All four artifact digests/inner inventories and the downloaded APK's static
signature/version/offline/library/alignment checks were independently verified.
The environment can create release metadata but its credential route cannot
upload to uploads.github.com. Publication therefore uses the dedicated
repository workflow with GitHub's normal token; see `docs/build-versioning.md`.
The workflow leaves failures in a draft and never overwrites assets or tags.

Continue the Binwalk/PhotoRec checked exports and gallery/owned-preview handoff
with dense, consistent formatting, then complete the broader native archive
worker/tree. Preserve complete raw evidence and unknown extents. Hardware,
media runtime, actual isolated UID/SAF and full desktop parity remain pending.

## Checked recovery preview/export slice

The working recovery slice connects actual Binwalk/PhotoRec bounded findings to
independent previews and checked named exports, adds source-independent preview
byte exports with picker recreation, and hands captured whole inputs to
Gallerize through validated private ownership. Four new API 28 actual-JNI
cases and the existing twelve carving/gallery cases pass. Shared acquisition
checks, the fully written 80 MiB gallery/grid fixture, generic/native scanner
checks and complete SDK/tracker host contracts pass. The full 139-case Android host suite passes with zero failures/errors/skips;
ARM64 assembly, lint and APK signature/version/offline/static-alignment checks
also pass. CI for this slice is the next gate. See
`docs/recovered-content.md` for implemented scope and unverified runtime limits.

The public release is https://github.com/sperglord8008s/DiE-Android-/releases/tag/v0.6.0-pre-alpha.1
with exact source `119e255a9150099f6c54b09a9a6f161e0d68c1c2`, APK SHA-256
`2b47ab8c3787cba625cc3939275649e50dfdd24145a8f83e0f1beb5a0d8d60de`
and all 14 verified assets. The publisher now streams directly to the checked
draft ID, preserves every metadata identity field on updates, validates the
returned public tag/source and can verify an already published release without
rewriting it. Six contract cases and successful publication/idempotence runs
`44614616127` / `44615704588` document these paths. The fix is merged through PR #3 at
`f6ea298e61b82a1c0cc296ab372b57d2048e7d65`; the published artifact bytes remain
unchanged. All five jobs in fix CI run `44616666002` (#126) passed.
