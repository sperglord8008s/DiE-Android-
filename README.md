# DiE Android (unofficial) — 0.4.0-alpha

Offline native Android frontend for the open-source Detect It Easy ecosystem. Independent project, not an official Horsicq application.

Development alpha with host JNI, Android UI simulation and CI build checks; handset verification and a complete audit remain pending. Do not treat a signature match or its absence as proof of safety. Use appropriate isolation for hostile samples.

## Features

- Storage Access Framework and Android share menu; no root, analytics, broad storage or INTERNET permission.
- Streaming header identification, SHA-256, Shannon entropy, touch-selectable block entropy, searchable ASCII strings and hex preview.
- Primary scanning reuses the CDiE desktop backend: exact signature source paths, format facts, hierarchy, diagnostics, deep/heuristic/recursive/aggressive/parent-format flags, overlays/resources/archives and tag filters. Calls are serialized. Full Qt engine parity is unverified.
- Shared persisted settings between Scan and the signature laboratory; captured options are exported with the result. Searchable detection evidence is paged without discarding records.
- Offline custom-rule ZIP import, native load checks, immutable SHA-256 snapshots, activation/rollback and original archive export; helpers, tag metadata and licensing notices are retained. No automatic network database updates.
- Versioned structured JSON report export using the system picker, with content identity, engine/database revisions, native evidence and explicit limitations. See [schema](docs/report-schema.md).
- Searchable, paginated maps/imports/exports/symbols/resources/instructions with every native field, contextual explanations and explicit unsupported/errors. Navigate physical offsets or unsigned preferred VAs to hex/disassembly, retain Back context, measure range entropy/digests and export exact stored resource bytes. See [contracts and limits](docs/native-records.md).
- Desktop-inspired dark native UI: persistent scan flags, Scan / Rescan, file summary, expandable signature groups with details, raw JSON log, elapsed time and progress indicator. ARM64 native linkage; no Termux. Rescan reopens the selected URI (subject to provider permissions) with current settings.

## Build

JDK 17, Gradle 8.11.1, Android platform 35, CMake 3.22.1, NDK 28.2.13676358. API 26+; Android 16 support intended but not yet validated.

```sh
bash scripts/test.sh
bash scripts/test-apk.sh
bash scripts/test-models.sh
bash scripts/fetch-engine.sh
bash scripts/test-native.sh
bash scripts/fetch-knowledge.sh
./gradlew :app:assembleDebug :app:lintDebug
bash scripts/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk
bash scripts/test-workbench-ui.sh
```

CDiE and submodules are pinned. The Gradle wrapper pins 8.11.1 with a distribution SHA-256. Build-time downloads only. GitHub Actions uploads the verified debug APK, checksum and source/data/signer build manifest. Debug signing keys and build paths can differ; bit-identical signed APKs are not claimed. Java-only fallback: `./gradlew -PwithCdie=false :app:assembleDebug` (no compiler/packer detection; the native APK verification script intentionally requires the full ARM64 build).

## Limits / roadmap

Inputs capped at 1 GiB; crash recovery and native worker isolation remain pending. ASCII preview is capped at approximately 200k characters, runs at 4096, hex preview at 4096 bytes and entropy at 256 blocks; Native Tools provides paged hex, Unicode extraction, disassembly and native tables. Main Scan and the laboratory have request-owned cancellation, with queued-cancel and recovery fixtures; native interruption remains cooperative and active interrupt latency is unverified. Some provider, metadata and rule operations must return before cancellation settles. Cache hits and byte-change invalidation have host fixtures. Native reports have explicit output limits. Archive expansion inherits CDiE's unsupported formats and some silent member-skipping limits, disclosed in exports. Device verification (including actual 16 KiB page operation), broad Qt comparison, batch/profile controls and a complete license audit remain pending. Consult the [current parity matrix](docs/FEATURE-PARITY.md) and [continuation point](docs/continuation.md).

## License

Frontend GPL-3.0-only. Commercial redistribution permitted under GPL conditions. Upstream CDiE is MIT; database and dependencies retain their original licenses. See generated notices and upstream revisions; audit before distributing a linked APK.
Upstream: https://github.com/horsicq/CDiE and https://github.com/horsicq/Detect-It-Easy

## Analysis workbench (0.3)
File 101, APK metadata and permission explanations, exported components, signing identity, archive inventory, pinned LibChecker exact-match rules and UAD-NG package knowledge. Optional offline Exodus JSON import with limited literal DEX matching. See [integration scope and limitations](docs/analysis-workbench.md).

### Expert-first interface
Technical detail is the default. Contextual help explains the actual data without replacing it with a simplified view. Raw evidence remains accessible.

## Core workbench expansion
Native Tools exposes upstream CDiE data structures, memory maps, imports, exports, symbols, resource metadata, disassembly, hashes, Unicode strings and paged hex. Explore exposes local package inventory, declared services and OS-permitted files. QUERY_ALL_PACKAGES is declared for this local inventory; no INTERNET permission. See [design goals](docs/DESIGN-GOALS.md) and [feature parity](docs/FEATURE-PARITY.md). Hardware testing and full parity remain pending.

### Verification and community development
Run `scripts/test.sh` for the generic inspector, `scripts/test-models.sh` for evidence/settings/range/export fixtures, `scripts/test-apk.sh` for real AOSP signing verification, and `scripts/test-native.sh` after `scripts/fetch-engine.sh` for Linux-host JNI fixtures (JDK 17, CMake, C compiler and Python 3). `JSON_JAR` and `APKSIG_JAR` accept cached, checksum-verified host dependencies for offline test runs. `NATIVE_BUILD_DIR` optionally retains a CMake build between native test runs.

`scripts/test-workbench-ui.sh` builds host JNI/fixtures and runs eight Android UI/lifecycle cases with Robolectric API 28 and actual JNI results. The SDK/JDK and fetched engine are required; JUnit 4.13.2, Robolectric 4.14.1 and its Android runtime are build-time test dependencies only. The test JVM keeps its download cache under `app/build/test-runtime-home`; retain it and the Gradle cache for offline reruns. The test provider captures export streams; real SAF permissions and handset behavior need device checks. Android CI runs these tests and independently cross-compiles/lints/verifies the ARM64 APK. No connected-device verification is claimed.
