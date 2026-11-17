# Development continuation point

Work is on `codex/workbench-parity`, proposed in [draft PR #1](https://github.com/sperglord8008s/DiE-Android-/pull/1), based on main `0558a89`. The application remains `org.diemobile` / `0.4.0-alpha`, with the existing signing configuration. Full desktop parity and handset verification are not complete. Read AGENTS.md and the three design/parity documents before continuing.

## Implemented and fixture-verified in this work

- The main Scan workflow uses the pinned desktop CDiE backend. Scan/laboratory settings share one migrated preferences store. Results retain exact source paths, hierarchy, parser facts and diagnostics; 50-row searchable pages retain the full model. Physical entry offsets open hex/disassembly. Export schema v1 captures the original input SHA-256, scan options, engine/database identities and native JSON.
- Bundled databases install into revision-specific private snapshots. Custom ZIP imports enforce path, duplicate, CRC, compressed/expanded size, member size and count limits. Original ZIPs, helpers, tags and license notices are retained. Native load checks precede atomic active-pointer changes; immutable paths invalidate native caches when switching/rolling back. Archive SHA-256 and enabled state are exported.
- AOSP apksig reports aggregate signer errors/warnings, v1/v2/v3/v3.1 status and an explicit manifest-minimum-to-API-35 range. Real host-created v1/v2/v3 and combined signatures, exact certificates, unsigned, tampered and truncated APKs have fixtures. Rotation/v3.1 lineage and detached v4 remain unverified/pending.
- Request-owned cancellation prevents a queued request from losing a cancel signal or cancelling another scan. Java acquisition/inspection can observe cancellation. Deterministic queued-cancel/recovery and streaming-inspector fixtures pass. Active native interruption remains cooperative; some provider/metadata/script operations must return first.
- Linux JNI fixtures validate known-rule matches, exact source, edited-rule execution, custom-DB gates, imported tag include/exclude precedence, cache hit/content invalidation, imported snapshot activation and database reload/rollback. Generated PE32/PE64/thin-Mach-O fixtures validate structures, supported maps, entry mapping and instruction decoding. Truncated headers fail explicitly. DEX/ZIP fixtures validate identification only.

## Reproduce checks and build

Use Linux, JDK 17, Python 3, a C/C++ compiler, CMake 3.22.1, Android SDK platform/build-tools 35 and NDK 28.2.13676358. Set `JAVA_HOME` and `ANDROID_HOME`. The wrapper pins Gradle 8.11.1 and its distribution SHA-256.

```sh
bash scripts/test.sh
bash scripts/test-apk.sh
bash scripts/test-models.sh
bash scripts/fetch-engine.sh
bash scripts/fetch-knowledge.sh
bash scripts/test-native.sh
./gradlew --no-daemon :app:assembleDebug :app:lintDebug
bash scripts/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk
```

For offline host tests, `APKSIG_JAR` and `JSON_JAR` accept cached dependencies but still verify their pinned checksums. `NATIVE_BUILD_DIR` retains an incremental native build directory; the default is a disposable build. Once engine/knowledge sources, Gradle/Maven artifacts and SDK components are cached, use `./gradlew --offline` for the build. First-time dependency acquisition is build-time network activity; the app has no INTERNET permission and performs no automatic database downloads.

The workflow has separate Android/lint/package, Linux JNI and APK/model jobs. The `die-android-arm64-debug` artifact contains the APK, adjacent SHA-256 file and `build-manifest.txt` with the source revision, bundled data revisions and signer identity. Packaging checks verify the APK signature, application ID/API levels, absence of INTERNET permission, bundled revision/license assets, ELF LOAD segment alignment and ZIP library placement for 16 KiB pages. Static alignment is **not** an Android 16 KiB runtime test. Debug keys, build paths and timestamps can differ between environments; bit-identical signed artifacts are not claimed. No release-signing identity was replaced. Preserve the last successful build's APK as fallback when doing device work.

Engine: `f055daa56fd3feef1db4d6dbc187cb292de24ea9`; signature data: `7ef816ee64ee36b4884da6b930a11542742affb7`. Knowledge versions remain pinned by `scripts/fetch-knowledge.sh`. No vendor checkout edits are required.

## Next priorities

1. Inspect the newest PR-head Actions jobs first. Fix regressions before adding features. Run the required host and Android checks for any changed native contract.
2. Verify handset flows: SAF/share/rescan, returning from the database manager with Custom DB enabled, failed imports preserving the prior database, activation surviving process restart, rollback/export, evidence paging/search, entry-point navigation, cancellation at each phase and report export. Repeat on API 26 and current Android, including a real/emulated 16 KiB environment. No connected-device flow was exercised in this session.
3. Extend typed evidence to memory maps, imports/exports, symbols and resources. Preserve complete native fields and unsigned addresses; use typed physical ranges/virtual-address domains to support safe hex/disassembly/entropy cross-links and selected resource extraction. Existing table output is still text. Check the pinned `cdie_*_data` headers before designing JNI records.
4. Continue Android binary XML/resources.arsc and DEX work. Current PackageManager metadata and ASCII defined-class matching are partial; DEX/ZIP recognition fixtures do not validate instruction decoding or full container integrity. Prefer pinned, license-audited upstream APIs/engines over duplicate parsers.
5. Add rotation/v3.1/v4 signing fixtures and split/base correlation, broader desktop heuristic/reference comparisons and malformed-input coverage. Follow the detailed gaps in FEATURE-PARITY.md (missing desktop hashes, archive families, batch/diff/profiles and complete per-field guidance).
6. Move native parsing into a separately designed isolated worker with cancellation/resource budgets before claiming suitability for arbitrary hostile samples. Add interrupted-import/stale-staging cleanup, power-loss recovery and snapshot storage reclamation. Existing atomic rename tests do not prove power-loss durability. Native script testing still has no cancellation scope.

The optional privileged process/service bridge remains absent. Do not infer live service state from manifest declarations or bypass ordinary UID restrictions. The complete linked/data license audit and reproducible release signing remain pending despite the bundled notices.
