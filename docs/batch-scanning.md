# Captured desktop batches

**Scan → Batch** and the desktop laboratory open the same offline batch tool.
Select 1–64 Storage Access Framework documents. Selection order is retained;
duplicate names and even duplicate URIs remain independent ordinal inputs.
The shared flags, tag filters and preview string minimum are captured when the
picker returns, before work is queued. Applying a profile or changing settings
afterward cannot relabel those results. Automatic format selection (`typeId: 0`)
is used for every input; named profile attribution and forced per-file formats
are not implemented.

`EngineDatabase.prepare` supplies one pinned CDiE/bundled signature revision and
one active custom-database snapshot for the entire batch. A subsequent custom
database activation does not change those captured paths. Existing custom
snapshots are immutable and are not deleted by activation. No database download,
file execution, installation, class loading or network operation occurs.

## Acquisition and execution

Acquisition, session persistence, actual analysis, evidence tables and Android
widgets are separate classes: `BatchScanSession`, `DesktopBatchAnalyzer`,
`BatchScanBackend` and `BatchScanActivity`.

Each provider stream is copied into a newly generated private `InputSnapshot`.
Names are evidence labels, never filesystem paths. The snapshot's complete
SHA-256 and byte length are recorded before scanning. Provider labels are used
when available; otherwise the URI's last segment is the display label. The
original acquisition URI remains a local provenance field. Copies are sequential
observations; they do not establish an atomic view of a directory or package set.

The actual analyzer serially runs complete-input generic inspection, CDiE's
desktop signature request and native format facts. The inspector's independently
computed SHA-256/length must match acquisition. A post-analysis full snapshot
identity check precedes report storage. Native rows, parents, exact signature
paths, flags, diagnostics and returned unknown/error fields remain in the
original `org.diemobile.scan-report` envelope. Android-specific package, DEX,
metadata, string classification, carving and codec engines are separate tools;
this batch does not imply they ran.

One request-owned `Engine.Cancellation` belongs to each active native scan.
Cancelling the batch also prevents later acquisitions and native requests. A
queued native cancellation cannot poison a subsequent batch. Provider reads and
native parsing remain cooperative: a blocked read/parser may need to return
before cancellation settles. There is no hard deadline or process isolation for
CDiE, and a native crash can still stop the app process.

## Journal, bounds and lifetime

Private sessions live in `filesDir/desktop-batches/batch-<UUID>`. A complete plan
is journaled before the first acquisition; every phase/outcome is checkpointed
through a synced temporary file and same-directory atomic rename. `latest` is a
separately atomically replaced UUID reference. Neither atomic rename nor host
tests prove power-loss durability or directory-entry fsync semantics on a device.

| Bound | Limit / behavior |
| --- | --- |
| Selected inputs | 64; larger selections fail before starting, without scanning a prefix |
| Captured input | 256 MiB each, 512 MiB total, including retained failed-scan inputs |
| Encoded stored report | 16 MiB each, 64 MiB total; over-budget reports fail explicitly |
| Journal | 2 MiB, bounded source labels/diagnostics and exact integer control fields |
| Retained batches | Eight; explicitly delete an unneeded saved batch before starting another |
| Interactive evidence | Existing 50/10/1-row paging, search and 4 Mi-character row bounds |
| Export staging | Existing complete streamed 256 MiB private export bound |

Every planned input retains an outcome, including `failed`, `cancelled` and
`not-started`. Per-file `stage`, error, elapsed time, input identity and independent
stored-report SHA-256/length remain visible. `complete` means the desktop scan
returned successfully, not that all possible formats or analyses were covered.
`finished` means the plan settled; it can contain failed inputs. Inspect the
outcome counts and every native diagnostic. Zero detections do not establish
exhaustive coverage or safety.

In-process configuration recreation retains the worker/request owner instead of
starting a second batch. A journal found in `planned`/`running` state after process
death remains visibly incomplete and is never automatically resumed. Temporary
files can remain if the process dies between creating an artifact and its next
checkpoint. Saved-batch deletion removes that session's owned directory, including
orphan stages. Automated orphan reclamation, resumable jobs, background execution
and exhaustive crash/power-loss recovery are not implemented.

Saved batches persist until explicit deletion, and can consume substantial
storage. Deletion takes the same per-session OS file lease as execution; an active
batch cannot be deleted, including from another activity. An unreadable/damaged
journal can be deleted without parsing its evidence, through a canonical UUID
directory ownership check. Deletion never follows an imported path or removes
exported documents. Low-storage failures are reported; no silent eviction occurs.

## Investigation and export contracts

The viewer exposes all planned outcomes and each stored native detection tree.
The original native row/parent and source ordinal are retained across search and
paging. Aggregate/detection rows have no invented physical offset. Select
**Exact owned input / hex / range export** to switch byte ownership to that
specific snapshot, independently checking its whole-file identity. A duplicate
display name cannot select another file's bytes. The aggregate byte source is an
independent captured journal, with its own length/SHA-256 and JSON coordinate
domain.

`org.diemobile.desktop-batch`, version 1, contains the UUID, creation/finalization
times, batch status/error, original options/engine identities, counts, limits and
complete ordered `entries`. Every entry includes its source ordinal, name,
acquisition URI, outcome/stage/error, identities and complete nested scan report
or explicit `null` if none was stored. Private filesystem paths are excluded.
Unknown/native report fields and isolated UTF-16 units survive streamed export.

Stored reports are length/SHA-256 checked and cross-checked against the entry's
source identity, captured flags/tags/minimum and engine/database revisions before
use. Corruption fails the export rather than silently removing a report. Reports
can still be exported if their original provider or acquired input bytes have
expired; byte actions then fail explicitly. Complete exports ignore UI filters
and pages, and are staged before opening the destination picker. Recreation,
later setting changes, deletion and newer journals cannot regenerate or replace
an already staged export. Cancel/failure discards its owned stage; failed
destination writes can leave partial provider data.

## Verification and remaining work

`bash scripts/test-batch.sh` exercises actual files/Inspector, full-plan failures,
duplicate identities, 77-row paging/full exports, exact Unicode, cancellation,
interrupted journals, source/report mutation, budgets, provider-close failures,
active file leases and deletion. Its injected analyzer tests journal contracts;
it does not stand in for native-engine verification.

Seven API 28 Robolectric cases use actual CDiE JNI, database installation,
Inspector, private files and widgets. External document streams are substituted.
They cover multi-file scans and failures, captured versus later settings, actual
result-viewer wiring, a cancelled request queued behind the engine lock and a
successful next batch, worker retention through configuration recreation,
complete delayed exports through deletion/newer journals/recreation, failed or
cancelled destinations, saved/damaged-journal deletion and over-limit selection.
The complete suite now has 100 checks; see current CI for the committed revision.

Real SAF grants/providers, process death while writing, low-storage behavior,
API 26/35, accessibility and ARM64 handset cancellation/crash flows remain
unverified. Directory/tree traversal, resumable/background jobs, arbitrary-engine
batch composition, report comparison, per-file manual formats, named profile
attribution and full desktop/Qt batch/settings equivalence remain incomplete.
