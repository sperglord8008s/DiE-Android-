# Large text and captured report navigation

The independent content preview reads UTF-8 as bounded pages instead of stopping
after the first MiB. **First**, **Back**, **Next**, **Tail** and **Byte…** show
the actual half-open byte interval within the captured input. Byte positions
accept decimal or `0x` hexadecimal. Hex has corresponding controls and prints
the selected input's actual byte addresses. Other preview modes and complete
byte exports remain available.

`Utf8Pages` reads at most 65,536 bytes and returns at most 16,384 UTF-16 units
per page. The decoder reports malformed input; a partial final codepoint in a
bounded sample is not mistaken for an invalid file. Successive pages consume
exact byte boundaries without replacing, repeating or dropping seam characters.
A manual jump into a continuation byte is refused. The last valid displayed
page and its cursor remain available after a failed navigation request.

Tail decodes the final bounded window and selects a complete-character suffix;
it does not validate earlier bytes. Automatic text recognition also samples
only a prefix. Choosing text is not a whole-file validity claim. UTF-16 and
legacy encodings require the existing string tools or Hex. Source SHA-256 is
checked around content-preview page reads, with cooperative cancellation when
the selected rendering is superseded. This can take time on a large input.

The last 512 visited text positions form the Back history. If older positions
expire, the status says so; First, Tail and byte navigation remain available.
Content preview recreation retains text history, explicit mode and Hex offset.
Invalid saved positions reset visibly instead of silently selecting other bytes.

## Complete generated text

The investigation viewer's **rendered text** actions stage their complete output
once using `PreparedExport`, then `PagedTextDialog` reads bounded UTF-8 pages on
the existing worker. The parent parser is not rerun for navigation. The staged
report remains available after its source disappears. Export transfers that
same complete snapshot to the existing document picker. Close releases only
the owned report stage; closing before the first frame also releases the busy
state without waiting for the queued read. The dialog adapts its scroll area
to the screen height and keeps the navigation controls outside the text body.

This change applies to backend rendered-text actions, including full JSON and
smali reports. Selected-record fact dialogs still have their explicit 128 KiB
display limit; full scoped JSON exports remain available. Report staging keeps
the existing 256 MiB encoded-output budget. Dialog navigation itself is not
restored after recreation; pending document-picker exports retain the existing
ownership/recreation behavior.

## Verification and limits

`bash scripts/test-utf8-pages.sh` uses a 128 MiB JVM and independent raw-byte and
Unicode reconstruction oracles: 90 generated Unicode cases / 161,892 complete
partition pages, a fully written 24,117,266-byte file and its tail, malformed
and truncated UTF-8, invalid bounds, cancellation and history restoration.

Five API 28 Robolectric cases exercise real private snapshots, page IO and
widgets: supplementary-character recognition at the 4,096-byte sample seam;
text pages past the old one-MiB boundary, Back and recreation; Hex addresses and
tail; complete captured report navigation after source expiry and unchanged
export bytes; stage cleanup and deterministic Close before the first frame.
Only the generated report text producer is controlled for that report case.

These checks do not establish Android handset rendering performance, real SAF,
process death, accessibility or API 26/35 acceptance. They do not expand the
existing content-input or export budgets. See [input budgets](input-budgets.md)
and the [feature checklist](FEATURE-PARITY.md).
