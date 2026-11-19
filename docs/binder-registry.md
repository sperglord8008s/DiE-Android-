# Captured Binder service registry

Open **System explorer → Services → OS Binder service registry**. The app runs
only `/system/bin/service list`, as its existing UID, without a shell, root,
Shizuku, extra permissions or network access. It captures the merged output and
process status before opening the common investigation viewer. This is separate
from **App-declared services**, which reads PackageManager declarations.

## What the evidence means

Binder supplies Android interprocess calls. The platform service manager returns
registered names visible to this caller, and the tool subsequently queries each
name's interface descriptor. These observations do not identify every system
service, its owning process, manifest component, clients, actual client use or
permission to invoke methods. List acquisition and descriptor queries are not an
atomic snapshot; services and visibility can change during or after the query.

| Captured field | Meaning and interpretation |
| --- | --- |
| `serviceName` | Exact name parsed from this output; names are not package/component identities |
| `registryIndex` | The tool's local ordinal, not a stable ID, Binder handle, file offset or address |
| `interfaceDescriptor` | Parsed descriptor text, commonly an AIDL interface name; it does not establish protocol version or implementation identity |
| Empty descriptor | The reference prints empty text when lookup returns no object or when a descriptor is empty; neither liveness nor absence is established |
| `lineIndex`, physical offset/size | Original line ordinal and exact half-open range in **captured command output bytes**, including its line terminator |
| `rawText` / `rawHex` | Complete valid UTF-8 line content, or exact bytes for an invalid UTF-8 line; no replacement-character decoding |
| `outputComplete` | Captured EOF and a known process exit within the cooperative capture budget; not a global service-completeness claim |
| `commandSucceeded` | Complete output and exit code zero; a nonzero exit can still have completely captured diagnostic bytes |
| `consistentWithReportedList` | One recognized header, matching row count, unique names, consecutive unique indices, no diagnostic lines and successful command; this checks text consistency, not global visibility or atomicity |
| Caller UID/PID, platform/API/build | The querying app and its OS context, not a service-owner identity |

The parser follows the original Android 15 `service.cpp` output contract at
`f7274fca5e36082674740bc6c976f73c4578d009`; original source SHA-256 is
`b559db30c430011667ede52a1ebb4b05a2c474bbe4df85d47c398298191bab94`.
The unmodified source and Apache-2.0 notice are packaged offline; Root views can
show/export it. This is a reference, not proof that an installed ROM's executable
matches those source bytes. Unknown output remains diagnostic evidence. Embedded
unescaped newlines/delimiters in names or descriptors can make this text format
ambiguous; original bytes remain authoritative.

## Capture, navigation and ownership

Root views expose parsed registration observations, every original output line,
command diagnostics and the full capture/status/visibility summary. Duplicate
names/indices remain separate; repeated headers and oversized decimal fields
remain visible rather than replacing earlier evidence. Empty descriptors retain
explicit uncertainty. A failed/inconsistent command opens the summary by default,
so denial text is never presented as a registration. Display names have bounded
previews; full fields and complete scoped exports retain their values.

Search and 50/10/1-row pages reuse the common viewer. A line links back to its
original ordinal and exact captured-output bytes for hex/range tools. These are
text-byte coordinates, never service-memory addresses. Filtered table exports
contain every row in that scope. Separate exports preserve exact raw output and
the original complete JSON capture envelope, including context and unknown data.
The envelope owns Base64 output, SHA-256/length, argv, exit/error/timeout/cap state
and caller/platform facts; table metadata references its complete byte identity.

The producer writes a new private capture once. Its independently recorded
SHA-256/length are supplied to the viewer and checked before parsing. Two owned
snapshots separate the envelope and raw-output coordinate domains. Recreation
reopens that exact capture; navigation never reruns the platform command. Changed
or expired capture sources fail explicitly. Prepared exports are independent
of later source mutation/expiry, view changes and recreation. Normal viewer close
removes its owned snapshots and original private capture; configuration recreation
retains the original source. A fresh registry query creates a new observation.

## Bounds and verification

Limits: 1 MiB collected output, 20,000 parsed lines, 4 MiB JSON envelope and a
5-second cooperative command/output deadline. The owning child process is
terminated on output cap, timeout, cancellation or failure. Output may be an
explicitly incomplete prefix after a cap/timeout; it cannot become a complete
successful registry. Cleanup includes closing owned streams and bounded thread/
process waits; Android scheduling, pipe closure and termination latency are not
hard real-time guarantees. Eight outstanding private captures bound orphan file
accumulation; app cache expiry or process death can still remove/leave captures.
No automatic eviction pretends to preserve evidence that was removed.

`scripts/test-system-services.sh` verifies the pinned source/notice identity,
then runs actual trusted child JVMs for process output, nonzero exit, byte caps,
timeout, cancellation and cleanup. It checks Unicode/CRLF/final CR/invalid UTF-8,
errors, duplicates, repeated headers, decimal overflow, exact ranges/hashes,
malformed accounting, scoped exports and private-source lifetimes. These child
JVMs are test helpers, never inspected samples.

Three API 28 Android host cases exercise the actual provider/widgets: 155-row
paging/search and complete filtered exports, original-source reference identity,
diagnostic separation, immutable bytes through recreation/source expiry, and the
real production launch-failure/digest-writing path on a Linux host without
`/system/bin/service`. Combined with prior cases, the full suite has 124 checks.
Actual Android command output, visibility/SELinux, real providers, process death,
API 26/35 and handset lifecycle/cancellation remain unverified. Privileged/live
process diagnostics and a read-only opt-in bridge remain separate pending work.
