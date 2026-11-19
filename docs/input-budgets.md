# Input sizes, memory buffers and verified scale

A tool's input cap, IO buffer size, report limit and process memory usage are
different quantities. Selecting a large file on Scan does not imply every
specialized parser accepts that same size. A file within its size cap can still
exceed a record, decoding, callback-work, time or export budget. Such failures
remain explicit; a successful partial prefix is not presented as complete.

## Current implemented input caps

These are adapter limits, not verified maximum sizes on a phone. MiB means
1,048,576 bytes; GiB means 1,073,741,824 bytes.

| Tool / operation | Input cap | Other relevant scope |
| --- | --- | --- |
| Primary acquisition / generic scan | 1 GiB | Generic string/hex/entropy previews are bounded separately; native signatures retain upstream limits |
| Whole-file eight-algorithm hashes | 1 GiB | Streaming digest acquisition; report/time/worker budgets still apply |
| ZIP-family member browser | 1 GiB archive | 20,000 entries; each selected expanded member at most 256 MiB; nested depth separately bounded |
| Desktop document batches | 256 MiB/file | 64 files, 512 MiB total captured input; 16 MiB/report, 64 MiB total stored reports |
| Exact aligned binary comparison | 256 MiB/side | 512 MiB pair; difference-record and report bounds remain separate |
| PhotoRec adapter 3 | 256 MiB | 250,000 candidates; 24 MiB native report; per-candidate/aggregate callback work and deadline bounds |
| Binwalk adapter 2 | 64 MiB | Contiguous file-sized native copy; 20,000 raw magic matches and 32 MiB report |
| MPV media probe | 256 MiB | Probe/property/worker limits; initialization is not full-frame/media-integrity verification |
| Collected EXIF/IPTC/ICC/XMP metadata | 128 MiB | Reader dispatch plus directory/tag/XMP/value/report limits |
| Classified whole-input strings | 256 MiB | Record/decoded-text/rule-work limits; strings are evidence, not a behavior verdict |
| Selected DEX document | 32 MiB | Container/entry/class/string/code bounds; APK and multidex parent acquisition have separate limits |
| Compiled Android XML/resource payload | 64 MiB | Parent APK acquisition and each extracted payload have separate identities and budgets |
| Captured base/split package set | 256 MiB/APK | 64 inputs, 512 MiB total; manifests at most 8 MiB each / 64 MiB total |
| Generic measured range/export | 256 MiB/operation | Must fit the exact selected source; unknown extents never become automatic carve-to-EOF |
| Binder command observation | 1 MiB output | This is command output, not a selected binary cap; 4 MiB envelope / 20,000 parsed lines / cooperative time limit |

The source constants and each captured report's own limits remain authoritative.
Separate [carving](photorec-investigation.md), [Binwalk](binwalk-investigation.md),
[media](media-investigation.md), [metadata](metadata-investigation.md),
[strings](string-investigation.md), [DEX](dex-investigation.md),
[resources](android-resources-investigation.md), [hashes](hash-investigation.md),
[batches](batch-scanning.md) and [Binder](binder-registry.md) contracts document
what is acquired, parsed, excluded and exported.

## Large inputs actually exercised on the host

PhotoRec tests now cover both **sparse and fully physically written 80 MiB + 17
byte sources**, with independently compared PNG bytes at a 64 KiB header-cache
seam, a 1 MiB matching-chunk seam, beyond the former 64 MiB cap, and at EOF.
The actual provider also acquires/identifies/cleans its large private snapshot.
A complete **256 MiB sparse input** scans successfully; 256 MiB + 1 is refused.
The static audit of the **51,088,307-byte** frozen alpha APK independently checks
all **111,228** returned magic witnesses and all **38** bounded ranges. That APK
is parsed as data and never executed.

PhotoRec's **1.25 MiB** figure is the payload bound for one 1 MiB matching buffer
and two 128 KiB header-cache windows. It is not a file-size cap, an allocator/RSS
measurement or a bound for the entire worker. The callback window, registry,
records, JNI/JVM, allocators and kernel memory are additional.

Binwalk tests perform a complete **64 MiB sparse-input scan**, with PNGs spanning
a read boundary and ending at EOF. Its actual JNI path preserves the caller's
seek position and records complete read accounting. Binwalk still requires a
file-sized contiguous copy for the pinned validators; PhotoRec's window bound
does not apply to it.

A separate native entropy audit covers every returned measurement/histogram of
a real **50,223,887-byte** APK against independent streaming byte counts, log2
and SHA-256. These checks complement format/media/boundary fixtures; they do not
establish exhaustive format support or Android performance/memory behavior.

All figures above are Linux host verification. Actual ARM64 devices, genuine
isolated UIDs/Binder, document providers, memory pressure, cancellation/crash
latency and API 26/35/16 KiB page runtime remain unverified. The feature checklist
keeps those acceptance items separate from compilation and host fixtures.
