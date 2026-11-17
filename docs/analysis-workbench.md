# Analysis workbench

Design: every result should include observation, interpretation, evidence, source/revision, limitations, and practical consequences. Never equate a requested permission, signature match, high entropy, or package label with proven malicious behavior.

## Interface principle: expert-first, explanatory by design

There is no beginner/advanced/expert gating. The default Scan view exposes technical facts and signature results, exact byte lengths, hashes, flags, and scan provenance. Preserve original names, units, offsets and raw records. Contextual help explains what a term means, mechanism, interpretation, corroboration and limitations beside the evidence. Reference material is supplemental, not a substitute for technical facts. Use readable proportional text for explanations and monospace for actual bytes/code. No artificial security score. Never imply evidence was collected when an engine has not exposed it.

## Integration map

- CDiE: existing native signature engine.
- LibChecker-Rules: pinned Apache-2.0 English data; exact native filenames and manifest class names initially. Not equivalent to LibChecker's complete matcher.
- Universal Android Debloater NG: pinned GPL-3.0 package catalog. Exact package ID lookup, read-only. Recommendations are contextual.
- Exodus: local import of a lawfully obtained tracker API snapshot; ODbL 1.0 / DbCL 1.0. No network or API credentials in the app. Literal DEX defined-class prefix matching only, not full regular-expression coverage; report skipped rules.
- APKEngineer: exact upstream project/repository still to be identified and license audited.
- AOSP apksig 8.9.2 (Apache-2.0): independent v1/v2/v3 verification and signer certificate/SPKI digests, explicit platform range, signer-local errors and warnings. Host signing fixtures pass; v3.1 is exposed without rotation fixtures, and detached v4 is pending.
- CDiE native tools now expose ELF/PE/Mach-O structures and supported memory maps, symbols, imports/exports/resources, instruction decoding, Unicode strings, hashes and entropy. ZIP/APK members can be selected for bounded private extraction and analysis; installed package inventory is available. These integrations do not imply full format coverage or device verification.
- Maps, imports, exports, symbols, resources and instructions have detached typed records, searchable Android pages, raw field details with contextual help, physical/VA navigation and range evidence. Resource exports stage exact stored bytes before the picker; reports preserve complete returned tables and captured parameters. Host JNI and API 28 Robolectric integration checks pass; real document-provider/handset flows remain pending. See [record contracts and UI scope](native-records.md).
- The selected-member ZIP browser supports nested private snapshots, paginated/filter-preserving Back/recreation, complete bounded inventory, directory revalidation and exact size/CRC32/SHA-256 acquisition. Host fixtures and five API 28 Robolectric cases cover real ZIP payloads and navigation; device/cache-pressure checks remain pending. See [archive acquisition](archive-acquisition.md).
- Google smali/dexlib2 + baksmali 3.0.10: Unicode/MUTF-8 class indexing in the main report; lazy typed metadata/instruction/reference/debug/exception adapters and smali exports fixture-verified. Android table UI integration is in progress. See [DEX contracts](dex-investigation.md) for exact scope, budgets and limits. No classes are loaded or executed.
- Future: complete Android binary manifest and resource XML; JADX; package diff; optional separate privileged actions only by explicit consent.
- Additional requested scope: MPV/media-codec probing, Binwalk, whole-blob media/resource carving with JPEG/PhotoRec-like recovery coverage, EXIF and collected file metadata. These are not yet implemented. Integrate actual upstream engines where compatible; audit exact versions/licenses and supported Android ABIs. Preserve raw tags, duplicate metadata, candidate offsets, validation/extraction evidence and explicit incomplete/unsupported results. Engine metadata, signature candidates and recovered bytes have different evidential meanings.

## Remaining Android metadata limits
APK identity/components still use platform PackageManager; split correlation remains incomplete. No resource execution, network, installation or debloating. ZIP enumeration and DEX parsing are bounded; this summary module reads selected DEX streams without extracting files. Unicode/MUTF-8 class names and 041 containers are supported by the pinned reader; compact DEX/ODEX/OAT and full ART validation remain pending. Tracker rule syntax coverage is still limited. Rules and signing metadata can be incomplete. Full data and source notices are included at build time; Exodus user-provided data is not redistributed.
