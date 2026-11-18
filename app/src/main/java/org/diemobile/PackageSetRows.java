// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded interactive previews with complete captured records for search and streamed export. */
final class PackageSetRows extends EvidenceRows {
    static final int MAX_ROWS = 100000;

    static final class Record {
        final int source, index;
        final String table, name;
        final JSONObject value;

        Record(int source, String table, int index, JSONObject value) {
            this.source = source;
            this.table = table;
            this.index = index;
            this.value = value;
            name = value.optString("name", value.optString("key", table + " " + index));
        }
    }

    final List<Record> records;
    private final JSONObject metadata;

    PackageSetRows(String kind, String title, List<Record> records, JSONObject metadata)
            throws IOException {
        super("package-set-" + kind, title);
        if (records.size() > MAX_ROWS)
            throw new IOException(
                    "Selected package-set scope exceeds 100,000 records; choose an individual"
                        + " source/table");
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
        this.metadata = metadata;
    }

    int size() {
        return records.size();
    }

    Record record(int index) throws IOException {
        if (index < 0 || index >= records.size())
            throw new IOException("Package record index outside captured scope");
        return records.get(index);
    }

    Row row(int index, BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        Record record = record(index);
        String name =
                record.name.length() > 4096
                        ? record.name.substring(0, 4096) + " [name preview]"
                        : record.name;
        JSONObject fields =
                wrapper(index, record)
                        .put("name", name)
                        .put("record", preview(record.value, 0, new int[] {0}, stop))
                        .put("interactivePreview", true)
                        .put("completeRecordInJsonExport", true);
        try {
            EvidenceJson.write(new PreviewCounter(), fields, stop);
        } catch (PreviewLimit large) {
            fields.put(
                    "record",
                    "[record exceeds the 96 Ki-character escaped preview budget; complete captured"
                        + " record retained in JSON export]");
        }
        return new Row(
                index,
                name,
                record.table
                        + " • "
                        + (record.source < 0 ? "captured set" : "APK source " + record.source)
                        + " • original record "
                        + record.index,
                null,
                null,
                fields);
    }

    private static final class PreviewLimit extends IOException {}

    private static final class PreviewCounter extends Writer {
        private int characters;

        public void write(char[] value, int offset, int count) throws IOException {
            if (count > 96 * 1024 - characters) throw new PreviewLimit();
            characters += count;
        }

        public void flush() {}

        public void close() {}
    }

    JSONObject metadata() {
        return metadata;
    }

    @Override
    boolean matches(Row row, String query, BooleanSupplier stop) throws Exception {
        Record record = record(row.index);
        return record.name.toLowerCase(Locale.ROOT).contains(query)
                || EvidenceRows.containsDecoded(record.value, query, 0, new int[] {0}, stop);
    }

    @Override
    void writeSelectedRow(Writer out, Row row, BooleanSupplier stop) throws Exception {
        writeRow(out, row.index, stop);
    }

    @Override
    void writeRow(Writer out, int index, BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        Record record = record(index);
        EvidenceJson.write(
                out,
                wrapper(index, record).put("record", record.value).put("interactivePreview", false),
                stop);
    }

    private static JSONObject wrapper(int index, Record record) throws Exception {
        return new JSONObject()
                .put("index", index)
                .put("name", record.name)
                .put("sourceIndex", record.source < 0 ? JSONObject.NULL : record.source)
                .put("recordTable", record.table)
                .put("originalRecordIndex", record.index)
                .put("physicalOffset", JSONObject.NULL)
                .put("sizeBytes", JSONObject.NULL)
                .put(
                        "coordinateDomain",
                        "Aggregate declarations/relationships. Nested manifest coordinates belong"
                            + " to the separately identified uncompressed AndroidManifest.xml; no"
                            + " APK byte offset is inferred.");
    }

    private static Object preview(Object value, int depth, int[] nodes, BooleanSupplier stop)
            throws Exception {
        EvidenceRows.check(stop);
        if (++nodes[0] > 1024 || depth > 16)
            return "[interactive preview limit; full captured value in JSON export]";
        if (value instanceof String) {
            String text = (String) value;
            return text.length() > 4096
                    ? text.substring(0, 4096) + " [full value in JSON export]"
                    : text;
        }
        if (value instanceof JSONObject) {
            JSONObject result = new JSONObject(), object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.put(key, preview(object.get(key), depth + 1, nodes, stop));
                if (nodes[0] > 1024) break;
            }
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray result = new JSONArray(), array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                result.put(preview(array.get(i), depth + 1, nodes, stop));
                if (nodes[0] > 1024) break;
            }
            return result;
        }
        return value;
    }
}
