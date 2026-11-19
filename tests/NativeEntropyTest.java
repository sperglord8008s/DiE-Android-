// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Complete actual-JNI entropy scopes, independently checked against source bytes. */
public final class NativeEntropyTest {
    private static NativeTable read(Path path) throws Exception {
        NativeTable table = NativeTable.parse(Engine.records(path.toString(), 10, 0, -1, false));
        require(table.ok, "Entropy failed: " + table.error);
        return table;
    }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]), output = Path.of(args[1]);
        Files.createDirectories(output);
        String[] names = {
            "entropy-0.bin",
            "entropy-1.bin",
            "entropy-511.bin",
            "entropy-512.bin",
            "entropy-513.bin",
            "entropy-1023.bin",
            "entropy-1024.bin",
            "entropy-51200.bin",
            "entropy-51203.bin",
            "entropy-65537.bin",
            "entropy-uniform.bin",
            "entropy-zero.bin",
            "pe32-rich.exe",
            "pe64-rich.exe",
            "mach64-high.bin",
            "mach64-zerofill.bin",
            "mach-fat.bin",
            "truncated-elf.bin"
        };
        for (String name : names) {
            NativeTable table = read(fixtures.resolve(name));
            require(
                    table.kind.equals("entropy") && table.scope.equals("complete-snapshot"),
                    "Wrong entropy scope");
            JSONObject legacy =
                    new JSONObject(
                            Engine.tool(
                                    fixtures.resolve(name).toString(),
                                    10,
                                    -1,
                                    0,
                                    4,
                                    "",
                                    true,
                                    false));
            require(
                    Double.doubleToLongBits(legacy.getDouble("total"))
                            == Double.doubleToLongBits(
                                    table.rows.get(0).toJson().getDouble("entropyBitsPerByte")),
                    "Typed total differs from upstream legacy report");
            JSONArray legacyRegions = legacy.getJSONArray("records");
            require(
                    legacyRegions.length() == table.metadata().getInt("regionCount"),
                    "Typed region inventory differs from upstream legacy report");
            for (int region = 0; region < legacyRegions.length(); region++) {
                JSONObject old = legacyRegions.getJSONObject(region),
                        current = table.rows.get(region + 1).toJson();
                require(
                        old.getString("name").equals(current.getString("name"))
                                && old.getLong("offset") == current.getLong("physicalOffset")
                                && old.getLong("size")
                                        == Long.parseLong(current.getString("sizeBytes"))
                                && old.getDouble("entropy")
                                        == current.getDouble("entropyBitsPerByte")
                                && old.getString("status")
                                        .equals(current.getString("upstreamDisplayLabel")),
                        "Typed region differs from upstream report");
            }
            Files.writeString(output.resolve(name + ".json"), table.encoded());
            require(
                    table.page("byte-frequency", 5, 50).rows.size() == 6,
                    "Histogram bins silently dropped");
            NativeTable.Row last = table.page("\"byteValue\":255", 0, 50).rows.get(0);
            require(
                    last.name.equals("Byte 0xFF")
                            && last.physicalOffset == null
                            && last.virtualAddress == null
                            && last.sizeBytes == null
                            && !last.hasFileByte(table.fileSize)
                            && last.contentRange(table.fileSize) == null,
                    "Histogram invented a byte location");
            if (name.equals("entropy-0.bin"))
                require(
                        table.metadata().getInt("sampleCount") == 0
                                && table.rows.get(0).toJson().getDouble("entropyBitsPerByte") == 0,
                        "Empty input semantics");
            if (name.equals("entropy-uniform.bin")) malformedContracts(table);
        }
        Path path = fixtures.resolve("entropy-uniform.bin");
        AtomicInteger calls = new AtomicInteger();
        NativeTable cancelled =
                NativeTable.parse(
                        Engine.entropy(path.toString(), () -> calls.incrementAndGet() > 20));
        require(
                !cancelled.ok
                        && cancelled.rows.isEmpty()
                        && cancelled.error.toLowerCase().contains("cancel"),
                "Active entropy cancellation returned partial success");
        require(read(path).ok, "Cancellation poisoned the next request");
        AtomicInteger throwingCalls = new AtomicInteger();
        try {
            Engine.entropy(
                    path.toString(),
                    () -> {
                        if (throwingCalls.incrementAndGet() > 10)
                            throw new IllegalStateException("fixture callback");
                        return false;
                    });
            throw new AssertionError("Native callback exception was swallowed");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().equals("fixture callback"), "Wrong callback failure");
        }
        require(read(path).ok, "Callback failure poisoned the progress observer");

        require(
                !NativeTable.parse(Engine.records(path.toString(), 10, 1, -1, false)).ok,
                "Unsupported mode succeeded");
        require(
                !NativeTable.parse(Engine.entropy(fixtures.toString(), () -> false)).ok,
                "Directory accepted as entropy data");
        require(
                !NativeTable.parse(
                                Engine.entropy(fixtures.resolve("missing").toString(), () -> false))
                        .ok,
                "Missing input succeeded");
        Path large = output.resolve("oversized.bin");
        try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) {
            file.setLength(256L * 1024 * 1024 + 1);
        }
        require(
                !NativeTable.parse(Engine.entropy(large.toString(), () -> false)).ok,
                "Oversized entropy input accepted");
        Files.delete(large);
        System.out.println(
                "Typed entropy JNI passed: complete histogram/blocks/regions, empty and boundary"
                    + " lengths, raw and executable families, strict accounting/domains and active"
                    + " cancellation/recovery.");
    }

    private static void malformedContracts(NativeTable table) throws Exception {
        JSONObject original = table.toJson(), bad = new JSONObject(original.toString());
        int firstBin =
                1 + table.metadata().getInt("regionCount") + table.metadata().getInt("sampleCount");
        bad.getJSONArray("rows").getJSONObject(firstBin).put("physicalOffset", 0);
        malformed(bad);
        bad = new JSONObject(original.toString());
        bad.getJSONArray("rows").getJSONObject(firstBin).put("count", "18446744073709551615");
        malformed(bad);
        bad = new JSONObject(original.toString());
        bad.getJSONArray("rows").getJSONObject(firstBin).put("probability", 1);
        malformed(bad);
        bad = new JSONObject(original.toString());
        bad.getJSONArray("rows").getJSONObject(firstBin + 1).put("byteValue", 0);
        malformed(bad);
        bad = new JSONObject(original.toString());
        bad.getJSONArray("rows").getJSONObject(0).put("entropyIeeeBitsHex", "0000000000000000");
        malformed(bad);
        bad = new JSONObject(original.toString());
        bad.getJSONArray("rows").getJSONObject(0).put("upstreamDisplayLabel", "not packed");
        malformed(bad);
        bad = new JSONObject(original.toString());
        bad.getJSONArray("rows")
                .getJSONObject(1 + table.metadata().getInt("regionCount"))
                .put("physicalOffset", 1);
        malformed(bad);
        bad = new JSONObject(original.toString());
        bad.getJSONObject("metadata").put("regionCount", 0);
        malformed(bad);
        JSONObject extended = new JSONObject(original.toString());
        extended.getJSONArray("rows")
                .getJSONObject(0)
                .put("futureDetails", new JSONObject().put("exact", "\udfff"));
        require(
                NativeTable.parse(extended.toString())
                        .rows
                        .get(0)
                        .toJson()
                        .getJSONObject("futureDetails")
                        .getString("exact")
                        .equals("\udfff"),
                "Unknown entropy metadata lost");
    }

    private static void malformed(JSONObject value) throws Exception {
        try {
            NativeTable.parse(value.toString());
            throw new AssertionError("Malformed entropy scope accepted");
        } catch (JSONException expected) {
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
