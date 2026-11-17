// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Lazy, immutable-source evidence. Paging never requires a full JSON DOM. */
abstract class EvidenceRows {
    static final int PAGE_SIZE = 50, MAX_ROW_CHARACTERS = 4 * 1024 * 1024;
    final String kind, title;
    EvidenceRows(String kind, String title) { this.kind = kind; this.title = title; }
    abstract int size();
    abstract Row row(int index, BooleanSupplier cancelled) throws Exception;
    abstract JSONObject metadata() throws Exception;

    static final class Row {
        final int index;
        final String name, description, encoded;
        final Long physicalOffset, sizeBytes;
        Row(int index, String name, String description, Long physicalOffset, Long sizeBytes, JSONObject fields) throws JSONException, IOException {
            if (index < 0 || physicalOffset != null && physicalOffset < 0 || sizeBytes != null && sizeBytes < 0) throw new IOException("Invalid evidence range/index");
            this.index = index; this.name = name; this.description = description; this.physicalOffset = physicalOffset; this.sizeBytes = sizeBytes;
            this.encoded = preserveSurrogates(fields.put("index", index).put("name", name).put("physicalOffset", physicalOffset == null ? JSONObject.NULL : physicalOffset)
                .put("sizeBytes", sizeBytes == null ? JSONObject.NULL : sizeBytes).toString());
            if (encoded.length() > MAX_ROW_CHARACTERS) throw new IOException("Evidence row exceeds the 4 Mi-character budget");
        }
        JSONObject json() throws JSONException { return new JSONObject(encoded); }
    }
    // DEX MUTF-8 can represent isolated UTF-16 surrogates. Escape those exact
    // units before UTF-8 export instead of letting an encoder substitute '?'.
    private static String preserveSurrogates(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                result.append(c).append(value.charAt(++i));
            } else if (Character.isSurrogate(c)) result.append(String.format(Locale.ROOT, "\\u%04X", (int)c));
            else result.append(c);
        }
        return result.toString();
    }
    static final class Selection {
        final EvidenceRows source;
        final String filter;
        private final int[] matches; // null means all rows, with no million-entry index allocation.
        final int count;
        private Selection(EvidenceRows source, String filter, int[] matches, int count) {
            this.source = source; this.filter = filter; this.matches = matches; this.count = count;
        }
        Page page(int requested, BooleanSupplier cancelled) throws Exception {
            return page(requested, PAGE_SIZE, cancelled);
        }
        Page page(int requested, int pageSize, BooleanSupplier cancelled) throws Exception {
            if (pageSize < 1 || pageSize > PAGE_SIZE) throw new IOException("Rows per page must be 1–50");
            int pages = Math.max(1, (count + pageSize - 1) / pageSize), number = Math.max(0, Math.min(requested, pages - 1));
            List<Row> rows = new ArrayList<>();
            int characters = 0;
            for (int i = number * pageSize; i < Math.min(count, (number + 1) * pageSize); i++) {
                check(cancelled); Row row = source.row(matches == null ? i : matches[i], cancelled); characters += row.encoded.length();
                if (characters > 8 * 1024 * 1024) throw new IOException("Page exceeds 8 Mi-characters; choose fewer rows per page or narrow the filter");
                rows.add(row);
            }
            return new Page(number, pages, count, rows);
        }
    }
    static final class Page {
        final int number, pageCount, matches;
        final List<Row> rows;
        Page(int number, int pageCount, int matches, List<Row> rows) {
            this.number = number; this.pageCount = pageCount; this.matches = matches; this.rows = Collections.unmodifiableList(rows);
        }
    }
    Selection select(String filter, BooleanSupplier cancelled) throws Exception {
        String query = filter.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return new Selection(this, filter, null, size());
        int[] found = new int[Math.min(size(), 1024)]; int count = 0;
        for (int i = 0; i < size(); i++) {
            check(cancelled); Row row = row(i, cancelled);
            if (row.encoded.toLowerCase(Locale.ROOT).contains(query) || row.description.toLowerCase(Locale.ROOT).contains(query)) {
                if (count == found.length) found = Arrays.copyOf(found, Math.min(size(), Math.max(16, found.length * 2)));
                found[count++] = i;
            }
        }
        return new Selection(this, filter, Arrays.copyOf(found, count), count);
    }
    void writeReport(Writer out, JSONObject provenance, String filter, int page, BooleanSupplier cancelled) throws Exception {
        JSONObject header = new JSONObject().put("schema", "org.diemobile.evidence-table").put("schemaVersion", 1)
            .put("createdAt", java.time.Instant.now().toString()).put("kind", kind).put("title", title)
            .put("rowCount", size()).put("scope", "complete selected table")
            .put("provenance", provenance).put("metadata", metadata())
            .put("view", new JSONObject().put("filter", filter).put("page", page).put("exportScope", "all rows, independent of the UI filter/page"));
        String encoded = preserveSurrogates(header.toString()); out.write(encoded, 0, encoded.length() - 1); out.write(",\"rows\":[");
        for (int i = 0; i < size(); i++) { check(cancelled); if (i > 0) out.write(','); out.write(row(i, cancelled).encoded); }
        check(cancelled); out.write("]}");
    }
    static void check(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Investigation cancelled");
    }
}
