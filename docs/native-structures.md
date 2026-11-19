# Typed native structures and fields

Native Tools → **Structures** calls the pinned CDiE `cdie_file_info_visit_slice`
visitor. The adapter detaches borrowed records synchronously; it does not add
another executable-format parser. PE, ELF, thin Mach-O and universal/fat Mach-O
are supported by that upstream entry point. Other readers return an explicit
failure. This is implemented native DataStructs coverage, not verified parity
with every desktop format or widget.

The result is an additional `structures` kind of
`org.diemobile.native-records`, version 1, inside the existing complete tool
report. Adapter version 1 and reference policy `reviewed-field-domains-v1` are
recorded in native metadata and packaged engine provenance. Search and 50-row
pages operate over the detached snapshot; exporting a filtered view still
exports every returned structure and field.

## Coordinates and returned values

Rows remain in visitor order. A `structure` row retains the native ID,
classification, signed offset/address/entry-size/total-size and unsigned count.
`nativeStructureType` means 0 unknown, 1 structure, 2 entry, 3 footer, 4 locator,
5 raw data. Raw data descriptors intentionally have no named-field schema.
Fixed-size tables can be expanded by CDiE into individual occurrences; inventory
can contain aggregate or already separate descriptors, depending on the reader.

A `field` row names its `parentStructureIndex`, declared type, signed
`relativeOffset` and native size. Parent structure offsets are absolute in the
whole selected input. Field storage is `parent.nativeOffset + relativeOffset`;
`physicalOffset` is supplied only when the complete parent fits the input and
the complete field fits both parent and input. Negative/unknown native values
remain explicit strings, with null usable coordinates. A usable zero-length
range still supplies no byte at EOF. These rules also apply after CDiE shifts a
chosen fat Mach-O slice into whole-input coordinates.

Every returned field retains `rawValue`, `displayValue`, `nativeComment`,
`nativeName`, numeric semantic `properties`, known `propertyNames` and
`unknownPropertyBits`. Names/types/raw/display/comments additionally retain
`nativeTextUnits`: eight hex digits per native wchar unit. JSON text uses exact
UTF-16 escapes; invalid 32-bit code points render U+FFFD while their original
units remain available. Null native text is distinct from an empty string.
These are the complete values returned by the reader; upstream display/string
caps are not reconstruction of bytes the reader did not return. Stored ranges
can be inspected/exported separately.

`nativeValue` preserves the exact `xx_var` type ID/name. Signed and unsigned
integers use decimal strings, retaining all 64 bits. Float/double values include
their exact IEEE bit pattern, including signed zero and NaN payloads. Native
string/byte variants retain length-delimited `bytesHex`, including embedded NUL
and invalid UTF-8; wide variants retain text and exact native units. An absent
variant remains null: CDiE's synthetic fat-header fields supply raw/display text
without an integer variant. An opaque process pointer is never dereferenced or
exported as an address; its presence and limitation are explicit. Future unknown
variant types fail the complete request rather than dropping a value.

## Evidence relationships

| Property | Mask | Meaning and limit |
|---|---:|---|
| Identifier | `0x1` | Native identifier; inspect format-specific interpretation |
| Virtual address | `0x2` | Can mean a preferred VA or a PE RVA; flags alone do not resolve it |
| Offset | `0x4` | May be whole-file, slice-relative or another format-relative offset |
| Size / count | `0x8` / `0x10` | Declared extent/cardinality; does not independently validate a target |
| Pointer | `0x20` | Reference-like value; no address domain is inferred from this bit alone |
| Flags | `0x40` | Stored bit field; raw mask and reader explanation remain visible |
| Timestamp | `0x80` | Declared date/time; not proof of acquisition or authorship |
| String / reserved | `0x100` / `0x200` | Native text or reserved/padding role |
| Signature | `0x400` | Format magic/signature field; not publisher authentication |
| Checksum | `0x800` | Stored checksum/hash field; does not mean it was verified |

