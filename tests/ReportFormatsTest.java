// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serializes actual JSON trees; Python's standard XML/CSV parsers independently reconstruct them.
 */
public final class ReportFormatsTest {
    private interface Action {
        void run() throws Exception;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void rejects(Action action, String message) throws Exception {
        try {
            action.run();
        } catch (IOException | JSONException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static JSONObject report(boolean rows) throws Exception {
        StringBuilder units = new StringBuilder();
        for (int i = 0; i <= 65535; i++) units.append((char) i);
        String exact = units + "\r\n\t,\"<&]]> =SUM(1,2) ' /\\ 😀";
        Inspector.Result input = new Inspector.Result();
        input.size = 123;
        input.type = "fixture";
        input.sha256 = "a".repeat(64);
        input.blocks = new double[] {0, 7.25};
        input.strings = exact;
        input.hex = "hex";
        JSONObject nativeScan =
                new JSONObject()
                        .put("ok", rows)
                        .put("log", exact)
                        .put(
                                "opaque",
                                new JSONObject()
                                        .put(
                                                exact,
                                                new JSONArray()
                                                        .put(JSONObject.NULL)
                                                        .put(true)
                                                        .put(-1234567890123456789L)));
        if (rows) {
            JSONArray records = new JSONArray();
            for (int i = 0; i < 125; i++)
                records.put(
                        new JSONObject()
                                .put("parent", i == 0 ? -1 : (i - 1) / 3)
                                .put("category", "compiler,tool\t\r\n")
                                .put("name", i == 42 ? exact : "name " + i)
                                .put("signature", "Binary/quoted_\"row,\t" + i + ".sg")
                                .put("text", "=candidate \"" + i)
                                .put(
                                        "opaque",
                                        new JSONObject()
                                                .put("key", i)
                                                .put(
                                                        "array",
                                                        new JSONArray().put(false).put(1.25))));
            nativeScan.put("rows", records);
        } else nativeScan.put("error", "Fixture failed native scan; coverage unknown");
        JSONObject result =
                ScanReport.create(
                        exact,
                        input,
                        new ScanOptions(16383, 7, "compiler;metadata", ""),
                        ScanEvidence.parse(nativeScan.toString()),
                        new JSONObject().put("raw", exact),
                        "b".repeat(40),
                        "c".repeat(40),
                        "d".repeat(64),
                        10,
                        exact,
                        exact,
                        rows ? "" : "fixture failure");
        result.put(
                "unknown",
                new JSONObject()
                        .put("text", exact)
                        .put("null", JSONObject.NULL)
                        .put("array", new JSONArray().put("1").put(1).put(true)));
        return result;
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        for (boolean success : new boolean[] {true, false}) {
            JSONObject report = report(success);
            for (ScanReportFormats.Format format : ScanReportFormats.Format.values())
                try (Writer output =
                        Files.newBufferedWriter(
                                directory.resolve(
                                        (success ? "success." : "failed.") + format.extension),
                                StandardCharsets.UTF_8)) {
                    ScanReportFormats.write(output, report, format, () -> false);
                }
        }
        JSONObject emptyRows = report(false);
        emptyRows.getJSONObject("scan").put("rows", new JSONArray());
        JSONObject nullRows = report(false);
        nullRows.getJSONObject("scan").put("rows", JSONObject.NULL);
        for (String kind : new String[] {"empty", "null"}) {
            JSONObject value = kind.equals("empty") ? emptyRows : nullRows;
            for (ScanReportFormats.Format format : ScanReportFormats.Format.values())
                try (Writer output =
                        Files.newBufferedWriter(
                                directory.resolve(kind + "." + format.extension),
                                StandardCharsets.UTF_8)) {
                    ScanReportFormats.write(output, value, format, () -> false);
                }
        }
        JSONObject normal = report(true);
        for (ScanReportFormats.Format format : ScanReportFormats.Format.values()) {
            AtomicInteger checks = new AtomicInteger();
            rejects(
                    () ->
                            ScanReportFormats.write(
                                    new StringWriter(),
                                    normal,
                                    format,
                                    () -> checks.incrementAndGet() > 8),
                    "Format ignored cancellation: " + format);
        }
        JSONObject deep = report(false), cursor = deep;
        for (int i = 0; i < 258; i++) {
            JSONObject next = new JSONObject();
            cursor.put("nested", next);
            cursor = next;
        }
        rejects(
                () ->
                        ScanReportFormats.write(
                                new StringWriter(),
                                deep,
                                ScanReportFormats.Format.XML,
                                () -> false),
                "XML depth budget ignored");
        JSONObject many = report(true);
        JSONArray records = many.getJSONObject("scan").getJSONArray("rows");
        while (records.length() < 20_000)
            records.put(
                    new JSONObject()
                            .put("parent", -1)
                            .put("category", "fixture")
                            .put("text", "row " + records.length()));
        for (ScanReportFormats.Format format :
                new ScanReportFormats.Format[] {
                    ScanReportFormats.Format.CSV, ScanReportFormats.Format.TSV
                }) {
            final long[] characters = {0};
            Writer counter =
                    new Writer() {
                        public void write(char[] chars, int offset, int count) {
                            characters[0] += count;
                        }

                        public void flush() {}

                        public void close() {}
                    };
            ScanReportFormats.write(counter, many, format, () -> false);
            require(characters[0] > 3_000_000, "Full table export shortened");
        }
        records.put(new JSONObject());
        rejects(
                () ->
                        ScanReportFormats.write(
                                new StringWriter(),
                                many,
                                ScanReportFormats.Format.CSV,
                                () -> false),
                "20,001 native rows accepted");
        System.out.println(
                "Report format Java checks passed: full Unicode/schema/data fixtures,"
                    + " empty/missing/null rows, bounded streaming, cancellation and XML"
                    + " depth/native-row limits.");
    }
}
