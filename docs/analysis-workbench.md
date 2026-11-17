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
- Future: apksig independent signing scheme verification; Android binary manifest and resource XML; dexlib2 / smali; JADX; ELF symbols/sections; Capstone or existing CDiE decoder; archive member analysis; package diff; installed-app picker; optional separate privileged actions only by explicit consent.

## Limits of release 0.3
APK parser uses platform PackageManager; split/container handling incomplete. No resource execution, network, installation or debloating. ZIP enumeration and DEX parsing bounded; archive entries never extracted by this module. DEX descriptor matcher only ASCII conventional classes, not Unicode or compact DEX. Rules and signing metadata can be incomplete. Full data and source notices included at build time; Exodus user-provided data not redistributed.
