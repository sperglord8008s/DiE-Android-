# Feature parity / verification checklist

Status labels: wired = integration exists, not validated on hardware; partial = reduced capability; pending = absent. Update on each build and fixture verification.

| Feature | Current target branch |
|---|---|
| CDiE signatures, deep/heuristic/recursive/aggressive/parent formats | wired, heuristic parity unverified |
| File selection + rescan | device feedback confirms earlier build; regressions pending |
| Upstream PE/ELF/Mach-O DataStructs | wired |
| Memory map sections/segments/default | wired |
| Imports, exports, symbols, resources | wired |
| Native disassembly with offset and architecture | wired; control flow/paging improvements pending |
| CRC32, MD5, SHA1, SHA256 | wired |
| Unicode strings | wired, upstream limits |
| Full paged read-only hex | wired |
| Entropy region analysis, native block statistics | wired; interactive zoom pending |
| APK metadata, declared permissions/components, signing fingerprint | partial |
| LibChecker exact lookups, UAD catalog, offline Exodus import | partial |
| Installed user/system package inventory, base/split APK paths | wired |
| Read-only permitted filesystem and declared service inventory | wired |
| OS Binder service registrations | wired, normal UID visibility only |
| Live process/service diagnostics and privileged files | pending; explicit bridge required |
| ZIP/APK nested member selection/extraction | wired; 256 MiB cap, private extraction, CRC check |
| Native resource payload extraction | partial; manual validated physical range export |
| Binary manifest/resource tables and DEX details | partial |
| AOSP apksig independent v1/v2/v3 verification and certificate metadata | host fixtures verified: separate v1/v2/v3 and combined signatures, exact certificate, unsigned, tampered and truncated APKs; device flow pending; v3.1 exposed but rotation fixtures pending |
| v4 detached .idsig handling | pending |
| Signature database browser, source, temporary editor, single-rule testing, profiling, tag filters | wired via upstream desktop backend; fixture and device testing pending |
| Custom database import and persistence | pending |
| Full original Qt DiE rule compatibility comparison | pending |
| Searchable typed evidence with source offsets and structured tool exports | partial |
| Comprehensive per-field documentation and investigative guidance | partial |
| Desktop scan cooperative cancellation and result cache | wired; runtime validation pending |
| Batch scanning, diff, profiles, isolated native worker | pending |
| Device testing, regression fixtures, license audit, reproducible signing | pending |

A functional alpha may have pending items. Do not describe it as a full port until parity is verified.

## Automated verification
Linux host JNI fixture job exercises ELF structures, section and default maps, imports, exports, symbols, disassembly, hash comparison and strings, plus an unsupported-format negative case. This complements Android cross-compilation but is not a handset test. Extend fixtures with valid and malformed PE, Mach-O, APK, DEX and ZIP inputs.

Desktop-backend fixture additionally validates candidate types, parser facts, loaded signature inventory and scan result tree against the pinned database. Unsupported cases and mobile UI behavior still require tests.

`scripts/test-apk.sh` creates disposable RSA signing keys and synthetic binary-manifest APKs. It checks actual AOSP apksig 8.9.2 verification, including signer-local digest errors (which are not returned by `Result.getErrors()`). It downloads a SHA-256-pinned build-time JAR, or accepts `APKSIG_JAR` for offline tests. Reports explicitly check the manifest minimum API through API 35; this does not verify Android 16/API 36, detached v4 signatures, or rotation lineage.
