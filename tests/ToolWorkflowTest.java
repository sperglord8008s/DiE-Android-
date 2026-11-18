// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class ToolWorkflowTest {
    public static void main(String[] args) throws Exception {
        ToolHistory history = new ToolHistory();
        for (int i = 0; i < 65; i++) {
            boolean expired = history.push(new ToolHistory.Location(new ToolRequest(9, 0, i, 4, "", false, false), "query" + i, i));
            require(expired == (i == 64), "History expiration was silent or premature");
        }
        for (int i = 64; i >= 1; i--) {
            ToolHistory.Location restored = history.back();
            require(restored.request.offset == i && restored.filter.equals("query" + i) && restored.page == i, "Navigation lost captured state");
        }
        require(!history.hasBack(), "History budget not applied");
        ToolRequest displayed = new ToolRequest(1, 0, -1, 4, "", true, false);
        ToolRequest draft = new ToolRequest(1, 3, -1, 4, "", true, false);
        history.push(new ToolHistory.Location(displayed, draft, "field", 2));
        ToolHistory.Location both = history.back();
        require(both.request.mode == 0 && both.controls.mode == 3 && both.page == 2, "Draft controls replaced displayed evidence parameters");
        require(ToolRequest.fromJson(displayed.toJson().toString()).sameAs(displayed), "Saved request round-trip");
        ToolRequest slice = new ToolRequest(0, 0, 3, 4, "section_64#offset", false, false);
        require(slice.records() && slice.json && slice.toJson().getLong("physicalOffset") == -1 && slice.toJson().getInt("machOSliceIndex") == 3, "Slice index mislabeled as physical offset");
        require(ToolRequest.fromJson(slice.toJson().toString()).sameAs(slice), "Captured slice request lost on restore");
        JSONArray rows = new JSONArray(); for (int i = 0; i < 123; i++) rows.put(NativeTableTest.row(i, "record" + i, 0L, null, "1"));
        NativeTable table = NativeTable.parse(NativeTableTest.envelope("symbols", 100, rows).toString());
        ToolRequest captured = new ToolRequest(4, 0, -1, 7, "original-selector", false, false);
        JSONObject exported = new JSONObject(ToolReport.encode("fixture.bin", "a".repeat(64), 100, "pinned-engine", captured, table, "", 10, "no-match", 0));
        require(exported.getJSONObject("nativeRecords").getJSONArray("rows").length() == 123, "UI filtering reduced exported evidence");
        require(exported.getJSONObject("request").getInt("minimumStringLength") == 7 && exported.getJSONObject("request").getBoolean("nativeJson"), "Export request was inferred from mutable controls");
        JSONObject unknown = new JSONObject(ToolReport.encode("fixture", "bad", 100, "", captured, table, "", 0, "", 0));
        require(unknown.getJSONObject("input").isNull("sha256") && unknown.getJSONObject("input").getString("identitySource").equals("not collected"), "Unknown identity invented");
        StringBuilder allUnits = new StringBuilder();
        for (int code = 0; code <= 65535; code++) allUnits.append((char)code);
        String exact = allUnits.toString();
        String unicodeReport = ToolReport.encode(exact, null, 0, "source", captured, table, "", 0, exact, 0);
        JSONObject decodedUnicode = new JSONObject(new String(unicodeReport.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8));
        require(decodedUnicode.getJSONObject("input").getString("name").equals(exact) && decodedUnicode.getJSONObject("view").getString("filter").equals(exact), "Tool header lost UTF-16 units during UTF-8 encoding");
        Path root = Files.createTempDirectory("die-export-test");
        try {
            File directory = root.resolve("exports").toFile();
            PreparedExport report = PreparedExport.text(directory, "report.json", exported.toString());
            PreparedExport restored = PreparedExport.restore(directory, report.file.toString(), report.name, report.mime, report.size);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); restored.copyTo(output, () -> false);
            require(new JSONObject(output.toString("UTF-8")).getJSONObject("nativeRecords").getJSONArray("rows").length() == 123, "Prepared report changed");
            File source = root.resolve("source.bin").toFile(); Files.writeString(source.toPath(), "0123456789");
            PreparedExport range = PreparedExport.range(directory, "payload.bin", source, new FileRange(10, 2, 5), () -> false);
            Files.writeString(source.toPath(), "XXXXXXXXXX");
            output.reset(); range.copyTo(output, () -> false);
            require(output.toString("UTF-8").equals("23456"), "Picker delay changed the prepared payload");
            int before = Objects.requireNonNull(directory.list()).length;
            try { PreparedExport.range(directory, "cancel.bin", source, new FileRange(10, 0, 10), () -> true); throw new AssertionError("Cancelled preparation succeeded"); }
            catch (InterruptedIOException expected) {}
            require(Objects.requireNonNull(directory.list()).length == before, "Cancelled preparation left a partial staging file");
            File outside = root.resolve("tool-export-outside.snapshot").toFile(); Files.write(outside.toPath(), new byte[0]);
            try { PreparedExport.restore(directory, outside.toString(), "out", "application/json", 0); throw new AssertionError("Restored outside private directory"); }
            catch (IOException expected) {}
            report.discard(); range.discard();
            require(Objects.requireNonNull(directory.list()).length == 0, "Completed snapshots not discarded");
        } finally { try (var files = Files.walk(root)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); } }
        System.out.println("Tool workflow fixtures passed: navigation, full report scope, identities and immutable/cancelled export snapshots.");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
