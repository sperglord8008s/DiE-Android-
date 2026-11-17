# Feature parity / verification checklist

Status labels: wired = integration exists, not validated on hardware; partial = reduced capability; pending = absent. Update on each build and fixture verification.

| Feature | Current target branch |
|---|---|
| CDiE signatures, deep/heuristic/recursive/aggressive/parent formats | desktop backend is primary; host known-rule detection, custom-DB gating and tag-filter fixtures verified; full Qt/heuristic parity and device flow unverified |
| File selection + rescan | device feedback confirms earlier build; regressions pending |
| Upstream PE/ELF/Mach-O DataStructs | host fixtures for ELF, PE32, PE64 and thin Mach-O; malformed headers tested; device flow pending |
| Memory map sections/segments/default | host ELF default/sections, PE sections and Mach-O segments; unsupported modes fail explicitly |
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
| Signature database browser, source, temporary editor, single-rule testing, profiling, tag filters | source retrieval, known edited-rule test and include-tag filtering host verified; profiling and device flow pending |
| Custom database import and persistence | local ZIP snapshots, SHA-256 identity, native load check, activation/rollback and original-ZIP export wired; host import/CRC/path/size/persistence tests and native activation/tag/reload fixtures verified; Android picker/restart flows pending |
| Full original Qt DiE rule compatibility comparison | pending |
| Searchable typed evidence with source offsets and structured tool exports | typed desktop detection tree, search and 50-row paging; versioned JSON report, captured shared settings and pinned provenance host verified; entry-offset hex/disassembly links wired; remaining native tables are text |
| Comprehensive per-field documentation and investigative guidance | partial |
| Desktop scan cooperative cancellation and result cache | cache hit and byte-change invalidation host verified; request-owned cancellation, deterministic queued-cancel/recovery and Java streaming cancellation host verified; active native interrupt latency and handset lifecycle tests pending |
| Batch scanning, diff, profiles, isolated native worker | pending |
| Device testing, regression fixtures, license audit, reproducible signing | pending |
| Packaged ARM64 APK signing/identity, offline assets and static 16 KiB alignment | build-time verification script and CI gate; actual 16 KiB Android runtime/device verification pending |

A functional alpha may have pending items. Do not describe it as a full port until parity is verified.

See [continuation instructions](continuation.md) for the build/check commands, implemented-versus-verified scope and next work.

## Automated verification
Linux host JNI fixture job exercises ELF structures, section and default maps, imports, exports, symbols, disassembly, hash comparison and strings. Deterministic PE32, PE64 and thin Mach-O fixtures check structures, supported maps, entry-point mapping and decoding. Truncated ELF/PE/Mach-O headers must return explicit errors. Minimal DEX and ZIP fixtures check recognition only, not full format validity. This complements Android ARM64 cross-compilation and does not verify handset behavior.

Desktop-backend fixtures validate candidate types, parser facts, loaded signature inventory, known custom-rule detection, exact rule provenance, source retrieval, edited-rule execution, custom-DB gating, include-tag filters and cache identity behavior. `scripts/test-models.sh` checks immutable settings, strict tree parsing, paging/search and structured report round trips. It uses a SHA-256-pinned host-only org.json JAR (`JSON_JAR` supports offline testing); the app uses Android's built-in org.json.

## Desktop upstream gap audit

Compared with the pinned Detect-It-Easy README, CDiE README, `docs/DATABASE.md` and `docs/LIMITATIONS.md` (engine `f055daa`, database `7ef816e`):

- PE native/.NET heuristic analyses, anti-analysis, Authenticode correlations and format-integrity checks are database-driven. Loading these rules is implemented; equivalence of their complete output with Qt DiE is **unverified**. CDiE documents an additional VMProtect heuristic result compared with the reference engine.
- CDiE hashes provide four algorithms. Desktop MD4, SHA224, SHA384 and SHA512 parity remains pending.
- Archive scanning expands ZIP-family containers with upstream limits; 7z/RAR/CAB/ISO member expansion and ZIP64 remain unsupported by this backend. Some upstream resource/temporary-space failures skip members without a diagnostic; exports explicitly disclose this coverage limit.
- XML/CSV/TSV scan export, directory/batch scanning, comparisons, saved profiles, demangling, debug-file/DWARF/PDB decoding and control-flow navigation remain pending in Android.
- Native DataStructs cover PE/ELF/thin Mach-O; other formats are not thereby fully parsed. Android binary XML, resources.arsc and DEX instruction analysis remain incomplete.
- Automatic network database updates are deliberately absent. Bundled data is pinned and installed by revision; custom local import is tracked separately below.

## Offline custom databases

The laboratory opens **Offline custom databases**. Import ZIPs with supported format folders at the root or below `db/` or `db_custom/`. The importer rejects unknown/nested format folders, non-script members (except metadata/notices), unsafe paths, duplicate logical entries, corrupt CRCs and oversized inputs rather than silently discarding them. Limits: 32 MiB compressed archive, 64 MiB expanded bytes, 1 MiB per file and 10,000 entries. Helpers, `_metadata` and license notices are retained; a snapshot must contain at least one non-`_init` format rule.

Each archive gets a private SHA-256-named immutable directory. The complete original ZIP is retained for exact export. Native inventory must load custom scripts before the active pointer changes. Activation enables the shared Custom DB option; toggling that option off retains the selected snapshot. Older snapshots support rollback and are retained; storage reclamation UI is pending. A new directory forces native database/cache invalidation. Import/load does not validate every rule's syntax, licensing or correctness: use laboratory source/test on known inputs. Snapshot archive hashes and enabled state are captured in reports. Native imports do not execute the inspected application. Native JS/parser isolation remains pending.

`scripts/test-apk.sh` creates disposable RSA signing keys and synthetic binary-manifest APKs. It checks actual AOSP apksig 8.9.2 verification, including signer-local digest errors (which are not returned by `Result.getErrors()`). It downloads a SHA-256-pinned build-time JAR, or accepts `APKSIG_JAR` for offline tests. Reports explicitly check the manifest minimum API through API 35; this does not verify Android 16/API 36, detached v4 signatures, or rotation lineage.
