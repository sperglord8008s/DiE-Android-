# Recovered PDF files

PhotoRec adapters 4/5 enable the original upstream PDF `file_check` callback.
A supported embedded PDF can now become an actual **Documents / Files** gallery
item with an exact `.pdf` payload and `application/pdf` export. Tapping it hands
those separately owned bytes to the existing in-app PDF viewer. It no longer
needs to remain only a PDF header when the upstream footer check supplies a
bounded result.

The reviewed `file_pdf.c` callbacks use PhotoRec's footer/newline or declared
linearized-size check and optional date reader. Their original returned size
is accepted only when the callback completes without a read/work error, the
size is positive, fits the actual source and upstream limits, and no upstream
offset error exists. Data-check sizes remain a separate path for other formats.
A footer match is not full PDF xref/object/stream validation or proof that a
renderer can display every page. Upstream backward footer search can include
multiple concatenated or incremental documents; overlapping findings and exact
original witnesses remain available.

## Read-only stream boundary

`ReadOnlyFile` presents only the candidate's source-relative interval to the
upstream `FILE` readers. Android uses Bionic `funopen`; the host uses
`fopencookie`. Reads use the existing owned `pread` source. Neither path opens a
sample filename, permits writes, changes the caller's descriptor position or
allocates a whole-input buffer. Path-based upstream opens/renames stay denied;
no rename, arbitrary format file callback or PhotoRec recovery executable runs.

The wrapper has a fixed **64 KiB** stdio buffer and checks relative seek/EOF,
per-candidate **8 MiB read work**, aggregate **256 MiB callback work** and the
existing cooperative deadline. Exceptions are contained at the C stdio callback
boundary. Source/IO/deadline/work failures retain a null recovered extent.
Attempts, actual executions, raw returned size, read work, failure reason and
complete/limited counts remain in the original native capture. Partial PDF
checks are visible in the tool/gallery status.

In adapter 4, the PDF date reader can scan the recovered file. Consequently a valid larger
PDF can hit the 8 MiB callback-read budget and remain unresolved even if a footer
was seen earlier. This limit is explicit; adapter 5 now distinguishes optional work from the established footer as described below. Missing footers never acquire an
input-tail or next-header size.

Adapter 4's maximum input-read payload buffers are **1,376,256 bytes (1.3125
MiB)**: the previous 1.25 MiB matcher/header caches plus this 64 KiB stream
buffer. Native callback scratch, registry/report storage, stdio structures,
JNI/Java, allocator and kernel memory are additional. This is neither an input
cap nor a total process memory bound. The whole-input limit remains 256 MiB;
Binwalk retains its independent 64 MiB limit. Original adapter 2/3 captures
retain their original validation and read budgets.

`PhotoRecFileChecks` validates the exact PDF-only mode, attempt/execution/
limited counts, buffer/work limits and source-bounded native result before a
range reaches recovery actions. Altered scopes, omitted attempts, failed checks
claiming positive extents and inconsistent metadata are refused.

## Verification

Host tests recover an owned valid PDF from between unrelated prefix/suffix
bytes in a mislabeled blob. Its gallery card, `.pdf`/MIME and exported payload
match the original bytes. Independent Python verifies the PDF's own xref/object
locations and compares the native returned boundary. Footer truncation,
footerless fully written 10 MiB input, work limits and forged scope/count/failure
claims are checked. Existing full PhotoRec native tests, including 80 MiB fully
written and 256 MiB sparse scans, remain passing.

A native wrapper test checks exact relative bytes/EOF, invalid/overflowing seeks,
denied writes, bounded read accounting, cooperative exceptions and caller
position/descriptor ownership. A host Android gallery case uses the actual new
native recovery and checks PDF save and preview handoff bytes. It does not
substitute for running the Android PDF renderer. Full-suite, ARM64 and CI status
is recorded separately in the release checklist. Actual Bionic stdio/isolated PhotoRec execution is now verified by the Android
lab described below. PDF rendering/SAF/handset flows and other full file-check
modules remain unverified or incomplete.


The eighth runtime-lab contract now packages the source-built x86 PhotoRec
library and sends complete/missing-footer PDF fixtures through the real
production isolated client/service. It records raw native results, UID, exact
payload size/SHA and explicit `pdfRendererExecuted: false`. Static verification
checks all native adapter/contract/fixture source hashes and the NDK-stripped
library bytes. Test APK compilation/static checks pass. Eight actual x86 API 26/35 contracts
now pass at source 8adac7b in run 44915200949, including the >9 MiB native PDF
case. [Downloaded verification evidence](verification/android-worker-runtime-8adac7b.json)
checks source/APK/library/full-report and completion hashes/counts. All **181 host tests**, required generic/native checks,
full ARM64 compilation/lint and APK signature/offline/notices/alignment checks
pass. No host result is labeled as Android FILE/PDF runtime verification.


## Adapter 5: retain established larger files

The original callbacks first establish a footer (or check a declared linearized
size against its footer), then perform optional newline/date reads. Reviewed
`file_pdf.c` and `filegen.c` clear the returned size when footer lookup or its
required read fails. Optional newline handling can append real newline bytes;
date reading never changes the recovered size. A positive returned size therefore
remains usable after an explicitly identified optional read-work limit.

Adapter 5 retains that exact native size when all source/minimum/maximum/offset
checks pass and the stream identifies a per-candidate or aggregate work limit.
Source IO failures, deadline failures, absent footers and other errors retain
null extents. No extent comes from the next header or source tail. Existing
adapter-4 captures keep their stricter original behavior.

The original failure reason, processed bytes and raw time remain visible.
`fileCheckBoundaryEstablished` describes the file boundary separately from
`fileCheckMetadataReadLimited` and its count. These fields mean optional
newline/date work was incomplete; they do not claim complete PDF structure,
metadata or rendering. Java validates the exact new policy, flags, returned
size and counter relationships before exposing an exportable file. Legacy
capture policy and forged IO/deadline/partial claims cannot inherit this rule.
The 8 MiB per-candidate read-work limit, 256 MiB input/aggregate-work limits and
fixed input buffers stay unchanged.

A valid PDF larger than 9 MiB now reaches the file gallery and exact typed save
despite the optional date reader reaching its budget. Independent Python checks
every payload byte and its xref/object locations. Missing-footer and invalid
scope/error/flag/count cases remain refused. The existing gallery case now uses
that larger payload, and the eight-case Android lab adds the same native large
PDF check. Current full-suite/build/Android execution status is tracked in the
release/continuation documents.
