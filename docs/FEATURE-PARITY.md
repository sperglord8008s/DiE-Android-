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
| Entropy region analysis, zoom, native block statistics | partial |
| APK metadata, declared permissions/components, signing fingerprint | partial |
| LibChecker exact lookups, UAD catalog, offline Exodus import | partial |
| Installed user/system package inventory, base/split APK paths | wired |
| Read-only permitted filesystem and declared service inventory | wired |
| OS live services and privileged system files | pending; explicit bridge required |
| ZIP/APK nested member selection/extraction | wired; 256 MiB cap, private extraction, CRC check |
| Native resource payload extraction | pending |
| Binary manifest/resource tables and DEX details | partial |
| apksig independent v1/v2/v3/v4 verification | pending |
| Signature database browser, custom import, editor, testing, profiling | pending |
| Full original Qt DiE rule compatibility comparison | pending |
| Searchable typed evidence with source offsets and structured tool exports | partial |
| Comprehensive per-field documentation and investigative guidance | partial |
| Batch scanning, diff, profiles, cancellation, native isolation | pending |
| Device testing, regression fixtures, license audit, reproducible signing | pending |

A functional alpha may have pending items. Do not describe it as a full port until parity is verified.

## Automated verification
Linux host JNI fixture job exercises ELF structures, section and default maps, imports, exports, symbols, disassembly, hash comparison and strings, plus an unsupported-format negative case. This complements Android cross-compilation but is not a handset test. Extend fixtures with valid and malformed PE, Mach-O, APK, DEX and ZIP inputs.
