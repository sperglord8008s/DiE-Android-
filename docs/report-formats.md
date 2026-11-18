# Complete JSON, XML, CSV and TSV scan captures

The primary **Save report** action keeps its existing JSON behavior.
**Reports / Log** also exposes complete XML, CSV and TSV exports. Every format
uses the same captured input, original options, engine/database revisions, native
evidence, generic previews, knowledge text, diagnostics and unknown fields.
Filters and displayed pages do not shorten these captures.

`ScanReportFormats` is an Android-independent streaming renderer. The report is
constructed from the captured scan state and fully staged before the document
picker. File names contain the original SHA-256 prefix and selected extension;
MIME types are `application/json`, `application/xml`, `text/csv` and
`text/tab-separated-values`. Pending name/MIME/bytes survive recreation and source
expiry. Later scan/settings changes cannot replace a prepared export. Cancelled
or failed output discards only the owned stage; failed provider writes can leave
partial destination data. No external viewer is automatically launched.

## JSON and XML

JSON remains `org.diemobile.scan-report` version 1, using the existing lossless
decoded-string encoder. Original encoded file bytes and JSON whitespace are
separate from these JSON values.

XML uses `org.diemobile.scan-xml` version 1:

```xml
<die-scan-report schema="org.diemobile.scan-xml" version="1">
  <object>
    <member>
      <key encoding="json-string">"schema"</key>
      <string encoding="json-string">"org.diemobile.scan-report"</string>
    </member>
  </object>
</die-scan-report>
```

The example shows one member; exports retain the entire report recursively.
Objects contain ordered `member` elements, each with a `key` and typed value.
Arrays contain `item index="…"` elements. Scalars use `string`, `number`,
`boolean` or `null` elements. Numbers use the shared JSON numeric representation;
null is explicit. Source keys never become XML element or attribute names.

XML 1.0 cannot directly represent NUL, isolated UTF-16 surrogates and some
noncharacters. Keys/string values therefore contain **ASCII JSON string
literals**, followed by normal XML text escaping. Parse XML text first, then
JSON-decode that literal to recover the exact original string units. This also
retains quotes, ampersands, tabs, line breaks and supplementary characters without
replacement. Numeric XML character references are not used to smuggle invalid
XML characters. No DTD or source-supplied markup is emitted.

## CSV and TSV

Both tabular formats use quoted fields, doubled quotes and CRLF record endings;
CSV separates fields with commas, TSV with tabs. The columns are:

```text
record_type,index,parent,depth,category_json,name_json,signature_json,text_json,value_json
```

The first data row is always `report-metadata`. Its `value_json` is an ASCII JSON
object with `schema: "org.diemobile.scan-table"`, `schemaVersion: 1`,
`reportMetadata`, `nativeRowsSeparated` and `nativeRowCount`. Metadata preserves
every original report field except a native `scan.rows` array detached into
subsequent records. Non-array/null/missing row fields remain in metadata exactly
as captured. Thus failed scans do not turn into unexplained empty CSV files.

Every following `native-row` retains its original zero-based index and complete
raw object in `value_json`. The separate parent/depth columns describe available
hierarchy; `_json` columns expose convenient original field values. Decode these
cells as JSON after CSV/TSV parsing, rather than treating JSON quotes/backslashes
as report data. Missing optional fields appear as JSON null in convenience
columns; the complete raw object still distinguishes missing from explicit null.
Unknown fields, nested arrays/objects, typed values and diagnostics are retained.

To reconstruct the complete original report, parse the metadata row, collect
`value_json` from every native row in ordinal order, and restore `scan.rows` only
when `nativeRowsSeparated` is true. Retain the original failed-scan status and
limits when interpreting rows. This is the app's versioned complete-capture
format, not a claim of byte-identical Qt/diec table serialization.

All JSON-bearing cells use ASCII escapes, including every non-ASCII UTF-16 unit.
This is deliberate: it preserves strings that cannot be represented directly in
UTF-8, and avoids encoding-dependent spreadsheet interpretation of raw source
strings. Source values are never executed by this exporter. Consumers may need to
raise their CSV field-size limit for complete metadata/large native records; the
independent test decoder demonstrates reconstruction with Python's standard
`csv`/`json` libraries.

## Bounds and verification

Renderers stream through bounded private export staging (256 MiB encoded).
XML/JSON retain the existing 256-level/two-million-node bounds. Tabular output
supports 20,000 native rows and streams metadata/cells without materializing a
second complete report string. Cancellation is checked in traversal and text
transforms. Failure removes the stage and does not open a destination for a
successful prefix. This is a complete capture of the existing report; bounded
ASCII/hex previews and upstream analysis coverage do not become exhaustive merely
because another export format is selected.

`bash scripts/test-report-formats.sh` uses Python's independent standard XML/CSV
parsers to reconstruct every field and type from all formats. Fixtures include
all 65,536 UTF-16 units in names/keys/values, NUL/noncharacters/isolated surrogates,
quotes/delimiters/CRLF/tabs, 125 native rows, unknown nested metadata, numeric/string/
boolean/null distinctions and failed/empty/missing/null row scopes. Full
20,000-row streaming, cancellation and XML depth limits are checked separately.

Two additional API 28 cases join the primary-export suite (now five cases).
They exercise actual Google-decoded input/string values, real widgets, XML
parsing, CSV/TSV metadata, selected MIME/extensions, pending recreation, later
state/source expiry and exact destination bytes. Only document-provider streams
are substituted. The full suite has 110 checks; consult the committed revision's
CI. Real providers, handset/storage/process-death flows and full Qt serialization
compatibility remain unverified.
