// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.math.BigInteger;
import java.util.*;
import org.json.*;

/** Strict typed view of a complete native table or a declared instruction page. */
final class NativeTable {
    static final String SCHEMA = "org.diemobile.native-records";
    private static final BigInteger U64_LIMIT = BigInteger.ONE.shiftLeft(64);
    private static final Set<String> KINDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "structures", "memory-map", "imports", "exports", "symbols", "resources", "disassembly")));

    static final class Row {
        final int index;
        final String name;
        final Long physicalOffset, recordOffset;
        final BigInteger virtualAddress, sizeBytes;
        final boolean isVirtual;
        private final JSONObject json;
        private final String searchable;

        Row(int index, JSONObject json) throws JSONException {
            if (nonnegativeLong(json.get("index")) != index) throw new JSONException("Native row index/order mismatch");
            this.index = index; this.json = json;
            name = json.getString("name");
            for (String key : new String[]{"physicalOffset", "virtualAddress", "sizeBytes"}) {
                if (!json.has(key)) throw new JSONException("Native row is missing " + key);
            }
            physicalOffset = optionalOffset(json, "physicalOffset");
            recordOffset = optionalOffset(json, "recordOffset");
            virtualAddress = optionalUnsigned(json, "virtualAddress", true);
            sizeBytes = optionalUnsigned(json, "sizeBytes", false);
            isVirtual = json.optBoolean("isVirtual", false);
            searchable = (json.toString() + " " + hexOffset(physicalOffset) + " " + hexOffset(recordOffset)
                + " " + (virtualAddress == null ? "" : "0x" + virtualAddress.toString(16))).toLowerCase(Locale.ROOT);
        }

        JSONObject toJson() throws JSONException { return new JSONObject(json.toString()); }
        String text(String key) { return json.optString(key, ""); }
        boolean flag(String key) { return json.optBoolean(key, false); }
        int code(String key) { return json.optInt(key, 0); }
        boolean matches(String query) { return searchable.contains(query); }

        boolean hasFileByte(long fileSize) {
            return !isVirtual && physicalOffset != null && physicalOffset < fileSize;
        }

        FileRange contentRange(long fileSize) {
            if (!hasFileByte(fileSize) || sizeBytes == null || sizeBytes.signum() <= 0 || sizeBytes.bitLength() > 63) return null;
            try { return new FileRange(fileSize, physicalOffset, sizeBytes.longValue()); }
            catch (IllegalArgumentException e) { return null; }
        }
    }

    static final class Page {
        final int number, pageCount, totalMatches;
        final List<Row> rows;
        Page(int number, int pageCount, int totalMatches, List<Row> rows) {
            this.number = number; this.pageCount = pageCount; this.totalMatches = totalMatches;
            this.rows = Collections.unmodifiableList(rows);
        }
    }

    static final class Mapping {
        final Row record;
        final Long physicalOffset;
        final String limitation;
        Mapping(Row record, Long physicalOffset, String limitation) {
            this.record = record; this.physicalOffset = physicalOffset; this.limitation = limitation;
        }
    }

    final boolean ok;
    final String kind, fileType, scope, error;
    final long fileSize;
    final List<Row> rows;
    private final String original, metadata;

    private NativeTable(String original, JSONObject json) throws JSONException {
        if (!SCHEMA.equals(json.getString("schema")) || json.getInt("schemaVersion") != 1) throw new JSONException("Unsupported native table schema");
        this.original = original;
        ok = json.getBoolean("ok"); kind = json.getString("kind"); error = json.optString("error", "");
        fileType = json.optString("fileType", ""); scope = json.optString("scope", "");
        if (ok && !KINDS.contains(kind)) throw new JSONException("Unsupported native table kind");
        if (ok && !(kind.equals("disassembly") ? scope.equals("instruction-page") : scope.equals("complete-snapshot"))) {
            throw new JSONException("Native table scope does not match its kind");
        }
        fileSize = ok ? nonnegativeLong(json.get("fileSizeBytes")) : 0;
        metadata = ok ? json.getJSONObject("metadata").toString() : "{}";
        List<Row> parsed = new ArrayList<>();
        if (ok) {
            JSONArray array = json.getJSONArray("rows");
            long count = nonnegativeLong(json.get("rowCount"));
            if (count != array.length() || count > 10000) throw new JSONException("Native table count/limit mismatch");
            if (kind.equals("structures")) NativeStructures.validate(array, fileSize, json.getJSONObject("metadata"));
            for (int i = 0; i < array.length(); i++) parsed.add(new Row(i, array.getJSONObject(i)));
        }
        rows = Collections.unmodifiableList(parsed);
    }

    static NativeTable parse(String response) throws JSONException { return new NativeTable(response, new JSONObject(response)); }
    JSONObject toJson() throws JSONException { return new JSONObject(original); }
    String encoded() { return original; }
    JSONObject metadata() throws JSONException { return new JSONObject(metadata); }

    Page page(String filter, int requestedPage, int pageSize) {
        if (requestedPage < 0 || pageSize < 1 || pageSize > 200) throw new IllegalArgumentException("Invalid record page");
        String query = filter.toLowerCase(Locale.ROOT);
        int matches = 0; for (Row row : rows) if (row.matches(query)) matches++;
        int pages = Math.max(1, (matches + pageSize - 1) / pageSize);
        int page = Math.min(requestedPage, pages - 1), skip = page * pageSize;
        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            if (!row.matches(query)) continue;
            if (skip > 0) { skip--; continue; }
            result.add(row); if (result.size() == pageSize) break;
        }
        return new Page(page, pages, matches, result);
    }

    /** Return every covering map record, including virtual-only and overlapping ones. */
    List<Mapping> mapVirtualAddress(String value) throws JSONException {
        if (!ok || !kind.equals("memory-map")) throw new IllegalStateException("VA translation requires a memory-map snapshot");
        BigInteger target = unsigned(value, true);
        List<Mapping> matches = new ArrayList<>();
        for (Row row : rows) {
            if (row.virtualAddress == null || row.sizeBytes == null || row.sizeBytes.signum() <= 0) continue;
            BigInteger end = row.virtualAddress.add(row.sizeBytes);
            if (end.compareTo(U64_LIMIT) > 0 || target.compareTo(row.virtualAddress) < 0 || target.compareTo(end) >= 0) continue;
            Long offset = null; String limitation = "";
            if (row.isVirtual || row.physicalOffset == null) limitation = "Virtual-only or unmapped record: no file bytes";
            else {
                BigInteger physical = BigInteger.valueOf(row.physicalOffset).add(target.subtract(row.virtualAddress));
                if (physical.signum() < 0 || physical.compareTo(BigInteger.valueOf(fileSize)) >= 0) limitation = "Mapped address is outside the file";
                else offset = physical.longValue();
            }
            matches.add(new Mapping(row, offset, limitation));
        }
        return Collections.unmodifiableList(matches);
    }

    static String hexOffset(Long value) { return value == null ? "unmapped" : "0x" + Long.toHexString(value).toUpperCase(Locale.ROOT); }

    private static Long optionalOffset(JSONObject json, String key) throws JSONException {
        return !json.has(key) || json.isNull(key) ? null : nonnegativeLong(json.get(key));
    }
    private static BigInteger optionalUnsigned(JSONObject json, String key, boolean hex) throws JSONException {
        return !json.has(key) || json.isNull(key) ? null : unsigned(json.get(key), hex);
    }
    private static long nonnegativeLong(Object value) throws JSONException {
        if (!(value instanceof Number) || !value.toString().matches("[0-9]+")) throw new JSONException("Expected nonnegative integer");
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException e) { throw new JSONException("Physical integer exceeds signed 64-bit range"); }
    }
    private static BigInteger unsigned(Object value, boolean hex) throws JSONException {
        if (!(value instanceof String)) throw new JSONException("Expected exact unsigned integer string");
        String text = (String)value;
        if (!text.matches(hex ? "0[xX][0-9a-fA-F]{1,16}" : "[0-9]{1,20}")) throw new JSONException("Invalid unsigned integer encoding");
        BigInteger parsed = new BigInteger(hex ? text.substring(2) : text, hex ? 16 : 10);
        if (parsed.signum() < 0 || parsed.compareTo(U64_LIMIT) >= 0) throw new JSONException("Unsigned integer exceeds 64 bits");
        return parsed;
    }
}
