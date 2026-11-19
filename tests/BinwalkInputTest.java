// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Actual Binwalk descriptor-position regression, complete maximum-size scan and IO contract. */
public final class BinwalkInputTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static JSONObject png(JSONObject report, long offset) throws Exception {
        JSONArray rows = report.getJSONArray("rows");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row.getString("name").equals("png")
                    && row.getLong("physicalOffset") == offset
                    && row.getBoolean("parserAccepted")) return row;
        }
        throw new AssertionError("Missing PNG at " + offset);
    }

    private static JSONObject stableEvidence(JSONObject original) throws Exception {
        JSONObject result = new JSONObject(original.toString());
        JSONArray catalog = result.getJSONArray("patterns");
        // Upstream's encrypted-firmware patterns come from a HashMap. Indices
        // identify this report's own catalog, not a stable global numbering.
        JSONArray rows = result.getJSONArray("rows");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            JSONObject pattern =
                    new JSONObject(catalog.getJSONObject(row.getInt("patternIndex")).toString());
            pattern.remove("index");
            row.remove("patternIndex");
            row.put("pattern", pattern);
        }
        List<JSONObject> ordered = new ArrayList<>();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject pattern = catalog.getJSONObject(i);
            pattern.remove("index");
            ordered.add(pattern);
        }
        ordered.sort(
                Comparator.comparing((JSONObject value) -> value.optString("name"))
                        .thenComparing(value -> value.optString("magicHex")));
        result.put("patterns", new JSONArray(ordered));
        return result;
    }

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        File input = directory.resolve("binwalk-positional.bin").toFile();
        byte[] image = MetadataFixtureFactory.png();
        Files.write(input.toPath(), image);
        JSONObject report = new JSONObject(BinwalkEngine.scanPath(input.toString()));
        require(report.optBoolean("ok"), report.toString());
        require(report.getString("adapter").equals("diemobile-binwalk-2"), "Wrong adapter");
        require(report.getLong("sourceReadBytes") == image.length, "Input read coverage differs");
        try (RandomAccessFile source = new RandomAccessFile(input, "r")) {
            source.seek(17);
            Field descriptor = FileDescriptor.class.getDeclaredField("fd");
            descriptor.setAccessible(true);
            JSONObject direct =
                    new JSONObject(BinwalkEngine.scanFd(descriptor.getInt(source.getFD())));
            require(
                    stableEvidence(report).similar(stableEvidence(direct)),
                    "Path/descriptor evidence differs");
            require(source.getFilePointer() == 17, "Caller descriptor position moved");
            require(
                    source.getFD().valid() && source.read() == (image[17] & 255),
                    "Caller descriptor closed/changed");
        }
        try (FileOutputStream output = new FileOutputStream(input, true)) {
            Field descriptor = FileDescriptor.class.getDeclaredField("fd");
            descriptor.setAccessible(true);
            JSONObject refused =
                    new JSONObject(BinwalkEngine.scanFd(descriptor.getInt(output.getFD())));
            require(!refused.getBoolean("ok"), "Write-only source accepted");
            require(output.getFD().valid(), "Refusal closed the caller descriptor");
        }
        require(
                !new JSONObject(BinwalkEngine.scanPath(directory.toString())).getBoolean("ok"),
                "Directory accepted as data");
        File snapshots = directory.resolve("binwalk-io-snapshots").toFile();
        BinaryRunner runner = (source, stop) -> BinwalkEngine.scanPath(source.toString());
        try (BinwalkBackend backend = new BinwalkBackend(input, snapshots, "png", runner)) {
            require(backend.open("accepted", () -> false).size() > 0, "New IO report rejected");
        }
        for (String field :
                new String[] {
                    "adapter",
                    "sourceReadBytes",
                    "inputReadBufferBytes",
                    "callerDescriptorPositionPreserved",
                    "parserCallbacksPreemptible"
                }) {
            JSONObject bad = new JSONObject(report.toString());
            if (field.equals("adapter")) bad.put(field, "unknown");
            else if (field.equals("callerDescriptorPositionPreserved")) bad.put(field, false);
            else if (field.equals("parserCallbacksPreemptible")) bad.put(field, true);
            else bad.put(field, image.length + 1);
            try (BinwalkBackend backend =
                    new BinwalkBackend(
                            input, snapshots, "corrupt", (source, stop) -> bad.toString())) {
                try {
                    backend.open("accepted", () -> false);
                    throw new AssertionError("Corrupted " + field + " accepted");
                } catch (IOException expected) {
                    require(
                            expected.getMessage().contains("accounting differs"),
                            expected.toString());
                }
            }
            require(snapshots.list().length == 0, "Failed report leaked its private source");
        }
        JSONObject legacy = new JSONObject(report.toString());
        legacy.remove("adapter");
        try (BinwalkBackend backend =
                new BinwalkBackend(
                        input, snapshots, "legacy", (source, stop) -> legacy.toString())) {
            require(
                    backend.open("accepted", () -> false).size() > 0,
                    "Original adapter capture rejected");
        }
        long[] positions = {65536 - 5L, BinwalkBackend.MAX_BYTES - image.length};
        try (RandomAccessFile large = new RandomAccessFile(input, "rw")) {
            large.setLength(BinwalkBackend.MAX_BYTES);
            for (long position : positions) {
                large.seek(position);
                large.write(image);
            }
        }
        JSONObject maximum = new JSONObject(BinwalkEngine.scanPath(input.toString()));
        require(maximum.optBoolean("ok"), maximum.optString("error"));
        require(
                maximum.getLong("sourceReadBytes") == BinwalkBackend.MAX_BYTES
                        && maximum.getLong("sourceReadOperations") == 1024,
                "Maximum input read was incomplete");
        for (long position : positions)
            require(
                    png(maximum, position).getLong("reportedSizeBytes") == image.length,
                    "Read-seam/EOF extent differs");
        Files.delete(input.toPath());
        System.out.println(
                "Binwalk positional JNI passed: unchanged caller offset, path/FD equivalence,"
                        + " unreadable sources, accounting rejection/cleanup, legacy captures and"
                        + " complete 64 MiB scan with read-seam/EOF extents.");
    }
}
