// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.StringWriter;

public final class ScanTargetTest {
    public static void main(String[] args) throws Exception {
        JSONObject nativeData =
                new JSONObject()
                        .put("ok", true)
                        .put("future", new JSONObject().put("opaque", "\udfff"))
                        .put(
                                "types",
                                new JSONArray()
                                        .put(choice(0, "Automatic"))
                                        .put(choice(999, "ELF64"))
                                        .put(choice(17, "Binary"))
                                        .put(choice(999, "ELF64")));
        ScanTarget.Catalog catalog = ScanTarget.Catalog.parse(nativeData.toString());
        require(catalog.choices.size() == 4, "Repeated native catalog records dropped");
        ScanTarget.Request binary = catalog.choices.get(2).request();
        require(
                binary.formatName.equals("Binary")
                        && ScanTarget.Request.restore(binary.toJson().toString()).sameAs(binary),
                "Choice/state identity changed");
        String sha = "a".repeat(64), engine = "pinned-source";
        ScanTarget.Capture capture = ScanTarget.Capture.resolve(binary, catalog, sha, 32, engine);
        require(capture.nativeTypeId == 17, "Combo-box position used as native selector");
        JSONObject report = report(sha, 32, engine, 17);
        capture.attach(report);
        require(
                report.getJSONObject("options").getInt("typeId") == 17
                        && report.getJSONObject("scanTarget")
                                        .getJSONObject("compatibleFormats")
                                        .getJSONArray("types")
                                        .length()
                                == 4,
                "Target/report scope mismatch");
        StringWriter encoded = new StringWriter();
        EvidenceJson.write(encoded, report, () -> false);
        require(
                new JSONObject(encoded.toString())
                        .getJSONObject("scanTarget")
                        .getJSONObject("compatibleFormats")
                        .getJSONObject("future")
                        .getString("opaque")
                        .equals("\udfff"),
                "Opaque catalog Unicode metadata lost");
        require(
                ScanTarget.Capture.resolve(
                                        new ScanTarget.Request("ELF64"), catalog, sha, 32, engine)
                                .nativeTypeId
                        == 999,
                "Repeated identical selector became ambiguous");
        JSONObject ambiguous = new JSONObject(nativeData.toString());
        ambiguous.getJSONArray("types").put(choice(555, "ELF64"));
        ScanTarget.Capture bad =
                ScanTarget.Capture.resolve(
                        new ScanTarget.Request("ELF64"),
                        ScanTarget.Catalog.parse(ambiguous.toString()),
                        sha,
                        32,
                        engine);
        require(
                bad.nativeTypeId == null && bad.error.contains("ambiguous"),
                "Ambiguous name silently chose first selector");
        bad =
                ScanTarget.Capture.resolve(
                        new ScanTarget.Request("binary"), catalog, sha, 32, engine);
        require(bad.nativeTypeId == null, "Native label was case-normalized");
        JSONObject failed = report(sha, 32, engine, 0);
        failed.put("scan", new JSONObject().put("ok", false).put("error", "unresolved"));
        bad.attach(failed);
        require(
                failed.getJSONObject("options").isNull("typeId")
                        && failed.getJSONObject("scanTarget")
                                .getJSONObject("request")
                                .getString("formatName")
                                .equals("binary"),
                "Unresolved selection mislabeled automatic");
        require(
                ScanTarget.Capture.resolve(
                                        ScanTarget.AUTOMATIC,
                                        ScanTarget.Catalog.unavailable("native unavailable"),
                                        sha,
                                        32,
                                        engine)
                                .nativeTypeId
                        == 0,
                "Automatic invented a nonzero selection");
        require(
                ScanTarget.Capture.resolve(
                                        new ScanTarget.Request("Automatic"),
                                        catalog,
                                        sha,
                                        32,
                                        engine)
                                .nativeTypeId
                        == null,
                "Explicit name silently became automatic");
        expectFailure(() -> capture.attach(report("b".repeat(64), 32, engine, 17)));
        expectFailure(() -> capture.attach(report(sha, 31, engine, 17)));
        expectFailure(
                () -> {
                    JSONObject fractional = report(sha, 32, engine, 17);
                    fractional.getJSONObject("source").put("sizeBytes", 32.25);
                    capture.attach(fractional);
                });

        expectFailure(() -> capture.attach(report(sha, 32, "other-source", 17)));
        expectFailure(() -> capture.attach(report(sha, 32, engine, 999)));
        expectFailure(
                () ->
                        ScanTarget.Catalog.parse(
                                new JSONObject(nativeData.toString())
                                        .put("types", new JSONArray().put(choice(1, "Binary")))
                                        .toString()));
        expectFailure(
                () ->
                        ScanTarget.Catalog.parse(
                                new JSONObject(nativeData.toString())
                                        .put(
                                                "types",
                                                new JSONArray()
                                                        .put(choice(0, "Automatic"))
                                                        .put(
                                                                new JSONObject()
                                                                        .put("id", 0.5)
                                                                        .put("name", "Binary")))
                                        .toString()));
        expectFailure(
                () ->
                        ScanTarget.Request.restore(
                                "{\"selectionMode\":\"automatic\",\"formatName\":\"ELF64\"}"));
        require(
                catalog.toJson().getJSONArray("types").getJSONObject(2).getInt("id") == 17,
                "Report attachment mutated catalog");
        System.out.println(
                "Scan targets passed: stable labels/native IDs, full catalogs/duplicates/unknown"
                    + " metadata, exact source/engine binding, ambiguous/missing selections, state"
                    + " and report scope.");
    }

    private static JSONObject choice(int id, String name) throws Exception {
        return new JSONObject().put("id", id).put("name", name);
    }

    private static JSONObject report(String sha, long size, String engine, int id)
            throws Exception {
        return new JSONObject()
                .put("source", new JSONObject().put("sha256", sha).put("sizeBytes", size))
                .put("provenance", new JSONObject().put("engineRevision", engine))
                .put("options", new JSONObject().put("typeId", 0))
                .put("scan", new JSONObject().put("ok", true).put("typeId", id));
    }

    interface Action {
        void run() throws Exception;
    }

    private static void expectFailure(Action action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Invalid target contract accepted");
        } catch (JSONException expected) {
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
