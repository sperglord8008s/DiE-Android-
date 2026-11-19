// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent path-list oracle, duplicate-parent witnesses and complete captured-tree exports. */
public final class ScanComparisonTest {
    private static final ScanOptions OPTIONS =
            new ScanOptions(ScanOptions.DEFAULT_FLAGS, 4, "", "");
    private static final String ENGINE = "a".repeat(40), DB = "b".repeat(40);

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void rejects(Action action, String message) throws Exception {
        try {
            action.run();
        } catch (IOException | JSONException | IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static JSONObject row(int parent, String name) throws Exception {
        return new JSONObject()
                .put("parent", parent)
                .put("text", name)
                .put("category", "fixture")
                .put("name", name)
                .put("signature", "Binary/" + name + ".sg");
    }

    private static JSONArray rows(JSONObject... records) {
        JSONArray rows = new JSONArray();
        for (JSONObject record : records) rows.put(record);
        return rows;
    }

    private static JSONObject report(JSONArray rows) throws Exception {
        Inspector.Result input = new Inspector.Result();
        input.size = 123;
        input.sha256 = "c".repeat(64);
        input.type = "fixture";
        input.blocks = new double[0];
        input.strings = "";
        input.hex = "";
        ScanEvidence evidence =
                ScanEvidence.parse(
                        new JSONObject()
                                .put("ok", true)
                                .put("rows", rows)
                                .put("flags", OPTIONS.flags)
                                .put("log", "fixture native diagnostic")
                                .put("report", "original text")
                                .toString());
        return ScanReport.create(
                "source-\ud800\u0000照片",
                input,
                OPTIONS,
                evidence,
                null,
                ENGINE,
                DB,
                "",
                1,
                "guide",
                "not run",
                "");
    }

    private static ScanComparison compare(JSONObject left, JSONObject right) throws Exception {
        return ScanComparison.compare(
                left,
                right,
                new JSONObject().put("batchId", "fixture-batch").put("unknown", "\udfff\u0000"),
                () -> false);
    }

    public static void main(String[] args) throws Exception {
        reorderAndAncestorCases();
        unknownFieldsAndMetadata();
        failuresAndBudgets();
        randomOracle();
        deepAndFullExports();
        System.out.println(
                "Scan comparison passed: independent exact-path oracle, reordered siblings,"
                        + " duplicate-parent occurrence alignment, ancestor changes, opaque typed"
                        + " fields/UTF-16, immutable conditions, failed-scan unknown coverage,"
                        + " cancellation/budgets, 20,000-deep linear paths and complete exports.");
    }

    private static void reorderAndAncestorCases() throws Exception {
        JSONObject left =
                report(rows(row(-1, "root"), row(0, "one"), row(0, "two"), row(1, "child")));
        JSONObject right =
                report(rows(row(-1, "root"), row(0, "two"), row(0, "one"), row(2, "child")));
        ScanComparison reordered = compare(left, right);
        require(
                reordered.matched == 4 && reordered.leftOnly == 0 && reordered.rightOnly == 0,
                "Pure sibling/index reorder changed exact tree records");
        ScanComparison.Record child = reordered.table("matched").get(3);
        require(
                child.leftIndex == 3
                        && child.rightIndex == 3
                        && reordered.record(child).getJSONObject("rightNativeRow").getInt("parent")
                                == 2,
                "Original parent ordinal was normalized away");
        right.getJSONObject("scan").getJSONArray("rows").getJSONObject(3).put("parent", 1);
        ScanComparison moved = compare(left, right);
        require(
                moved.matched == 3 && moved.leftOnly == 1 && moved.rightOnly == 1,
                "Changed ancestor relationship disappeared");
        JSONObject repeated =
                report(
                        rows(
                                row(-1, "root"),
                                row(0, "same-group"),
                                row(1, "A"),
                                row(0, "same-group"),
                                row(3, "B")));
        JSONObject swapped =
                report(
                        rows(
                                row(-1, "root"),
                                row(0, "same-group"),
                                row(1, "B"),
                                row(0, "same-group"),
                                row(3, "A")));
        ScanComparison duplicate = compare(repeated, swapped);
        require(
                duplicate.matched == 3 && duplicate.leftOnly == 2 && duplicate.rightOnly == 2,
                "Identical parent instances collapsed child ownership");
        require(
                duplicate.metadata().getJSONObject("duplicateSiblingOccurrences").getInt("left")
                        == 1,
                "Duplicate occurrence mechanism hidden");
        ScanComparison multiplicity =
                compare(
                        report(rows(row(-1, "same"), row(-1, "same"))),
                        report(rows(row(-1, "same"))));
        require(
                multiplicity.matched == 1 && multiplicity.leftOnly == 1,
                "Duplicate record multiplicity lost");
    }

    private static void unknownFieldsAndMetadata() throws Exception {
        JSONObject previewOnly = report(rows(row(-1, "same")));
        previewOnly.getJSONObject("options").put("minimumStringLength", 9);
        previewOnly
                .getJSONObject("provenance")
                .getJSONObject("customDatabase")
                .put("archiveSha256", "f".repeat(64));
        JSONObject previewConditions =
                compare(report(rows(row(-1, "same"))), previewOnly)
                        .metadata()
                        .getJSONObject("conditions");
        require(
                !previewConditions.getBoolean("samePreviewMinimum")
                        && !previewConditions.getBoolean("sameCustomArchive")
                        && previewConditions.getBoolean("sameEffectiveCustomDatabase")
                        && !previewConditions.getBoolean("configurationDiffers"),
                "Preview-only/disabled custom metadata was presented as a native execution"
                    + " difference");
        JSONObject extra =
                new JSONObject()
                        .put("array", new JSONArray().put("\ud800\u0000").put(1))
                        .put("parent", "opaque nested parent");
        JSONObject left = report(rows(row(-1, "unknown").put("extra", extra)));
        JSONObject reversed =
                new JSONObject()
                        .put("parent", "opaque nested parent")
                        .put("array", new JSONArray().put("\ud800\u0000").put(1));
        JSONObject right = report(rows(row(-1, "unknown").put("extra", reversed)));
        require(compare(left, right).matched == 1, "Object-key order changed comparison semantics");
        reversed.getJSONArray("array").put(1, "1");
        // Report creation captured a separate scan JSON; edit the actual retained raw field.
        right.getJSONObject("scan")
                .getJSONArray("rows")
                .getJSONObject(0)
                .getJSONObject("extra")
                .getJSONArray("array")
                .put(1, "1");
        require(compare(left, right).leftOnly == 1, "Unknown field/type change ignored");
        JSONObject modified = report(rows(row(-1, "same")));
        modified.getJSONObject("options").put("flags", 0).put("includeTags", "changed");
        modified.getJSONObject("provenance")
                .put("engineRevision", "d".repeat(40))
                .put("signatureRevision", "e".repeat(40))
                .getJSONObject("customDatabase")
                .put("archiveSha256", "f".repeat(64));
        ScanComparison captured = compare(report(rows(row(-1, "same"))), modified);
        JSONObject conditions = captured.metadata().getJSONObject("conditions");
        require(
                conditions.getBoolean("sameCapturedInputIdentity")
                        && conditions.getBoolean("configurationDiffers"),
                "Content identity/configuration differences conflated");
        require(
                !conditions.getBoolean("sameEffectiveOptions")
                        && !conditions.getBoolean("sameEngineRevision")
                        && !conditions.getBoolean("sameSignatureRevision")
                        && !conditions.getBoolean("sameCustomArchive"),
                "Captured comparison conditions lost");
        modified.getJSONObject("options").put("flags", OPTIONS.flags);
        modified.getJSONObject("scan")
                .getJSONArray("rows")
                .getJSONObject(0)
                .put("text", "later mutation");
        require(
                !captured.metadata().getJSONObject("conditions").getBoolean("sameEffectiveOptions")
                        && captured.record(captured.table("all").get(0))
                                .getJSONObject("rightNativeRow")
                                .getString("text")
                                .equals("same"),
                "Caller mutation relabeled captured evidence");
        StringWriter out = new StringWriter();
        captured.writeReport(out, () -> false);
        JSONObject exported = new JSONObject(out.toString());
        require(
                exported.getJSONObject("left")
                        .getJSONObject("source")
                        .getString("name")
                        .equals("source-\ud800\u0000照片"),
                "Source UTF-16 changed");
        require(
                exported.getJSONObject("capturedReportIdentity")
                        .getString("unknown")
                        .equals("\udfff\u0000"),
                "Opaque context UTF-16 changed");
    }

    private static void failuresAndBudgets() throws Exception {
        JSONObject good = report(rows(row(-1, "root"), row(0, "one"))),
                failed = report(new JSONArray());
        failed.put(
                "scan", new JSONObject().put("ok", false).put("error", "Detection report capped"));
        ScanComparison unknown = compare(good, failed);
        require(
                !unknown.performed
                        && unknown.uncompared == 2
                        && unknown.leftOnly == 0
                        && unknown.rightOnly == 0,
                "Failed scan interpreted as disappearance");
        require(
                unknown.table("differences").isEmpty() && unknown.table("uncompared").size() == 2,
                "Unknown coverage mislabeled as a change");
        AtomicInteger checks = new AtomicInteger();
        rejects(
                () ->
                        ScanComparison.compare(
                                good, good, new JSONObject(), () -> checks.incrementAndGet() > 4),
                "Cancellation ignored");
        require(compare(good, good).matched == 2, "Cancelled comparison poisoned later request");
        JSONObject malformed = report(rows(row(-1, "bad")));
        malformed.getJSONObject("scan").getJSONArray("rows").getJSONObject(0).put("parent", 0);
        rejects(() -> compare(good, malformed), "Cyclic/forward parent accepted");
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i <= ScanComparison.MAX_ROWS; i++) tooMany.put(row(-1, "r" + i));
        rejects(() -> compare(good, report(tooMany)), "20,001 rows accepted");
        JSONObject large =
                report(
                        rows(
                                row(-1, "huge")
                                        .put(
                                                "opaque",
                                                "x"
                                                        .repeat(
                                                                ScanComparison.MAX_KEY_CHARACTERS
                                                                        + 1))));
        rejects(() -> compare(good, large), "Unbounded canonical row field accepted");
        JSONObject nested = new JSONObject();
        JSONObject cursor = nested;
        for (int i = 0; i < 66; i++) {
            JSONObject next = new JSONObject();
            cursor.put("next", next);
            cursor = next;
        }
        JSONObject deep = report(rows(row(-1, "nested").put("opaque", nested)));
        rejects(() -> compare(good, deep), "Unbounded nested row field accepted");
    }

    /** Explicit ancestor lists + sibling multiplicities, independent of the production interner. */
    private static List<List<String>> oracle(JSONArray rows) throws Exception {
        List<List<String>> paths = new ArrayList<>();
        Map<Integer, Map<String, Integer>> occurrences = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            int parent = row.getInt("parent");
            String name = row.getString("name");
            Map<String, Integer> counts =
                    occurrences.computeIfAbsent(parent, ignored -> new HashMap<>());
            int occurrence = counts.getOrDefault(name, 0);
            counts.put(name, occurrence + 1);
            List<String> path = parent < 0 ? new ArrayList<>() : new ArrayList<>(paths.get(parent));
            path.add(name + "\u0000" + occurrence);
            paths.add(path);
        }
        return paths;
    }

