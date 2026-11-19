# Whole-file digest engine

The app-owned descriptor adapter computes CRC32, MD4, MD5, SHA1, SHA224, SHA256,
SHA384 and SHA512 in one native streaming pass. CRC32/MD5/SHA1/SHA256 reuse the
contexts already linked by CDiE. SHA224/SHA384/SHA512 add the unmodified
`src/algo/sha512/xx_sha512.c` from the **same pinned xxfclib checkout**,
`0fc8b4fa12c2c9c97d79fa3a3806667fc1da9b9d` (MIT). No vendor edits are required.

MD4 uses unmodified RHash 1.4.5 source at
`6ea6791024499524cf41f9e0b6b08d456bf4a5e2`, under the BSD Zero Clause license.
The small source subset (`md4.c/.h`, byte-order support and `ustd.h`) and complete
`COPYING` are retained in `third_party/rhash`. `source-lock.json` records exact
per-file SHA-256 values. Original license/header notices are also bundled in the
APK as `rhash-md4-1.4.5.txt`. This integrates MD4, not RHash's entire algorithm
catalog. MD4 is for legacy identification, not adversarial authentication.

App code is GPL-3.0-only. CMake compiles the original RHash MD4 file with
`-fno-strict-aliasing` for its aligned word access, and links the shared adapter
with `--no-undefined` on Linux/Android. The input is data, never executable code.
No runtime network, plugins, external commands or hash-provider discovery is
needed. Source checks run before Android builds.

```sh
bash scripts/fetch-engine.sh
python3 scripts/check-hash-sources.py
NATIVE_BUILD_DIR="$PWD/app/build/host-native" bash scripts/test-hashes.sh
```

Host fixtures require JDK 17, a C compiler/CMake and OpenSSL 3 with its legacy
provider for an **independent development-only MD4 reference**. SHA-2/MD5/SHA1
are compared with JCA; CRC32 is compared with Java's CRC32. OpenSSL is not a
dependency of this Android hash adapter. The SHA-256-pinned test-only JSON JAR
can be cached through `JSON_JAR`. Native sources/dependencies come from the
existing engine checkout and these retained RHash files; builds can be offline
after the normal toolchain/dependency acquisition.

`hashes.c/.h` owns the descriptor loop and per-request digest contexts. It checks
regular-file/known-size input, duplicates the descriptor, uses `pread`, preserves
the caller's cursor and compares final descriptor metadata with the initial
snapshot. All eight results are returned together; failure clears the output.
The separate `libdiehash` JNI library only formats a small ASCII evidence envelope; it does not load the `libdiemobile` parser bridge. See
[hash investigation contracts](../docs/hash-investigation.md) for the Android
boundary, ownership, limits and remaining device verification.
