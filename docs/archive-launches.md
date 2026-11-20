# From an embedded finding to archive investigation

A Binwalk or PhotoRec candidate with an observed physical start offers
**Inspect archive / compressed stream at this offset (parsing interval)**.
It opens the native archive browser with the exact captured input identity and
an explicit interval from the observed start to the end of that input.

This action is available even when the candidate's reported size is unknown.
The interval is a parser budget, not a recovered archive extent. The original
finding is unchanged. Its reported size stays null when unknown; a known
reported size remains separate from the chosen parsing tail. Exact stored-byte
recovery/export still requires the existing bounded-range action.

For example, PhotoRec can accept a gzip header without establishing the stored
stream length. The archive browser can try decoding that input interval and
acquire the resulting logical member only after its own complete native parse,
metadata/size validation and independent byte hashing. That derived member does
not establish where the original compressed archive ended in a larger blob.

## Request and evidence contract

`ArchiveLaunch` is an Android-independent typed request containing:

- Complete source SHA-256 and exact source length.
- Explicit parsing offset/length, with no recovered-extent value.
- Original finding scope and native row index.
- The complete original selected record and provenance as separately retained
  JSON strings, including fields the launcher does not interpret.

The constructor checks exact integer types, bounds, supported finding scopes,
row offset/index, provenance identity and the complete parsing-tail relationship.
`EvidenceJson` preserves all UTF-16 code units, including NUL, supplementary
characters and isolated surrogates. Request encoding is bounded during writing
at 64 Ki-characters. An oversized complete context is explicitly refused;
records are not silently shortened. The archive browser's manual interval
controls remain available independently.

Before starting the child browser, the investigation viewer rechecks its
captured source. The native archive session independently acquires the input
and compares its measured SHA-256/size with the requested identity **before any
native parsing**. Main, Gallerize and content-preview callers also supply their
existing captured identities. A source mutation or stale identity cannot be
silently attached to a new archive result.

The archive session retains the original launch context in saved state and its
complete evidence export. Manual interval changes do not rewrite that original
request. Its finding context and current native interval remain distinct.
Restoration verifies the owned source/report files and launch identity, without
rerunning the native engine.

## Selected-member return

A selected member result carries its independent SHA-256 and the root captured
source identity, including through the classic ZIP fallback. The investigation
viewer validates those parent fields and the existing private member-handoff
boundary before opening a separately owned preview. An expired parent input
does not invalidate already acquired member bytes. Missing/invalid parent
metadata is an explicit refusal, and cleanup is limited to generated direct
private archive handoff leaves.

The member's original physical offset remains unknown. Native directory and
acquisition packets remain in the parent archive export; persistent native-origin
sidecars attached to each independent preview and an all-visited-node history
remain future work.

## Verification

`scripts/test-archive-launch.sh` runs the actual Binwalk, PhotoRec and libarchive
libraries in one host JVM. It checks an independently generated prefixed TAR
found by Binwalk and a real PhotoRec gzip finding whose reported extent remains
unknown. Both chains produce member bytes matching independent fixture SHA-256
expectations. The test closes the original backend/source, restores the archive
without rescanning, preserves original row/provenance/Unicode, and refuses wrong
identities before native work, coerced numeric fields and oversized requests.

The focused Android host cases check source-identity refusal before parser
entry, original finding context after manual scope changes/recreation, actual
PhotoRec unknown-extent and Binwalk bounded-row launch actions, cancelled and
malformed results, and a checked returned preview after parent-cache expiry.
These use real relevant JNI libraries with OS transport substituted; the result
protocol's independent known payload is separate from the standalone native
pipeline proof. The full **151-case Android host suite** passes with zero failures/errors/skips,
as do required generic/native checks, the 25-capture/37-acquisition archive
model regressions, ARM64 assembly, lint and the fifteen-library static APK
verifier. Source CI is the next gate for this slice.

Actual Android UID/Binder, process death, SAF/media/PDF, cancel/crash, cache
pressure and 16 KiB runtime behavior remain unverified. The action does not
claim arbitrary deobfuscation, passwords, universal archive support or full
desktop DiE parity. See [native archive contracts](native-archives.md).
