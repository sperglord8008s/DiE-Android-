# Android worker runtime laboratory

This module exercises the actual production `BinaryWorkerService`,
`BinaryWorkerClient`, hash/archive JNI and descriptor acquisition on Android.
It is a separate **test-only x86_64** application,
`org.diemobile.runtimecheck`, with no launcher or network permission. The
production ARM64 application remains `org.diemobile` with its existing signing
configuration. This module is not an alternative user build.

`stageWorkerSources` copies an explicit set of unchanged production files into
an isolated source root. The static verifier compares every staged byte to its
original. There is no substitute service, fake native engine or test opcode in
production. Unexercised Binwalk/PhotoRec/MPV Java bridge references are retained;
the laboratory packages only the real hash and archive native libraries. Google
resource-parser Java dependencies are compiled because the shared service
references them; resource parsing is not covered by these five runtime checks.

## Reproduce

Use JDK 17, Gradle 8.11.1, SDK/build-tools 35, NDK 28.2.13676358 and CMake
3.22.1. Fetches use the existing pinned source/patch/checksum contracts. Retain
the dependency caches and toolchains for later `--offline` builds.

```sh
bash scripts/fetch-engine.sh
./gradlew --no-daemon :runtime-worker:assembleDebug :runtime-worker:assembleDebugAndroidTest
python3 scripts/verify-worker-runtime.py
# Select exactly one running, disposable x86_64 API 26 or API 35 emulator.
# ANDROID_SERIAL can select it when more than one device is connected.
RUNTIME_EXPECTED_API=35 python3 scripts/run-worker-runtime.py
```

Runtime evidence requires a clean committed source checkout; local dirty
compilation/static inspection is supported but is not accepted as CI runtime
evidence. `adb install -t` installs only the two generated trusted test APKs;
the laboratory does not install or execute an inspected application.

The separate [runtime workflow](../.github/workflows/android-worker-runtime.yml)
executes both API levels with KVM on disposable GitHub runners. Its emulator
action is pinned to source commit
`a421e43855164a8197daf9d8d40fe71c6996bb0d` (Apache-2.0, build-only). Emulator
system images are SDK-managed test tools; captured `getprop` records the actual
Android build. This is separate from the existing five-job ARM64/source release
gate. A workflow file or successful APK compilation is not runtime evidence.

## What is checked

1. A real normal-UID client binds the private isolated service, passes a
   read-only descriptor and receives all eight native digests. UID and engine
   identity, complete byte counts, MD4 and CRC32 known vectors are asserted.
2. A fully written **16 MiB + 17 byte** patterned file crosses that same
   boundary. Six native MD/SHA digests and CRC32 are compared with independent
   platform JCA/CRC implementations; no sparse/truncated fixture substitutes for
   the full input.
3. A gzip behind a 37-byte prefix and a misleading `.jpeg` name is inventoried
   and extracted through actual read/write descriptors and libarchive. Every
   decoded byte and an independent payload digest are compared. The normal
   client's input/output alias guard must refuse before modifying the source;
   the source identity is rechecked. Host native fixtures separately cover the
   JNI alias guard.
4. A real report pipe is filled to `EAGAIN`, returned to blocking mode and sent
   to the actual worker. A wrong request token must leave the worker alive;
   the correct token must disconnect the blocked worker while the parent
   application survives. No oversized report or timing-sensitive large parse
   is needed to keep the request active.
5. A fresh ordinary client completes another native hash request after worker
   death, demonstrating recovery.

The instrumentation writes a complete private JSON report even on failure.
The runner retains that report, instrumentation/install logs, emulator
properties and Android process logs. `runtime-build.json` records source-file
and APK SHA-256 identities; the runner rechecks the APKs before installation.
CI uploads these with the signed test APKs under
`android-worker-runtime-api-26` and `android-worker-runtime-api-35`.

## Limits and licensing

Passing these checks would verify only the stated x86 Android worker contracts.
It would not verify production ARM64, a physical handset, SAF grants/providers,
UI/accessibility, other engines, playback/PDF, arbitrary hostile-input
resilience, all crash modes or 16 KiB-page execution. See
[feature parity](../docs/FEATURE-PARITY.md) for current results and gaps.

The laboratory and adapters are GPL-3.0-only. The APK includes the original
CDiE/xxfclib, RHash, libarchive/codec, Google parser and Guava notices. Source
pins, unmodified upstream files, reviewed patches and build recipes remain in
the same repository modules as production. Compiler caches and build outputs
are ignored and are not repository source.
