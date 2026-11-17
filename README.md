# DiE Android (unofficial) — 0.2.0-alpha

Offline native Android frontend for the open-source Detect It Easy ecosystem. Independent project, not an official Horsicq application.

Source prototype, not an audited or device-tested release. Android compilation, native linking and signature results need CI and device validation. Do not treat a signature match or its absence as proof of safety. Use appropriate isolation for hostile samples.

## Features

- Storage Access Framework and Android share menu; no root, analytics, broad storage or INTERNET permission.
- Streaming header identification, SHA-256, Shannon entropy, touch-selectable block entropy, searchable ASCII strings and hex preview.
- JNI binding to CDiE's public DIE_ScanFileA function; serialized calls, pinned bundled signature database, deep/heuristic/recursive/aggressive/parent-format flags. Explicit engine errors.
- JSON report export using the system picker.
- Desktop-inspired dark native UI: persistent scan flags, Scan / Rescan, file summary, expandable signature groups with details, raw JSON log, elapsed time and progress indicator. ARM64 native linkage; no Termux. Rescan reopens the selected URI (subject to provider permissions) with current settings.

## Build

JDK 17, Gradle 8.11.1, Android platform 35, CMake 3.22.1, NDK 28.2.13676358. API 26+; Android 16 support intended but not yet validated.

```sh
bash scripts/test.sh
bash scripts/fetch-engine.sh
gradle :app:assembleDebug
```

CDiE and submodules are pinned. Build-time downloads only. GitHub Actions uploads debug APKs if compilation succeeds. Java-only fallback: gradle -PwithCdie=false :app:assembleDebug (no compiler/packer detection).

## Limits / roadmap

Inputs capped at 1 GiB; cache copy removed normally, crash recovery and native isolation still needed. ASCII preview capped at approximately 200k characters, runs at 4096, hex first 4096 bytes, entropy up to 256 blocks. No native cancellation yet. Recursive detection flag is exposed but not validated. Add APK tree, paged hex, Unicode, disassembly, imports/exports, signature evidence, database import, batch/profile controls, instrumentation and malformed-file tests, 16 KB page-size verification and license audit before release.

## License

Frontend GPL-3.0-only. Commercial redistribution permitted under GPL conditions. Upstream CDiE is MIT; database and dependencies retain their original licenses. See generated notices and upstream revisions; audit before distributing a linked APK.
Upstream: https://github.com/horsicq/CDiE and https://github.com/horsicq/Detect-It-Easy
