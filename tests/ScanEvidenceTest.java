// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.nio.file.*;
import org.json.*;

public final class ScanEvidenceTest {
    public static void main(String[] args) throws Exception {
        ScanOptions original = new ScanOptions(ScanOptions.DEFAULT_FLAGS | ScanOptions.ARCHIVES | ScanOptions.CACHE | ScanOptions.CUSTOM_DB,
            5, " build;format ", "extra");
        ScanOptions changed = original.withBasicFlags(ScanOptions.AGGRESSIVE, 7);
        require((changed.flags & ScanOptions.ARCHIVES) != 0 && (changed.flags & ScanOptions.CACHE) != 0, "Basic controls lost laboratory settings");
        require((changed.flags & ScanOptions.DEEP) == 0 && (changed.flags & ScanOptions.AGGRESSIVE) != 0, "Basic controls not applied");
        require(changed.includeTags.equals("build;format") && changed.minimumStringLength == 7, "Options snapshot changed");
        require(!original.sameAs(new ScanOptions(original.flags, original.minimumStringLength, "other", original.excludeTags)), "Tag-only changes were hidden");
        invalid(() -> new ScanOptions(65536, 4, "", ""));
        invalid(() -> new ScanOptions(0, 2, "", ""));
        invalid(() -> new ScanOptions(0, 4, "tag\0hidden", ""));

        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("parent", -1).put("text", "ELF64").put("category", "format"));
        for (int i = 1; i < 123; i++) rows.put(new JSONObject().put("parent", 0)
            .put("text", "Library " + i).put("category", "library").put("name", "name" + i)
            .put("signature", "/db/ELF/fixture_" + i + ".sg"));
        JSONObject nativeResult = new JSONObject().put("ok", true).put("rows", rows).put("type", "ELF64")
            .put("signatureCount", 12345).put("log", "Unknown compression method: fixture");
        ScanEvidence evidence = ScanEvidence.parse(nativeResult.toString());
        require(evidence.rows.size() == 123 && evidence.rows.get(100).depth == 1, "Tree hierarchy lost");
        require(evidence.page("", 0, 50).size() == 50 && evidence.page("", 1, 50).get(0).index == 50, "Paging skipped records");
        require(evidence.page("", 2, 50).size() == 23 && evidence.page("", 3, 50).isEmpty(), "Paging tail incorrect");
        require(evidence.page("fixture_122.sg", 0, 50).get(0).index == 122, "Rule provenance search failed");
        require(evidence.toJson().getString("log").equals(evidence.log), "Engine diagnostics lost");
        JSONObject mutable = evidence.toJson(); mutable.put("ok", false);
        require(evidence.toJson().getBoolean("ok"), "Evidence snapshot is mutable");
        require(!ScanEvidence.parse("{\"ok\":false,\"error\":\"Scan cancelled\"}").ok, "Negative scan converted to success");
        malformed("{\"ok\":true}");
        malformed("{\"ok\":true,\"rows\":[{\"parent\":0,\"text\":\"cycle\",\"category\":\"\"}]}");
        malformed("{\"ok\":true,\"rows\":[null]}");

        Path input = Files.createTempFile("die-model", ".bin");
        try {
            Files.writeString(input, "Evidence fixture");
            Inspector.Result inspection = Inspector.scan(input.toFile(), 4);
            JSONObject report = ScanReport.create("fixture.bin", inspection, original, evidence, null,
                "engine-revision", "db-revision", "c".repeat(64), 100, "explanation", "APK details", "");
            // Round-trip through serialization, as an external report consumer would.
            report = new JSONObject(report.toString());
            require(report.getInt("schemaVersion") == 1, "Missing schema version");
            require(report.getJSONObject("source").getString("sha256").equals(inspection.sha256), "Wrong input identity");
            require(report.getJSONObject("scan").getJSONArray("rows").length() == 123, "Scan JSON was stringified or lost");
            require(report.getJSONObject("options").getInt("flags") == original.flags, "Options not captured");
            require(report.getJSONObject("provenance").getString("signatureRevision").equals("db-revision"), "Provenance missing");
            JSONObject custom = report.getJSONObject("provenance").getJSONObject("customDatabase");
            require(custom.getBoolean("enabled") && custom.getString("archiveSha256").equals("c".repeat(64)), "Custom database identity lost");
        } finally { Files.deleteIfExists(input); }
        System.out.println("Options, typed evidence paging, diagnostics and versioned report fixtures passed.");
    }

    interface Action { void run() throws Exception; }
    private static void invalid(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid option accepted"); }
        catch (IllegalArgumentException expected) {}
    }
    private static void malformed(String json) throws Exception {
        try { ScanEvidence.parse(json); throw new AssertionError("Malformed evidence accepted"); }
        catch (JSONException expected) {}
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
