# DiE Android (unofficial) — 0.4.0-alpha

Offline native Android frontend for the open-source Detect It Easy ecosystem. Independent project, not an official Horsicq application.

Source prototype, not an audited or device-tested release. Android compilation, native linking and signature results need CI and device validation. Do not treat a signature match or its absence as proof of safety. Use appropriate isolation for hostile samples.

## Features

- Storage Access Framework and Android share menu; no root, analytics, broad storage or INTERNET permission.
- Streaming header identification, SHA-256, Shannon entropy, touch-selectable block entropy, searchable ASCII strings and hex preview.
- Primary scanning reuses the CDiE desktop backend: exact signature source paths, format facts, hierarchy, diagnostics, deep/heuristic/recursive/aggressive/parent-format flags, overlays/resources/archives and tag filters. Calls are serialized. Full Qt engine parity is unverified.
- Shared persisted settings between Scan and the signature laboratory; captured options are exported with the result. Searchable detection evidence is paged without discarding records.
- Versioned structured JSON report export using the system picker, with content identity, engine/database revisions, native evidence and explicit limitations. See [schema](docs/report-schema.md).
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
```

CDiE and submodules are pinned. The Gradle wrapper pins 8.11.1 with a distribution SHA-256. Build-time downloads only. GitHub Actions uploads debug APKs if compilation succeeds. Java-only fallback: `./gradlew -PwithCdie=false :app:assembleDebug` (no compiler/packer detection).

## Limits / roadmap

Inputs capped at 1 GiB; crash recovery and native worker isolation remain pending. ASCII preview is capped at approximately 200k characters, runs at 4096, hex preview at 4096 bytes and entropy at 256 blocks; Native Tools provides paged hex, Unicode extraction, disassembly and native tables. Desktop cooperative cancellation is wired but runtime/race checks remain pending. Cache hits and byte-change invalidation have host fixtures. Native reports have explicit output limits. Archive expansion inherits CDiE's unsupported formats and some silent member-skipping limits, disclosed in exports. Device verification, broad Qt comparison, batch/profile controls, 16 KB page-size verification and a complete license audit remain pending. Consult the [current parity matrix](docs/FEATURE-PARITY.md), rather than treating a successful build as runtime verification.

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
Run `scripts/test.sh` for the generic inspector, `scripts/test-models.sh` for evidence/settings/export fixtures, `scripts/test-apk.sh` for real AOSP signing verification, and `scripts/test-native.sh` after `scripts/fetch-engine.sh` for Linux-host JNI fixtures (JDK 17, CMake, C compiler and Python 3). `JSON_JAR` and `APKSIG_JAR` accept cached, checksum-verified host dependencies for offline test runs. `NATIVE_BUILD_DIR` optionally retains a CMake build between native test runs. Android CI cross-compiles the ARM64 APK independently; no connected-device verification is claimed.
