# PhotoRec format adapter

This builds **actual unmodified PhotoRec 7.2 format sources** at
`281be432dd79121e08e0898887a9ee1f30fb3e96`. The full upstream archive and reviewed
API/registration/reference files are SHA-256 pinned in `source-lock.json`.
PhotoRec sources are GPL-2.0-or-later; this adapter is GPL-3.0-only. Full copyright
headers and GPL text are bundled in the app. The result is part of the GPL-3.0
Android application.

`prepare-photorec.py` extracts only the verified archive, rejects unsafe archive
members and generates the source list from the original `src/Makefile.am`.
All 358 format/support translation units are compiled without optional JPEG,
compression, GUI or filesystem libraries. Upstream sources are not patched.
`config.h` documents the fixed POSIX/API-26 capabilities and compiler byte order.

`probe.cpp` calls the original registration, standalone safe-header and streaming
data callbacks. Its shared Aho-Corasick index searches every byte and preserves
every occurrence for searched patterns, including relative-offset, short,
overlapping, nested, rejected and negative-start magic. The original 774-pattern
catalog is always retained; 193 one-byte/all-zero patterns are explicitly marked
unsearched because their whole-blob expansion requires a separate anchored scope.
There are 581 searched patterns and 349 registered upstream format groups.
These are catalog counts, not a claim of complete validation of 349 file formats.

The adapter does **not** invoke full `file_check`/`file_rename` callbacks or the
PhotoRec recovery program. Auxiliary signature acquisition is disabled. Every
upstream translation unit redirects path-based `fopen`/`rename` to denying
functions; timestamp-changing capabilities are not configured. The app supplies
read-only input bytes. No command, codec subprocess or inspected app is executed.

Only a positive, source-bounded `calculated_file_size` after `DC_STOP`, within the
upstream minimum/maximum and without an offset error, enables stored-byte carving.
An extent remains an upstream data-check report, not full integrity verification:
PNG CRC errors, for example, do not prevent its chunk-boundary callback stopping.
See [evidence contracts](../docs/photorec-investigation.md).

```sh
# JDK 17 and CMake on Linux
export JAVA_HOME=/path/to/jdk17
bash scripts/test-photorec.sh
# Android SDK / NDK 28.2.13676358; static C++ runtime and 16 KiB alignment
export ANDROID_HOME=/path/to/android-sdk
bash scripts/build-photorec.sh android
# Archive plus all adapter/build/notice sources, deterministic ZIP and checksum
python3 scripts/package-photorec-sources.py
```

After the verified tarball exists in `.ci-cache/photorec` (or
`PHOTOREC_DEPS_DIR`), set `DIE_OFFLINE=true` to prohibit downloads. Missing or
edited cache inputs fail explicitly. The source ZIP carries that verified tarball
under its default cache path and the complete standalone adapter/build inputs;
extract it, set `JAVA_HOME`, and run `DIE_OFFLINE=true bash scripts/build-photorec.sh host`.
The JSON test dependency and Android toolchains are separate build prerequisites.
