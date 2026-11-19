// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Journal/evidence policies with injected catalogs; real native passes have Android tests. */
public final class BatchInterpretationTest {
    private static final String ENGINE = "a".repeat(40), DB = "b".repeat(40);
    private static final ScanOptions OPTIONS = new ScanOptions(0, 4, "", "");
    private static final ScanTarget.Request BINARY = new ScanTarget.Request("Binary");

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void rejects(Action action) throws Exception {
        try {
            action.run();
        } catch (IOException | JSONException expected) {
            return;
        }
        throw new AssertionError("Invalid interpretation journal accepted");
    }

    private static BatchScanSession.Source source(String name) {
        return new BatchScanSession.Source(
                name,
                "fixture:" + name,
                () -> new ByteArrayInputStream(name.getBytes(StandardCharsets.UTF_8)));
    }

    private static JSONObject report(InputSnapshot input, String name, ScanTarget.Request request)
            throws Exception {
        int selector = name.equals("right") ? 99 : 77;
        JSONObject catalog =
                new JSONObject()
                        .put("ok", true)
                        .put("opaque", "\ud800")
                        .put(
                                "types",
                                new JSONArray()
                                        .put(new JSONObject().put("id", 0).put("name", "Automatic"))
                                        .put(
                                                new JSONObject()
                                                        .put("id", selector)
                                                        .put(
                                                                "name",
                                                                name.equals("missing")
                                                                        ? "Other"
                                                                        : "Binary")));
        ScanTarget.Capture target =
                ScanTarget.Capture.resolve(
                        request,
                        ScanTarget.Catalog.parse(catalog.toString()),
                        input.sha256,
                        input.size,
                        ENGINE);
        JSONObject scan = new JSONObject().put("ok", target.nativeTypeId != null);
        if (target.nativeTypeId == null) scan.put("error", target.error);
        else
            scan.put("typeId", target.nativeTypeId)
                    .put(
                            "rows",
                            new JSONArray()
                                    .put(
                                            new JSONObject()
                                                    .put("parent", -1)
                                                    .put("text", "fixture observation")
                                                    .put("category", "fixture")));
        JSONObject report =
                ScanReport.create(
                        name,
                        Inspector.scan(input.file, 4),
                        OPTIONS,
                        ScanEvidence.parse(scan.toString()),
                        null,
                        ENGINE,
                        DB,
                        "",
                        0,
                        "",
                        "",
                        target.error);
        target.attach(report);
        return report;
    }

    private static BatchScanSession run(
            File root,
            List<BatchScanSession.Source> sources,
            ScanTarget.Request request,
            BatchScanSession.Analyzer analyzer)
            throws Exception {
        BatchScanSession session =
                BatchScanSession.create(root, sources, OPTIONS, request, ENGINE, DB, "");
        session.run(sources, analyzer, new BatchScanSession.Control(), text -> {});
        return session;
    }

