# APK signing investigation

Open **APK / SDKs → APK signing / rotation / certificates / detached v4**.
The provider uses unmodified AOSP apksig 8.9.2, independently of Android
PackageManager's certificate summary. It never installs or executes an APK.
The checked platform range is the manifest minimum SDK through **API 35**;
Android 16/API 36 and device installation/update decisions are not verified.

## Verification and identities

The initial view verifies the embedded APK signatures without a detached file.
Root views can explicitly select or replace a local `.idsig`. No sibling file,
network resource, certificate authority or cloud service is discovered. The
APK-only evidence remains available independently of the selected pair.

`verified` requires apksig's aggregate success and, when an `.idsig` is supplied,
successful v4 verification with no returned v4 signer errors. The raw
`apksigResultVerified` flag is retained separately. This distinction is necessary:
in pinned 8.9.2, `Result.containsErrors()` and `getAllErrors()` omit v4 signer
errors. A real corrupt-verity-tree fixture leaves that raw aggregate flag true
while `isVerifiedUsingV4Scheme()` is false. The app does not present this pair as
verified. Diagnostics collect global, every scheme signer, ignored JAR signer
and source-stamp scopes directly, retaining duplicates and severity.

A scheme boolean means successfully verified using that scheme in the checked
range. False alone does not prove absence. A returned certificate or lineage
can accompany a failed verification. V3/v3.1 records target different API ranges;
they are not thereby simultaneous co-signers. The upstream-returned verified
APK signer list is separate from parsed scheme certificates and the stricter
combined result.

A source stamp identifies a distribution signer, which can differ from the APK
signer. Its status, returned certificate lineage, timestamp and informational
messages are separate evidence. A fixture changes only the stamp certificate
inside the signing block: embedded APK signatures still verify while the stamp
fails. A signed timestamp is not independent trusted time.

## Complete collected tables

| Table | Evidence |
|---|---|
| Verification | Completed/unavailable state, combined and raw upstream status, per-scheme flags, checked API range, verified APK signer references and detached layout |
| Signers | Every returned v1/ignored-v1/v2/v3/v3.1/v4 record, original index and list ordinal where needed, certificate associations, JAR member names, SDK ranges, development-release rotation flag, scoped lineage/digest references |
| Certificates | Exact returned X.509 DER, TBS certificate, signature/parameters, subject/issuer names and DER, serial/version/times, MD5/SHA-1/SHA-256 compatibility fingerprints, exact SPKI and SHA-256, key parameters, unique IDs, key/extended usage, alternative names and every returned critical/noncritical extension's DER OCTET STRING |
| Rotation lineages | Each combined or signer-specific history, ordered certificate references, installed-data/shared-UID/permission/rollback/auth capabilities and contextual mechanisms, upstream re-encoded proof-of-rotation attribute |
| Digests | Every returned signing content digest with its signing-algorithm ID, exact hex/bytes and source scope; v4's synthetic algorithm ID zero is explicitly distinguished from a wire algorithm |
| Diagnostics | Every returned scoped error/warning/information record, original ordinal, issue enum, full message and public parameters; unsupported parameter objects retain type/rendering without claiming a wire encoding |
| Source stamp | Presence of a returned result, verified/status fields, separate signing certificate and lineage, signed Unix seconds/UTC when renderable, errors/warnings/information |

Certificate DER is deduplicated by SHA-256 plus byte equality; all associations,
chain positions, signer duplicates and scoped lineages remain separate. Lineages
are not merged merely because certificates match: SDK-targeted histories can
have different capabilities. The exported rotation bytes are re-encoded by
upstream, not falsely labeled an original APK range.

X.509 descriptions are JCA-decoded fields, with raw DER retained. RSA parameters,
prime/binary EC field/curve/generator/point data, and DSA parameters are exposed
when the provider supplies those key interfaces. Extension decoding errors stay
visible. There is no PKIX trust-store, chain, certificate-time or revocation
validation; the APK signer is commonly self-signed. Key-usage names explain the
original bit positions. MD5/SHA-1 fingerprints are compatibility identifiers.

Search/paging does not change export scope. **All collected signing evidence**
exports every table; selected tables and individual records can also be exported.
Certificate/TBS/SPKI/signature/name/extension, content-digest, re-encoded lineage
and exact selected `.idsig` bytes have binary actions. Derived records have no
invented APK offsets. The detached-input view uses its own acquired byte source,
so its layout and hex/range actions refer to `.idsig` bytes.