Field actions always distinguish **storage bytes** from **resolved field
values** and can open the containing structure. Reviewed whole-input offset
relationships cover PE DOS/COFF/section/debug pointers, ELF header table offsets
and fat-architecture slice offsets. Absent zero offsets do not create links.
Reviewed preferred-VA relationships cover PE ImageBase, ELF entry and Mach-O
segment/section addresses. PE entry/section RVAs require a captured ImageBase;
Mach-O segment/section file offsets require the captured slice header when fat.
Zero-fill section flags suppress stored-section links. Missing base/section
metadata, unknown domains and unreviewed fields create no value-navigation
link; their complete raw evidence and storage-byte actions remain available.
PE data directories deliberately require further field-specific review: the
certificate/security directory's address is a file offset, unlike ordinary
RVA directories. Selecting only `NAME#FIELD` can omit context required for a
value relationship. A mapped target remains static evidence, not proof of use.

Technical references: [Microsoft PE format](https://learn.microsoft.com/en-us/windows/win32/debug/pe-format),
[ELF generic ABI](https://gabi.xinuos.com/elf/), and Apple's
[Mach-O loader declarations](https://github.com/apple-oss-distributions/cctools/blob/main/include/mach-o/loader.h).
The authoritative adapter contract is the checked-out pinned
`vendor/CDiE/src/app/cdie_file_info.h` and
`vendor/CDiE/dep/xxfclib/include/xxfclib/formats/xx_format.h`.

## Selection, limits and cancellation

Blank selector means `Info`; `NAME`, `NAME#FIELD` and native family-qualified
selectors are passed to CDiE. Inventory omits fields. A missing selector/field,
wrong family, unsupported format or invalid inventory request is an error,
not an empty successful scan. Fat Mach-O uses an explicit zero-based slice
index, or the native build's architecture if present, else the first slice.
Other slices remain raw ranges. Thin formats ignore the index. The requested
index, selector, inventory and counts are captured; tool reports use
`machOSliceIndex` and leave `physicalOffset` automatic for Structures, so an
index is never mislabeled as a file location.

The adapter accepts regular files up to 256 MiB, at most 10,000 combined
structure/field rows, 16 MiB complete JSON, and 512 Ki native text units or bytes
per value. Metadata counts and exact bounds are checked by the Java model.
Unknown property bits and additional object members survive export. Exceeded
limits discard the candidate and return failure without a successful prefix;
use a narrower native selector to inspect a large format. No signature database
is used. Input identity is the parent inspection identity, not a new hash of
the whole file; existing native-tool source lifetime limitations still apply.

A request-owned BooleanSupplier is observed synchronously by CDiE progress
helpers and visitor callbacks. Active cancellation and a 30-second monotonic
cooperative deadline discard the candidate. Calls are serialized with the
other engine operations; no global stop request can poison the next request.
This is not an OS kill deadline or native-parser isolation. Other legacy native
tables retain their existing before/after cancellation behavior.

## Verification and remaining work

`scripts/test-native.sh` uses the actual JNI and production model on generated
PE32/64, ELF, high-VA Mach-O and a two-slice fat Mach-O. Independent Python
`struct` checks compare 30 field values, sizes, semantic bits and whole-input
coordinates with original bytes. Fixtures cover inventory/selectors, missing
fields, malformed/unsupported inputs, over-limit rows/input sizes, active
cancellation/recovery, unknown metadata/property bits and rejected coordinate,
parent, type and text-unit corruption. A trusted C fixture exercises the exact
native serializer; Python reconstructs integer limits, IEEE signed zero/NaN
payloads, invalid UTF-8/NUL bytes, all 65,536 UTF-16 units and invalid wchar
values. Inspected programs are never executed.

Three additional real-JNI Robolectric API 28 cases exercise PE storage versus
RVA navigation/Back/full filtered export/recreation, explicit fat-slice storage
and value coordinates/selector context, and exact field-byte exports across
source mutation/recreation. Test streams substitute for SAF providers. These
checks do not establish handset/ARM64 execution, real SAF grants, API 26/35
behavior, memory pressure, crash isolation, exhaustive format semantics,
complete per-field references or power-loss/process-death recovery.