    public static void main(String[] arguments) throws Exception {
        File root = new File(arguments[0], "interpretations");
        List<BatchScanSession.Source> sources =
                List.of(source("left"), source("right"), source("missing"));
        BatchScanSession session =
                run(
                        new File(root, "mixed"),
                        sources,
                        BINARY,
                        (input, name, stop) -> report(input, name, BINARY));
        require(
                session.metadata().getInt("schemaVersion") == 2
                        && session.metadata().getJSONObject("options").isNull("typeId"),
                "Batch plan pretended one resolved selector applies to all inputs");
        BatchScanSession loaded =
                BatchScanSession.open(session.directory.getParentFile(), session.id, () -> false);
        require(loaded.interpretation.sameAs(BINARY), "Captured request changed after reopen");
        JSONObject left = loaded.report(loaded.entry(0), () -> false),
                right = loaded.report(loaded.entry(1), () -> false),
                missing = loaded.report(loaded.entry(2), () -> false);
        require(
                left.getJSONObject("options").getInt("typeId") == 77
                        && right.getJSONObject("options").getInt("typeId") == 99,
                "Input-specific IDs were reused across catalogs");
        require(
                loaded.entry(2).status.equals("failed")
                        && !missing.getJSONObject("scan").getBoolean("ok")
                        && missing.getJSONObject("options").isNull("typeId")
                        && missing.getJSONObject("scanTarget")
                                .getString("resolutionError")
                                .contains("not available"),
                "Unavailable interpretation fell back to Automatic or lost its failed report");
        ScanComparison comparison =
                ScanComparison.compare(left, missing, new JSONObject(), () -> false);
        require(
                comparison.uncompared == 1 && comparison.rightOnly == 0,
                "Failed interpretation became an absent detection");
        JSONObject conditions = comparison.metadata().getJSONObject("conditions");
        require(
                !conditions.getBoolean("nativeSelectorsResolved")
                        && conditions.isNull("sameNativeOptions")
                        && conditions.isNull("configurationDiffers"),
                "Unknown selectors were presented as known same/different execution settings");
        require(
                comparison.metadata().getJSONObject("right").has("scanTarget"),
                "Comparison discarded captured interpretation context");
        JSONObject changed = new JSONObject(missing.toString());
        changed.getJSONObject("options").put("flags", 1);
        JSONObject knownDifference =
                ScanComparison.compare(left, changed, new JSONObject(), () -> false)
                        .metadata()
                        .getJSONObject("conditions");
        require(
                knownDifference.getBoolean("configurationDiffers")
                        && !knownDifference.getBoolean("sameNativeOptions"),
                "A known flag difference was discarded because a selector was unresolved");
        StringWriter complete = new StringWriter();
        loaded.writeReport(complete, () -> false);
        require(
                new JSONObject(complete.toString()).getJSONArray("entries").length() == 3,
                "Complete export lost an unresolved input");
        BatchScanSession mismatch =
                run(
                        new File(root, "mismatch"),
                        List.of(source("left")),
                        BINARY,
                        (input, name, stop) -> report(input, name, ScanTarget.AUTOMATIC));
        require(
                mismatch.entry(0).status.equals("failed")
                        && mismatch.entry(0).reportName.isEmpty()
                        && !mismatch.entry(0).snapshotName.isEmpty(),
                "Different analyzer request was accepted");
        File journal = new File(session.directory, "state.json");
        byte[] original = Files.readAllBytes(journal.toPath());
        JSONObject bad = new JSONObject(new String(original, StandardCharsets.UTF_8));
        bad.remove("interpretationRequest");
        Files.writeString(journal.toPath(), bad.toString());
        rejects(
                () ->
                        BatchScanSession.open(
                                session.directory.getParentFile(), session.id, () -> false));
        bad = new JSONObject(new String(original, StandardCharsets.UTF_8));
        bad.getJSONObject("options").put("typeId", 77);
        Files.writeString(journal.toPath(), bad.toString());
        rejects(
                () ->
                        BatchScanSession.open(
                                session.directory.getParentFile(), session.id, () -> false));
        Files.write(journal.toPath(), original);

        // Build an actual old journal/report shape, including remeasured private report identity.
        BatchScanSession old =
                run(
                        new File(root, "legacy"),
                        List.of(source("legacy")),
                        ScanTarget.AUTOMATIC,
                        (input, name, stop) -> report(input, name, ScanTarget.AUTOMATIC));
        JSONObject oldReport = old.report(old.entry(0), () -> false);
        oldReport.remove("scanTarget");
        byte[] reportBytes = oldReport.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(new File(old.directory, old.entry(0).reportName).toPath(), reportBytes);
        Path oldJournal = new File(old.directory, "state.json").toPath();
        JSONObject state = new JSONObject(Files.readString(oldJournal));
        state.put("schemaVersion", 1);
        state.remove("interpretationRequest");
        state.getJSONArray("entries")
                .getJSONObject(0)
                .put("reportSizeBytes", reportBytes.length)
                .put(
                        "reportSha256",
                        Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(reportBytes)));
        Files.writeString(oldJournal, state.toString());
        BatchScanSession legacy =
                BatchScanSession.open(old.directory.getParentFile(), old.id, () -> false);
        require(
                legacy.schemaVersion == 1
                        && legacy.interpretation.automatic()
                        && !legacy.report(legacy.entry(0), () -> false).has("scanTarget"),
                "Legacy Automatic evidence was rejected or retroactively assigned a catalog");
        System.out.println(
                "Batch interpretation policies passed: per-input selectors, immutable name plans,"
                        + " unresolved reports/unknown comparisons, mismatched analyzer rejection,"
                        + " strict journals and original v1 capture compatibility.");
    }
}
