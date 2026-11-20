# Checked recovered content

Binwalk and PhotoRec candidate actions now connect bounded stored ranges to
independently owned local previews. A range keeps its original parent SHA-256,
parent length, physical offset, byte length and candidate label. The complete
original candidate/callback records and catalogs remain in the parent tool's
full report exports; persistent per-preview native-origin sidecars and broader
cross-session investigation history remain pending.

## Acquisition and export

`OwnedContent` separates byte acquisition from provider/model and Android UI.
It checks the complete owned parent's identity before and after copying the
selected range, uses 64 KiB IO blocks, hashes the acquired child independently
and discards failed/cancelled stages. Gallerize uses the same implementation.
The 256 MiB range/export limit is separate from the IO buffer size, total JVM
memory, original engine limits and aggregate cache storage.

For these two carving providers, **Export exact stored range** also checks the
complete captured parent on both sides of staging. Suggested names identify the
engine, original candidate index, physical offset and a constrained native
format hint. The format extension does not establish complete file validity.
Unknown extents retain their original metadata/hex actions and have no automatic
preview or exact-range export. No extent is inferred from the next header.

**View exact recovered bytes / media / text** opens an independent private child.
The parent tool and original selected file can close without invalidating that
child. Existing view-as controls, EXIF/XMP metadata, MPV probing, classified
strings, archive selection, native tools and Gallerize work on those exact
acquired bytes. Platform decoding remains a separate observation from the
native header/data-check result.

The preview's **Export bytes** stages the exact complete child, independently
of rendering mode. Pending picker state owns the stage, so source expiry and
activity recreation cannot silently regenerate a different payload. Cancelled
and failed exports discard their private stages; a failed document-provider
write explicitly reports that its destination may contain partial data. The
original selected input is never modified.

## Whole captured input and gallery lifetime

**Root views → Gallerize this captured input** transfers a separate private
snapshot through `gallery-handoffs`. The receiver checks its canonical private
location, generated filename, SHA-256 and length before acquiring its own
Gallerize session. It releases only an eligible private handoff, including
failure cleanup; a forged external path is not deleted. Recreation restores
the captured gallery without rescanning or requiring the old handoff.

This opens Gallerize's separate combined PhotoRec/Binwalk discovery over the
captured input; see [actual recovered-file gallery and exports](recovered-file-gallery.md). It does not relabel the original Binwalk results as PhotoRec evidence
or claim that their validators have identical coverage. Every native report
and its source/engine context retains its own scope.

## Record presentation

Investigation rows use a bold name/index, readable proportional description
and monospace physical-offset/length facts. Record details prioritize the name,
location and description before the original JSON fields. Complete raw data and
selected-record/table exports remain available. Individual/global expandable
mechanism definitions stay separate from factual failure/coverage information.
There are no appearance themes or decorative controls added to this workflow.

## Verification

Four API 28 Android host cases use actual Binwalk and PhotoRec JNI, real media
bytes and the production models/widgets. OS worker transport and document
output streams are substituted. They cover both engines' exact PNG payloads,
parent/source expiry, cancelled/retried/failed output cleanup, prepared byte
export through recreation after child expiry, private gallery ownership and
capture-preserving restoration, forged external-path refusal, unknown extents
and same-length parent-mutation refusal. Existing PhotoRec and Gallerize cases
also pass. These are separate from actual isolated Android UID/IPC behavior.

Independent host acquisition cases check byte equality, original-file
preservation, source mutation, cancellation cleanup and independent lifetimes.
The existing fully written 80 MiB + 17 byte gallery/entropy fixture still passes
after the common acquisition change. Full-suite results and the current build
are recorded in the feature checklist/continuation document.

Real handset/SAF providers, process death during a write, cache pressure, media
codec/PDF execution, actual isolated worker/crash timing and total Android
memory/performance remain unverified. See the Gallerize, Binwalk and PhotoRec
contracts for their distinct discovery, integrity and recovery limits.
