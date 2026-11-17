// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.*;

/** Typed, immutable view of the actual desktop result tree; retains the native report. */
final class ScanEvidence {
    static final class Row {
        final int index, parent, depth;
        final String text, category, name, signature;

        Row(int index, int parent, int depth, JSONObject json) throws JSONException {
            this.index = index; this.parent = parent; this.depth = depth;
            text = json.getString("text"); category = json.getString("category");
            name = json.optString("name", ""); signature = json.optString("signature", "");
        }
    }

    final boolean ok;
    final String error, type, architecture, endianness, mode, format, log;
    final int signatureCount;
    final List<Row> rows;
    private final String original;

    private ScanEvidence(String original, JSONObject json) throws JSONException {
        this.original = original;
        ok = json.getBoolean("ok"); error = json.optString("error", "");
        type = json.optString("type", ""); architecture = json.optString("architecture", "");
        endianness = json.optString("endianness", ""); mode = json.optString("mode", "");
        format = json.optString("format", ""); log = json.optString("log", "");
        signatureCount = json.optInt("signatureCount", 0);
        List<Row> records = new ArrayList<>();
        if (ok) {
            JSONArray array = json.getJSONArray("rows");
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                int parent = row.getInt("parent");
                if (parent < -1 || parent >= i) throw new JSONException("Invalid result parent at row " + i);
                records.add(new Row(i, parent, parent == -1 ? 0 : records.get(parent).depth + 1, row));
            }
        }
        rows = Collections.unmodifiableList(records);
    }

    static ScanEvidence parse(String json) throws JSONException { return new ScanEvidence(json, new JSONObject(json)); }
    JSONObject toJson() throws JSONException { return new JSONObject(original); }

    List<Row> page(String filter, int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 500) throw new IllegalArgumentException("Invalid evidence page");
        String query = filter.toLowerCase(java.util.Locale.ROOT);
        long skip = (long)page * pageSize;
        List<Row> found = new ArrayList<>();
        for (Row row : rows) {
            if (!(row.text + "\n" + row.category + "\n" + row.signature).toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
            if (skip > 0) { skip--; continue; }
            found.add(row); if (found.size() == pageSize) break;
        }
        return Collections.unmodifiableList(found);
    }
}