## Acquisition, layout and budgets

The provider owns a complete APK snapshot with fresh SHA-256 and exact size,
checking remembered Scan identity when supplied. A supplemental stream is copied
to a generated private file, bounded even when the provider supplies no size.
Both sources are rechecked after verification and before adoption. Failed copy,
budget or cancellation preserves the previous pair. A copied malformed signature
is deliberately inspectable as a completed negative or unavailable verification.

Private session handles survive recreation and parent expiry. An atomic local
source pointer publishes the latest complete pair before an old attachment is
removed; restoration checks directory ownership and each complete SHA-256/size.
Saved handles predating a replacement therefore remain usable. Private paths are
not included as source descriptors in evidence exports. Normal close removes the
session; configuration changes retain its sources. Cache eviction, power-loss
durability and orphan-session storage reclamation remain separate concerns.
Source `capturedAt` marks pair publication/restoration. The embedded APK result
is reused across detached replacement/clear and recalculated on restoration;
its bytes and checked platform range remain fixed. Detached byte views/actions
also recheck the private source identity before exposing associated ranges.

| Budget | Bound |
|---|---|
| Whole acquired APK | 256 MiB |
| Explicit detached stream | 8 MiB |
| Contiguous APK buffer requested through DataSource | 16 MiB |
| Aggregate APK reads across DataSource slices | 8 GiB |
| Feed block / cooperative verification deadline | 1 MiB / 30 seconds per verification |
| Collected records / JSON characters | 20,000 / 32 Mi-characters |
| Returned certificate DER | 1 MiB |

The `.idsig` preflight only checks top-level length-prefixed allocation ranges
against the actual bounded file. This prevents upstream's InputStream helper
allocating an attacker-declared huge buffer before EOF. All cryptographic and
field interpretation remains in unmodified apksig. Layout records distinguish
hashing info, signing infos, optional verity tree and trailing bytes. The pinned
verifier recomputes the APK root when the optional tree is omitted. It ignores
bytes after the tree: those bytes are retained in the complete source identity
but are not authenticated by v4. No EOF-derived or inferred APK region is used.

These checks are in-process and cooperative. Upstream CPU/inflate operations
and a stalled content provider may delay cancellation; this is not a hard
memory/CPU/process boundary or a claim of safety for arbitrary hostile inputs.
Exceeding acquisition/result budgets fails explicitly without publishing a
silently shortened complete result. The main Scan text is a bounded preview;
the dedicated viewer retains the complete returned records within these limits.

## Reproduction and verified scope

`signing-engine/source-lock.json` records the exact published binary/source JAR
hashes and the reviewed upstream public/input source files. Gradle strictly pins
the dependency and verifies binary bytes before every build. The source archive
is a development API/behavior reference; it is not a modified replacement engine.
The original bundled Apache-2.0 notice remains included.

```sh
bash scripts/test-apk.sh
# Completely offline, with previously verified public inputs:
APKSIG_JAR=/cache/apksig-8.9.2.jar \
APKSIG_SOURCES_JAR=/cache/apksig-8.9.2-sources.jar \
JSON_JAR=/cache/json-20240303.jar DIE_OFFLINE=true bash scripts/test-apk.sh
```

Host fixtures generate disposable keys and actual binary-manifest APKs using
the upstream signer. They check separate/combined v1/v2/v3, rotation at APIs
28/33/35, actual v3.1 and v4/v4.1 signer targeting, exact capabilities and
certificates, source stamps, corrupted/mismatched/truncated inputs, optional-tree
and trailing-byte behavior, malicious allocation lengths, stream limits,
cancellation, complete exports, latest-pair restoration and cleanup. RSA,
ECDSA/P-256 and DSA signatures/returned key metadata are exercised. This is not
an exhaustive algorithm or historical Android compatibility matrix.

Four API 28 Robolectric cases use the actual signer/verifier and widgets:
targeted signer/lineage/certificate navigation and exact DER export; pending and
selected `.idsig` recreation after parent deletion and exact byte export;
corrupt-v4 diagnostics plus complete filtered export/clear; and picker
cancellation/oversized replacement retaining the prior pair. Only provider
transport is substituted. This brings the suite to 71 checks. Actual SAF,
handset/API 26/35, platform installation/rotation/v4 behavior, hard isolation,
split-set signing policy and API 36 remain unverified or incomplete.
