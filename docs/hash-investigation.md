# Whole-file hash investigation

**File info → Whole-file hashes** opens eight typed digest records, including
the missing desktop families MD4/SHA224/SHA384/SHA512. **Native Tools → Hashes**
uses the same new worker/validation path while retaining its text/JSON switch
and algorithm selector. The original pinned CDiE C hash-report API remains
unchanged with its original four algorithms; it is not the expanded Android
view's computation path.

## Evidence and mechanisms

| Algorithm | Output | Meaning and source |
|---|---|---|
| CRC32 | 32 bits / 8 hex digits | CRC-32/ISO-HDLC (ZIP/Ethernet), reflected, polynomial 0x04c11db7, init/xorout 0xffffffff; pinned xxfclib |
| MD4 | 128 / 32 | RFC 1320 legacy identification; unmodified RHash 1.4.5 source |
| MD5 | 128 / 32 | RFC 1321 legacy identification; pinned xxfclib |
| SHA1 | 160 / 40 | FIPS 180-4 legacy identification; pinned xxfclib |
| SHA224 | 224 / 56 | SHA-256 family with distinct initial state, not truncated SHA256; pinned xxfclib |
| SHA256 | 256 / 64 | Independently checked against Java acquisition SHA-256; pinned xxfclib |
| SHA384 | 384 / 96 | SHA-512 family with distinct initial state, not truncated SHA512; pinned xxfclib |
| SHA512 | 512 / 128 | 1024-bit block SHA-2 variant; pinned xxfclib |

Records preserve full lowercase hexadecimal, bit length, implementation/source
revision, complete input length/coverage, mechanism and limitations. Empty files
return standard digests, including MD4 `31d6cfe0d16ae931b73c59d7e0c089c0` and CRC32
`00000000`; older desktop DiE versions can display blanks for empty files.
Search matches algorithm names (also hyphenated SHA names) or digest text,
excluding common explanatory boilerplate. Filter/page never reduces the full
eight-record JSON export. Individual complete values export as text.

A digest is computed evidence, not bytes stored at a known input offset. Row
physical/size fields are null; the input provenance separately identifies every
byte hashed. There is no inferred digest address. CRC is accidental-corruption
detection. MD4/MD5/SHA1 have collision weaknesses. A SHA-2 digest is useful against
a trusted expected value, but plain hashes do not authenticate publishers or
establish file safety. No reputation, credential or network query is performed.

## Source ownership and worker boundary

Acquisition copies and hashes the complete input into an owned private snapshot,
with a 1 GiB limit. The worker then computes all eight digests in **one native
pass over that snapshot**; acquisition is a separate copy/read operation. The
native SHA256 must equal acquisition's independent Java SHA-256 before any
successful model is exposed. Native Tools exports use this newly measured
identity, rather than a potentially stale parent digest passed in its Intent.

The private isolated-UID service receives only a read-only descriptor and report
pipe with the `hashes` engine selector. Ordinary-UID fallback is refused. The
native adapter duplicates the descriptor, uses 64 KiB `pread` reads without
moving the caller cursor, checks the exact declared size and compares final
device/inode/size/mode/mtime/ctime metadata. A 40-second native deadline and
45-second parent worker deadline bound computation; acquisition observes Java
cancellation separately. The JNI report is at most 16 KiB, while the validated
result must contain exactly the eight known algorithms in order with exact hex
lengths, one complete native pass and a full byte count. Failures never expose
a successful prefix. The shared library requires all symbols to resolve at
link time on Linux/Android.

The typed provider retains its source until close; reports and staged picker
exports remain independent of caller mutation/expiry. Native Tools needs the
snapshot only through calculation/validation and closes it afterward. Its
selected digest report includes full measured provenance. No format parser,
signature database, inspected code, external command or file-writing extractor
is invoked by the hash worker.

RHash source/notice bytes and report source identities are verified by
`scripts/check-hash-sources.py`. The retained RHash source is 0BSD, xxfclib is
MIT and app adapters are GPL-3.0-only. See [engine build details](../hash-engine/README.md).

## Verification and remaining scope

`scripts/test-hashes.sh` runs the actual source-built JNI and a native boundary
fixture. All seven RFC MD4 vectors, independent JCA/OpenSSL 3 comparisons at
padding/block/64 KiB read boundaries and a larger random input pass. Tests check
CRC variant, exact byte counts, borrowed-descriptor position, regular-file,
input/timeout limits, empty input, cleared failure output, full SHA-256 identity,
typed/legacy selectors/exports, snapshot ownership, cancellation and rejection
of incomplete, duplicate-algorithm or incorrect-SHA-256 worker reports.

Three API 28 Robolectric cases use actual host JNI and Android widgets, replacing
only OS transport: all eight values/sources/locations/search; complete filtered
export across source expiry/recreation; and the existing Native Tools entry,
eight-algorithm output, SHA-512 selection and freshly measured export identity.
They join the other workbench checks; production Binder/isolated UID, ARM64
execution, deadline/cancellation timing, real document providers and API 26/35/
16 KiB runtime remain device-unverified. These tests do not establish hash
algorithm certification or bit-identical release reproducibility.

Range/routine-specific hashes, fuzzy/ssdeep/TLSH, authentihashes, import hashes
and signed-content verification are distinct features. This change supplies raw
whole-file digests; it does not imply complete parity of those other tools.
