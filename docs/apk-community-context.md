# Captured APK declarations and community knowledge

**APK / SDKs → What does this APK do?** now captures the selected APK through the
existing package-set workbench, then opens the original community descriptions
for its exact literal manifest package ID. The general catalog remains available.
Individual captured APK/split source records also expose this action, so a
multi-input set never silently chooses a package from its other members.

This combines two separately attributed scopes: observed static declarations
from exact APK bytes, and the original UAD-NG community claims. All duplicate
source records remain visible. Unknown IDs have the existing explicit no-match
state. A name match does not establish publisher identity, version/device
applicability, runtime behavior, safety or a removal recommendation. Signer
observations remain technical evidence, with their original verification/errors;
they do not authenticate a community description. No inspected APK is installed
or executed, and this feature provides no package removal/disable commands.

## Evidence and ownership

`PackageSetBackend` requires one known, nonempty literal package attribute and
stable owned APK/CRC-checked manifest snapshots. It checks both input identities
before and after creating an independent `PackageKnowledgeSubject`. The capture
retains original input/member identities, the exact package scalar and original
attribute record, parser provenance, collected signer/error context and the
association's limits. An inventory package-name mismatch stays visible; the
literal observed declaration supplies the lookup ID. Filenames are not IDs.

The subject uses an explicitly bounded **1 MiB** private snapshot. Its small
path/size/SHA-256 descriptor crosses the Activity boundary; the full evidence
never goes into Binder extras. Invalid types/schema/IDs, ambiguous/defaulted
attributes, changed/expired source snapshots, cancellation, excess size and
out-of-directory restoration are refused. Unknown capture fields and every
UTF-16 code unit survive the streamed JSON writer. A rejected candidate never
replaces or deletes an existing retained owner.

The knowledge viewer separately owns the pinned full community catalog. Its
provenance and complete record/table exports include the captured association
and its own SHA-256. **Views → Captured APK declaration / association evidence
and limits** pages/exports the complete original association stream. Community
record/field text exports retain the same context without rewriting the original
upstream record. Complete original catalog-byte export remains unchanged.

Parent cleanup and original APK expiry do not remove the independent context.
Recreation retains the catalog and subject descriptors without rereading the
original APK or source catalog. Final consumer cleanup removes its private
captures. The automatic single-source handoff is attempted once per parent
Activity state; failures leave the package workbench and its original evidence
available, with manual per-source retry. Following another community ID keeps
the original captured association explicitly separate from that related claim.

This is a captured selected-input association, not a complete inherited archive/
recovery origin chain, framework resolution, live behavior trace, signer-bound
knowledge database or device/version-specific debloat policy. Such scope is not
implied by a practical community description.

## Verification

Independent Java/Python checks reconstruct all 65,536 UTF-16 units in capture
fields and complete context in original community record exports, including an
unchanged `1.2300e+9999` numeric token. They check duplicate claims, unknown
fields, strict IDs/types, cancellation, budgets, path confinement, exact source-
free restoration, final ownership and preservation after a refused restore.
The existing full 5,402-record / 5,399-ID knowledge-source oracle also passes.

Three new API 28 host cases use actual SDK 35 AAPT2 manifests, signatures,
parsers and widgets: Main preserves the captured input identity in the explicit
lookup route; a known duplicate UAD ID retains both original descriptions and
APK/manifest/parser context across parent cleanup, child recreation and complete
exports; an expired owned APK refuses the handoff without rescan or partial
ownership. Eighteen focused package/knowledge/presentation cases pass. OS worker
transport/providers are substituted in these host tests.

All **178 Android host tests**, required generic/native checks, ARM64 assembly,
lint and fifteen-library APK verification pass. The final parent once-only
recreation check also passes; the seven-case test APKs compile and pass static
source/manifest/version/signature/notice checks. Source CI is pending. A new
seventh emulator contract exercises the actual `resources` Binder/PFD worker and
unchanged package declaration model against the installed trusted lab APK's
compiled manifest. Its Android execution is pending. The original six worker/
media contracts passed at `8f2ab38`; production ARM64/handset/SAF/UI and general
platform/package behavior remain separate acceptance work.
