# DiE Android — design goals and acceptance criteria

## Mission
A free, open-source, offline, expert-first Android software and binary investigation workbench. A technically curious person should be able to explore files of known and unknown format, apps and system packages, and build a genuine understanding of their structures, roles, dependencies and potential behavior. It is not a malware verdict generator or a simplified beginner scanner.

## Non-negotiable product principles
- Expert technical facts by default: preserve native terminology, names, address domains, units, raw values, flags, classifications and source evidence.
- Provide contextual explanations: definition, mechanism, relevance, observed evidence, uncertainty, implications, next investigative step. Do not infer behavior merely from a declared capability.
- Desktop DiE functional parity first. Reuse upstream CDiE and its DataStructs, format readers, disassembler and signature engine; don't invent duplicate parsers unnecessarily.
- Extend with openly licensed Android-specific engines and data (LibChecker, Exodus, UAD-NG, APK tooling, apksig, DEX decoding; later JADX and others subject to compatibility). Pin, attribute and verify dependencies; database version and match method are evidence.
- Offline by default. No telemetry, network calls, automatic cloud uploads, execution of analyzed apps or removal actions. Privileged operations require a separately designed, opt-in security boundary. No bypass of Android sandbox.
- All code and documentation should be community-readable, reproducible, modular, licensed and tested. Keep app UI separate from engine adapters and source acquisition. Keep reports exportable and machine-readable; do not hide caps, unsupported formats or errors.
- Input acquisition: SAF and shares, local readable paths, package inventory, base and split APKs, installed native libraries, archive members; future explicit Shizuku/root bridge for accessible system diagnostics and files. Never pretend to read inaccessible data.
- Services: distinguish manifest declarations, OS service manager registrations, process state and actual runtime observations. Inventory visibility is limited by user/profile and permissions. Do not claim live status from a manifest.
- File interpretation: identity, size, hashes, recognition, headers, address mapping, dependency/symbol/resource tables, entropy, strings, executable decoding and evidence; recursively inspect archive members when selected. Unknown inputs still get generic hex/hash/string/entropy exploration.
- Usability: desktop-inspired dense layout adapted for touch; search/filter, offsets and address navigation, rescan without selection, preserve relevant scan parameters, one source with several analysis tools. Provide readable report layouts without replacing actual detail.

## Architecture
Acquisition adapters -> private bounded input snapshot -> analysis orchestrator -> native tools / Android metadata / knowledge databases -> typed evidence model -> expert UI, contextual help and report exports.
Keep native code serialized until an isolation and threading review. Later move hostile file analysis into a dedicated isolated worker process with explicit cancellation and resource budgets. The current native process remains vulnerable to upstream parser defects, so do not present it as safe for arbitrary hostile samples.

## Feature parity gate
See FEATURE-PARITY.md. A feature is complete only when a local or CI fixture validates actual output and relevant device flows have been checked. A JNI entry point or successful compilation is not completion. A release should document known unsupported cases, tested Android versions and signing/reproducibility constraints.
