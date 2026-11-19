// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

public final class NativeTableTest {
    public static void main(String[] args) throws Exception {
        JSONArray records = new JSONArray();
        for (int i = 0; i < 123; i++) records.put(row(i, "name_" + i, i * 8L, "0xFFFF800000001000", "256"));
        NativeTable table = NativeTable.parse(envelope("memory-map", 4096, records).toString());
        require(table.page("", 0, 50).rows.size() == 50 && table.page("", 1, 50).rows.get(0).index == 50, "Page gap/order");
        require(table.page("", 2, 50).rows.size() == 23 && table.page("", 200, 50).number == 2, "Tail page");
        require(table.page("NAME_122", 0, 50).rows.get(0).index == 122, "Case-insensitive name search");
        require(table.page("0x3D0", 0, 50).rows.get(0).index == 122, "Physical hex search");
        JSONObject mutable = table.toJson(); mutable.getJSONArray("rows").getJSONObject(0).put("name", "changed");
        require(table.rows.get(0).name.equals("name_0"), "Snapshot mutated externally");
        records = new JSONArray().put(row(0, "first", 16L, "0xFFFF800000001000", "256"))
            .put(row(1, "overlap", 512L, "0xFFFF800000001000", "256"))
            .put(row(2, "virtual", null, "0xFFFF800000001100", "256").put("isVirtual", true));
        table = NativeTable.parse(envelope("memory-map", 4096, records).toString());
        List<NativeTable.Mapping> overlap = table.mapVirtualAddress("0xffff800000001005");
        require(overlap.size() == 2 && overlap.get(0).physicalOffset == 21 && overlap.get(1).physicalOffset == 517, "Overlapping mappings were lost or truncated to signed VA");
        List<NativeTable.Mapping> virtual = table.mapVirtualAddress("0xFFFF800000001101");
        require(virtual.size() == 1 && virtual.get(0).physicalOffset == null && !virtual.get(0).limitation.isEmpty(), "Virtual bytes invented");
        require(table.mapVirtualAddress("0xFFFF800000001200").isEmpty(), "Half-open VA boundary");
        require(!table.rows.get(2).hasFileByte(table.fileSize) && table.rows.get(2).contentRange(table.fileSize) == null, "Virtual actions enabled");
        NativeTable.Row overflow = NativeTable.parse(envelope("symbols", 4096, new JSONArray()
            .put(row(0, "oversized", 4090L, "0x1000", "18446744073709551615").put("value", "0xFFFFFFFFFFFFFFFF"))).toString()).rows.get(0);
        require(overflow.contentRange(4096) == null && overflow.toJson().getString("value").equals("0xFFFFFFFFFFFFFFFF"), "Unsigned symbol value/size lost");
        require(overflow.hasFileByte(4096), "Known byte address discarded because target size is unavailable");
        malformed(envelope("memory-map", 4096, records).put("rowCount", 4));
        malformed(envelope("memory-map", 4096, records).put("schemaVersion", 2));
        malformed(envelope("memory-map", 4096, new JSONArray().put(row(1, "bad index", 0L, null, "0"))));
        malformed(envelope("memory-map", 4096, new JSONArray().put(row(0, "overflow VA", 0L, "0x10000000000000000", "1"))));
        malformed(envelope("memory-map", 4096, new JSONArray().put(row(0, "fraction", 0L, null, "1").put("physicalOffset", 0.5))));
        require(!NativeTable.parse("{\"schema\":\"org.diemobile.native-records\",\"schemaVersion\":1,\"kind\":\"resources\",\"ok\":false,\"error\":\"Unsupported\"}").ok, "Unsupported table became an empty success");
        ranges();
        System.out.println("Native table models: paging/search, unsigned/overlap/virtual mappings, strict schema and bounded range I/O passed.");
    }

    private static void ranges() throws Exception {
        try { new FileRange(Long.MAX_VALUE, Long.MAX_VALUE - 1, 2); throw new AssertionError("Range overflow accepted"); }
        catch (IllegalArgumentException expected) {}
        Path path = Files.createTempFile("die-range", ".bin");
        try {
            Files.writeString(path, "PREFIXabcSUFFIX");
            byte[] before = Files.readAllBytes(path);
            FileRange range = new FileRange(before.length, 6, 3);
            RangeAnalysis.Result result = RangeAnalysis.inspect(path.toFile(), range, () -> false);
            require(result.sha256.equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") && result.crc32.equals("352441c2"), "Range digest included unrelated bytes");
            require(Math.abs(result.entropy - Math.log(3) / Math.log(2)) < 1e-12, "Range entropy");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); RangeAnalysis.copy(path.toFile(), range, bytes, () -> false);
            require(bytes.toString("UTF-8").equals("abc") && Arrays.equals(before, Files.readAllBytes(path)), "Extraction changed bytes/input");
            Files.writeString(path, "changed length");
            try { RangeAnalysis.inspect(path.toFile(), range, () -> false); throw new AssertionError("Stale input length accepted"); }
            catch (IOException expected) {}
            Files.write(path, new byte[128 * 1024]);
            AtomicInteger checks = new AtomicInteger();
            try { RangeAnalysis.inspect(path.toFile(), new FileRange(128*1024, 0, 128*1024), () -> checks.incrementAndGet() > 2); throw new AssertionError("Range cancellation ignored"); }
            catch (InterruptedIOException expected) {}
            Files.write(path, new byte[0]);
            result = RangeAnalysis.inspect(path.toFile(), new FileRange(0, 0, 0), () -> false);
            require(result.sha256.equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") && result.entropy == 0, "Empty range");
            try { new FileRange(FileRange.MAX_OPERATION_BYTES+1, 0, FileRange.MAX_OPERATION_BYTES+1).requireOperationBudget(); throw new AssertionError("Budget ignored"); }
            catch (IOException expected) {}
        } finally { Files.delete(path); }
    }

    static JSONObject row(int index, String name, Long offset, String address, String size) throws JSONException {
        return new JSONObject().put("index", index).put("name", name).put("physicalOffset", offset == null ? JSONObject.NULL : offset)
            .put("virtualAddress", address == null ? JSONObject.NULL : address).put("sizeBytes", size == null ? JSONObject.NULL : size);
    }
    static JSONObject envelope(String kind, long size, JSONArray rows) throws JSONException {
        return new JSONObject().put("schema", NativeTable.SCHEMA).put("schemaVersion", 1).put("ok", true).put("kind", kind)
            .put("scope", kind.equals("disassembly") ? "instruction-page" : "complete-snapshot").put("fileType", "fixture")
            .put("fileSizeBytes", size).put("rowCount", rows.length()).put("metadata", new JSONObject()).put("rows", rows);
    }
    private static void malformed(JSONObject value) throws Exception {
        try { NativeTable.parse(value.toString()); throw new AssertionError("Malformed table accepted"); } catch (JSONException expected) {}
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
