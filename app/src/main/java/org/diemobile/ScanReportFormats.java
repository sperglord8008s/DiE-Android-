// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Streaming, reversible report formats. String cells retain JSON encoding to preserve UTF-16. */
final class ScanReportFormats {
    enum Format {
        JSON("json", "application/json"),
        XML("xml", "application/xml"),
        CSV("csv", "text/csv"),
        TSV("tsv", "text/tab-separated-values");
        final String extension, mime;

        Format(String extension, String mime) {
            this.extension = extension;
            this.mime = mime;
        }
    }

    private static final String[] COLUMNS = {
        "record_type",
        "index",
        "parent",
        "depth",
        "category_json",
        "name_json",
        "signature_json",
        "text_json",
        "value_json"
    };

    private ScanReportFormats() {}

    static void write(Writer output, JSONObject report, Format format, BooleanSupplier stop)
            throws Exception {
        EvidenceRows.check(stop);
        if (!"org.diemobile.scan-report".equals(report.getString("schema"))
                || report.getInt("schemaVersion") != 1)
            throw new IOException("Unsupported primary scan-report schema");
        if (format == Format.JSON) EvidenceJson.write(output, report, stop);
        else if (format == Format.XML) {
            output.write(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<die-scan-report schema=\"org.diemobile.scan-xml\" version=\"1\">");
            new Xml(output, stop).value(report, 0);
            output.write("</die-scan-report>\n");
        } else table(output, report, format == Format.CSV ? ',' : '\t', stop);
        EvidenceRows.check(stop);
    }