    private static JSONArray randomRows(Random random) throws Exception {
        JSONArray rows = new JSONArray();
        int count = random.nextInt(80);
        for (int i = 0; i < count; i++)
            rows.put(
                    row(
                            i == 0 || random.nextInt(4) == 0 ? -1 : random.nextInt(i),
                            "node-" + random.nextInt(8)));
        return rows;
    }

    private static void randomOracle() throws Exception {
        Random random = new Random(9751203);
        for (int i = 0; i < 100; i++) {
            JSONArray left = randomRows(random), right = randomRows(random);
            List<List<String>> a = oracle(left), b = oracle(right);
            int common = 0;
            for (List<String> path : a) if (b.contains(path)) common++;
            ScanComparison actual = compare(report(left), report(right));
            require(
                    actual.matched == common
                            && actual.leftOnly == a.size() - common
                            && actual.rightOnly == b.size() - common,
                    "Independent ancestor-list oracle disagrees");
        }
    }

    private static void deepAndFullExports() throws Exception {
        JSONArray chain = new JSONArray();
        for (int i = 0; i < ScanComparison.MAX_ROWS; i++) chain.put(row(i - 1, "deep"));
        JSONObject tree = report(chain);
        ScanComparison actual = compare(tree, tree);
        require(
                actual.matched == ScanComparison.MAX_ROWS
                        && actual.record(actual.table("matched").get(19999)).getInt("leftDepth")
                                == 19999,
                "Deep tree was flattened/truncated");
        final long[] chars = {0};
        Writer counter =
                new Writer() {
                    public void write(char[] value, int offset, int count) {
                        chars[0] += count;
                    }

                    public void flush() {}

                    public void close() {}
                };
        actual.writeReport(counter, () -> false);
        require(chars[0] > 5_000_000, "Complete deep-tree export shortened");
        rejects(
                () -> actual.writeReport(new StringWriter(), () -> true),
                "Complete export ignored cancellation");
    }
}
