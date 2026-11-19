# Complete native entropy evidence

Native Tools → **Entropy regions** uses the pinned CDiE
`cdie_entropy_read` streaming reader. It exposes the complete returned data as
an `entropy` kind of `org.diemobile.native-records`, version 1, with adapter and
cooperative-request contract version 1. No additional executable-format parser
or approximate byte sampler is introduced. The old native text/JSON entry point
remains available internally and is used as a compatibility reference in tests.

## Measurements and domains

| Row type | Scope and coordinates |
|---|---|
| `whole-file` | Every input byte, physical offset zero and complete input size |
| `region` | Every eligible physical region returned by the native default map; each exact returned offset/size is retained |
| `block` | Contiguous blocks that partition the entire input, including the final remainder; the API names these samples, but they do not skip bytes |
| `byte-frequency` | All 256 whole-input byte counts, including zero-count bins; no single physical offset, VA or payload range |

For each physical measurement, `entropyBitsPerByte` is Shannon entropy
`-sum(p * log2(p))`. Values describe a byte distribution, normally 0–8 bits per
byte; empty intervals have zero. `entropyIeeeBitsHex` retains the exact native
IEEE binary64 value. JSON numbers use the pinned runtime's locale-independent
shortest round-trip formatter. Physical range actions can inspect, hash,
measure or stage the exact stored bytes. Histogram bins remain aggregate data
and have no byte-navigation/export action.

Histogram `byteValue` is 0–255, `count` is an exact decimal string, and
`probability` is derived as count/input size, or zero for empty input. Every
count sums to the complete file size. The Java model independently checks count
accounting, probabilities, total entropy, sample coverage, range bounds, IEEE
representations and row ordering/counts. All returned metadata/unknown object
members survive complete export. Search and 50-row pages do not reduce the
exported scope; filtering `region`, `block` or `byte-frequency` selects a view.

`upstreamDisplayLabel` preserves CDiE's legacy display: entropy at least 6.5 is
labeled `packed`, lower values `not packed`. This is a display heuristic, not a
signature match or evidence of execution, encryption, maliciousness or the
presence/absence of a packer. Compressed media commonly has high entropy;
small samples can also be misleading. The explanation and threshold appear in
native metadata and the default tool help. No signature database is used.

## Native selection and bounds

The reader recognizes PE, ELF and thin Mach-O for its default map. Other inputs,
including the current fat Mach-O path, can use a whole-blob `Binary`/`Data`
region. Native regions exclude virtual-only, invisible, nonpositive and
out-of-file entries, and clip overlong ends to the input. Returned names use the
upstream bounded byte-escape display policy. Regions can overlap and are not a
partition; their entropies cannot be added or averaged into whole-file entropy.
Unsupported/malformed native map handling can fail the complete request. It
does not convert a failed read into an empty histogram.

For a nonempty file, block count is
`min(100, max(1, floor(fileSize / 512)))`. Blocks have a common base size, with
the final block retaining the remainder. Empty files have no blocks, one empty
fallback Data region, zero total entropy and 256 zero histogram bins. A short
file still measures every byte.

The adapter accepts regular files up to 256 MiB, at most 1,024 native regions,
100 blocks, 256 bins and one total (1,381 rows maximum), with a 16 MiB complete
JSON limit. CDiE streams through its configured read buffer, clamped to the
input size. Whole-file counts are combined from the complete block pass;
non-whole-file regions are read separately, so overlapping regions can repeat
work. This is not a hard total-I/O or resident-memory budget.

A request-owned cancellation supplier and 30-second monotonic cooperative
deadline are observed by native progress helpers and during serialization.
The shared `native_request.h` observer also serves Structures, restores the
previous thread-local observer on every completed native path and propagates
Java callback exceptions after releasing owned native results. Cancelled,
failed, over-limit and malformed candidates return no successful partial
snapshot. This remains in-process native analysis, serialized with the other
engine operations; it is not an OS kill deadline or process-isolation proof.

Tool reports capture immutable request/view state and parent input identity.
They do not claim a new whole-input hash; same-length source mutation remains
subject to the existing [native-tool lifetime limits](native-records.md).
Reports and selected ranges are staged before the picker, so later UI/source
changes cannot alter those pending bytes. Range reports carry a newly measured
range digest. Schema/identity details are in [report-schema.md](report-schema.md).

## Verification and remaining work

`scripts/test-native.sh` runs actual JNI plus the production model on 18 scopes:
empty and 1/511/512/513/1023/1024/51200/51203/65537-byte boundaries, uniform and
zero data, rich PE32/64, high-VA/zero-fill Mach-O, a fat blob and a truncated raw
header. It compares totals, every region and legacy labels to CDiE's original
report. A separate Python byte Counter/math.log2/IEEE oracle checks all 612
physical measurements and 4,608 bins against the original bytes. Malformed
counts/domains/coordinates/IEEE values, missing/directory/oversized input,
unknown metadata, active cancellation, callback exceptions and subsequent
request recovery are checked. A metadata formatter-fragment overflow found by
these fixtures was corrected by splitting the bounded write, preserving the
shared report limit.

Two added actual-JNI Robolectric API 28 cases check histogram paging/late-bin
search/no invented locations, complete filtered reports through source mutation
and recreation, and exact final-remainder block bytes staged through mutation
and recreation. They use test output streams, not real SAF providers. These
checks do not verify ARM64 handset execution, real grants/providers, process
crashes/death, hard cancellation timing, memory pressure or power-loss behavior.
Interactive zoom/charts, broader native region families/fat-slice semantics,
complete per-format references and worker migration remain separate work.

A separate local static audit also passes on a 50,223,887-byte real APK
(SHA-256 `c799a83bfd0508963dcb3ea58dd6aa38d687979dc0b3edff920cef9abafb8444`):
358 returned records, 100 complete blocks, 102 physical measurements and all
256 histogram bins agree with an independent streaming Counter/log2/SHA-256
pass, including the final remainder. The inspected APK was not executed.
This is host scale verification, not a device-runtime/performance claim.