    private static void table(
            Writer output, JSONObject report, char delimiter, BooleanSupplier stop)
            throws Exception {
        JSONObject scan = report.getJSONObject("scan");
        JSONArray rows = scan.optJSONArray("rows");
        int count = rows == null ? 0 : rows.length();
        if (count > 20_000) throw new IOException("Tabular report exceeds 20,000 native rows");
        JSONObject metadata = new JSONObject();
        for (Iterator<String> keys = report.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!key.equals("scan") || rows == null) metadata.put(key, report.get(key));
            else {
                JSONObject nativeMetadata = new JSONObject();
                for (Iterator<String> fields = scan.keys(); fields.hasNext(); ) {
                    String field = fields.next();
                    if (!field.equals("rows")) nativeMetadata.put(field, scan.get(field));
                }
                metadata.put(key, nativeMetadata);
            }
        }
        for (int i = 0; i < COLUMNS.length; i++) {
            if (i > 0) output.write(delimiter);
            literalCell(output, COLUMNS[i]);
        }
        output.write("\r\n");
        literalCell(output, "report-metadata");
        for (int i = 1; i < COLUMNS.length; i++) {
            output.write(delimiter);
            if (i == COLUMNS.length - 1)
                jsonCell(
                        output,
                        new JSONObject()
                                .put("schema", "org.diemobile.scan-table")
                                .put("schemaVersion", 1)
                                .put("reportMetadata", metadata)
                                .put("nativeRowsSeparated", rows != null)
                                .put("nativeRowCount", count),
                        stop);
            else literalCell(output, "");
        }
        output.write("\r\n");
        int[] depths = new int[count];
        Arrays.fill(depths, -1);
        for (int i = 0; i < count; i++) {
            EvidenceRows.check(stop);
            Object raw = rows.get(i);
            JSONObject row = raw instanceof JSONObject ? (JSONObject) raw : null;
            int parent = row == null ? Integer.MIN_VALUE : row.optInt("parent", Integer.MIN_VALUE);
            boolean parentKnown = parent == -1 || parent >= 0 && parent < i;
            if (parent == -1) depths[i] = 0;
            else if (parentKnown && depths[parent] >= 0) depths[i] = depths[parent] + 1;
            literalCell(output, "native-row");
            output.write(delimiter);
            literalCell(output, Integer.toString(i));
            output.write(delimiter);
            literalCell(output, parentKnown ? Integer.toString(parent) : "");
            output.write(delimiter);
            literalCell(output, depths[i] < 0 ? "" : Integer.toString(depths[i]));
            for (String field : new String[] {"category", "name", "signature", "text"}) {
                output.write(delimiter);
                jsonCell(output, row == null ? JSONObject.NULL : row.opt(field), stop);
            }
            output.write(delimiter);
            jsonCell(output, raw, stop);
            output.write("\r\n");
        }
    }

    private static void literalCell(Writer output, String value) throws IOException {
        output.write('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') output.write('"');
            output.write(c);
        }
        output.write('"');
    }

    private static void jsonCell(Writer output, Object value, BooleanSupplier stop)
            throws Exception {
        output.write('"');
        Writer escaped =
                new Transform(output, stop) {
                    @Override
                    boolean changes(char value) {
                        return value == '"';
                    }

                    @Override
                    void character(char c) throws IOException {
                        if (c == '"') output.write('"');
                        output.write(c);
                    }
                };
        EvidenceJson.write(new Ascii(escaped, stop), value, stop);
        output.write('"');
    }

    /**
     * Character transforms never close/flush caller-owned streams or materialize complete cells.
     */
    private abstract static class Transform extends Writer {
        final Writer output;
        final BooleanSupplier stop;

        Transform(Writer output, BooleanSupplier stop) {
            this.output = output;
            this.stop = stop;
        }

        abstract void character(char value) throws IOException;

        abstract boolean changes(char value);

        @Override
        public void write(int value) throws IOException {
            EvidenceRows.check(stop);
            if (changes((char) value)) character((char) value);
            else output.write(value);
        }

        @Override
        public void write(char[] chars, int offset, int count) throws IOException {
            int start = offset;
            for (int i = 0; i < count; i++) {
                if ((i & 4095) == 0) EvidenceRows.check(stop);
                if (changes(chars[offset + i])) {
                    output.write(chars, start, offset + i - start);
                    character(chars[offset + i]);
                    start = offset + i + 1;
                }
            }
            output.write(chars, start, offset + count - start);
        }

        @Override
        public void write(String text, int offset, int count) throws IOException {
            int start = offset;
            for (int i = 0; i < count; i++) {
                if ((i & 4095) == 0) EvidenceRows.check(stop);
                if (changes(text.charAt(offset + i))) {
                    output.write(text, start, offset + i - start);
                    character(text.charAt(offset + i));
                    start = offset + i + 1;
                }
            }
            output.write(text, start, offset + count - start);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    /**
     * ASCII JSON literals can represent XML-forbidden NUL/noncharacters and isolated surrogates.
     */
    private static final class Ascii extends Transform {
        private static final char[] HEX = "0123456789ABCDEF".toCharArray();
        private final char[] escaped = {'\\', 'u', '0', '0', '0', '0'};

        Ascii(Writer output, BooleanSupplier stop) {
            super(output, stop);
        }

        @Override
        boolean changes(char value) {
            return value >= 127;
        }

        @Override
        void character(char value) throws IOException {
            if (value < 127) output.write(value);
            else {
                escaped[2] = HEX[(value >>> 12) & 15];
                escaped[3] = HEX[(value >>> 8) & 15];
                escaped[4] = HEX[(value >>> 4) & 15];
                escaped[5] = HEX[value & 15];
                output.write(escaped);
            }
        }
    }

    private static final class Xml {
        final Writer output, text;
        final BooleanSupplier stop;
        int visited;

        Xml(Writer output, BooleanSupplier stop) {
            this.output = output;
            this.stop = stop;
            text =
                    new Transform(output, stop) {
                        @Override
                        boolean changes(char value) {
                            return value == '&' || value == '<' || value == '>';
                        }

                        @Override
                        void character(char value) throws IOException {
                            if (value == '&') output.write("&amp;");
                            else if (value == '<') output.write("&lt;");
                            else if (value == '>') output.write("&gt;");
                            else output.write(value);
                        }
                    };
        }

        void value(Object value, int depth) throws Exception {
            EvidenceRows.check(stop);
            if (depth > 256 || ++visited > 2_000_000)
                throw new IOException("XML report exceeds its depth/node budget");
            if (value == null || value == JSONObject.NULL) output.write("<null/>");
            else if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                output.write("<object>");
                for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    output.write("<member><key encoding=\"json-string\">");
                    EvidenceJson.write(new Ascii(text, stop), key, stop);
                    output.write("</key>");
                    value(object.get(key), depth + 1);
                    output.write("</member>");
                }
                output.write("</object>");
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                output.write("<array>");
                for (int i = 0; i < array.length(); i++) {
                    output.write("<item index=\"");
                    output.write(Integer.toString(i));
                    output.write("\">");
                    value(array.get(i), depth + 1);
                    output.write("</item>");
                }
                output.write("</array>");
            } else if (value instanceof String) {
                output.write("<string encoding=\"json-string\">");
                EvidenceJson.write(new Ascii(text, stop), value, stop);
                output.write("</string>");
            } else if (value instanceof Boolean) {
                output.write("<boolean>");
                output.write(value.toString());
                output.write("</boolean>");
            } else if (value instanceof Number || value instanceof EvidenceJson.RawNumber) {
                output.write("<number>");
                EvidenceJson.write(output, value, stop);
                output.write("</number>");
            } else throw new IOException("Unsupported XML report value class");
        }
    }
}
