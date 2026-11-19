# Read-only archive acquisition

The Archive browser reads ZIP-compatible central directories using the platform's `java.util.zip.ZipFile`. `ArchiveStore` separates inventory and extraction from Android widgets. This is a user-selected acquisition path, distinct from CDiE's recursive signature-scanning archive pass and its coverage limitations.

## Evidence and selection

Inventory retains every central-directory record within its budget, including duplicate names and explicit directory entries. Records keep their original zero-based directory index, name, declared expanded/compressed byte lengths, compression method, CRC32 and duplicate-name count. The folder view groups path prefixes and presents searchable 50-row pages. Synthetic folder rows do not establish that an explicit directory record exists. The current browser does not expose local-header flags, ZIP extra fields/comments, signatures or every central-directory field; full ZIP structure inspection/export is future work.

Select a file row to see its complete name and metadata, then choose **Analyze member** or **Browse nested ZIP**. Names are display data, including absolute names, `..` segments and backslashes. They are never joined onto an output filesystem path. Only a conservative extension can influence the suffix of a random private output filename. Duplicate names remain visible, with extraction disabled because the reader's name-based selection would be ambiguous.

Before extraction, the reader reopens the archive and compares the complete bounded directory, in order, with the captured inventory. An unrelated changed member also invalidates the selection. The same open ZIP handle supplies the selected stream. Extraction checks actual expanded length and CRC32 and computes a new SHA-256. A changed file length, oversized stream, stale directory, malformed/unsupported compression, CRC mismatch or cancellation fails without publishing a partial snapshot. The input is read-only.

CRC32 checks accidental corruption, not authenticity. Directory declarations are not proof of content until the selected member is extracted. The newly computed SHA-256 identifies the extracted bytes; it does not authenticate an archive's author. Whole-parent archive SHA-256 relationships are not yet exported as a structured provenance chain. Selected leaves return their display chain and private file to Main Scan, which reacquires and hashes those exact leaf bytes for the usual tools/reports.

## Nested navigation and ownership

**Browse nested ZIP** extracts one member to a private random snapshot and reads its directory. It never guesses from a filename extension or executes an archive's contents. Failure leaves the parent folder/filter/page usable and discards the failed snapshot. **Back archive** returns to the parent location and removes the abandoned nested snapshot. Android Back first moves to a parent folder, then a parent archive. Reload rereads the current snapshot.

The navigation trail retains each level's folder, filter and page, including across Activity recreation. Saved state contains small path/parameter descriptors, not directory models or payloads. Owned restored paths must be in the private snapshot directory, use the expected generated prefix and match their saved length. Missing/expired nested state reopens the outer archive with an explicit notice. Parents are re-inventoried when revisited rather than retaining many large models.

On ordinary finish/cancellation/failure, owned nested files are deleted. Ownership of an **Analyze member** leaf transfers to Main Scan, which retains it for rescan and later removes it. The caller's outer archive is never deleted by the browser. Once a selected leaf replaces Main Scan's input, the browser trail has ended; persistent cross-scan parent history remains future work. Abrupt process death can leave private orphan snapshots; scavenging/crash recovery is still pending.

## Budgets and unsupported cases

- Source archive: 1 GiB; complete inventory: 20,000 records and 8 Mi-characters of names. Over-limit inventories fail explicitly instead of returning success with missing records.
- Selected member: 256 MiB expanded; supported methods: stored (0) and deflate (8), as supported by the platform ZIP reader. Password/encrypted members and other compression engines are not implemented.
- Navigation: 16 total levels, 512 MiB of retained nested snapshots, and 48 Ki-characters of retained member-name/folder paths. Filters accept up to 1,024 characters. These text/depth budgets bound saved state and report a limit before navigation exceeds it.
- Cancellation is checked before directory work, between records and between 64 KiB extraction reads. Opening `ZipFile` and an individual platform I/O operation must return first. Output/data limits do not impose a hard CPU budget or isolate a faulty parser.
- Non-ZIP families, damaged-directory recovery, local-header traversal and comprehensive ZIP64/encoding/encryption fixtures remain pending. Availability of a Java ZIP API is not proof of complete format or device coverage.

## Verification

`scripts/test.sh` tests actual stored/deflated/empty payloads and independent SHA-256 expectations, traversal-name safety, duplicate records, CRC mismatch, same-length directory changes (including an unrelated member), oversized declarations/input, record caps, truncation and streaming-cancellation cleanup.

`scripts/test-workbench-ui.sh` additionally runs five API 28 Robolectric archive cases: paginated/filter-preserving nested Back; recreation and selected-leaf ownership transfer; invalid nested-container recovery; queued cancellation/reload; and the 16-level boundary with a usable retained parent. They use real ZIP streams and the production Activity. Actual handset/ARM64 lifecycle, document-provider acquisition, small-screen layout, cache-pressure/process-death behavior and API 26/35 verification remain pending.
