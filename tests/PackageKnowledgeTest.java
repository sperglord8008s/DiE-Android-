// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent original-byte, Unicode, graph and ownership oracles; nothing is installed or run. */
public final class PackageKnowledgeTest {
    interface Task {
        void run() throws Exception;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void refuse(Task task, String message) throws Exception {
        try {
            task.run();
            throw new AssertionError("Accepted " + message);
        } catch (IOException expected) {
            require(expected.getMessage() != null, message);
        }
    }

    private static JSONObject record(String description) throws Exception {
        return new JSONObject()
                .put("description", description)
                .put("list", "Oem")
                .put("removal", "Advanced")
                .put("dependencies", new JSONArray())
                .put("neededBy", new JSONArray())
                .put("labels", new JSONArray());
    }

    private static PackageKnowledge read(byte[] value) throws Exception {
        return PackageKnowledge.read(new ByteArrayInputStream(value), () -> false);
    }

    private static String json(Object value) throws Exception {
        StringWriter out = new StringWriter();
        EvidenceJson.write(out, value, () -> false);
        return out.toString();
    }

    private static void write(Path file, EvidenceRows rows, PackageKnowledge catalog)
            throws Exception {
        try (Writer out =
                new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            rows.writeReport(out, catalog.provenance(), "", 0, () -> false);
        }
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        StringBuilder units =
                new StringBuilder(
                        "Early practical description\n"
                                + "long community text 世界 😀\n".repeat(4000));
        for (int i = 0; i < 65536; i++) units.append((char) i);
        units.append("FULL-DESCRIPTION-NEEDLE");
        String description = units.toString();
        try (DataOutputStream out =
                new DataOutputStream(
                        Files.newOutputStream(output.resolve("description-utf16be")))) {
            for (int i = 0; i < description.length(); i++) out.writeChar(description.charAt(i));
        }
        JSONObject a =
                record(description)
                        .put(
                                "dependencies",
                                new JSONArray()
                                        .put("app.B")
                                        .put("missing.ID")
                                        .put("app.B")
                                        .put(7)
                                        .put("app.A"))
                        .put("neededBy", new JSONArray().put("app.C"))
                        .put(
                                "unknown",
                                new JSONObject()
                                        .put(
                                                "largeNumber",
                                                (EvidenceJson.RawNumber) () -> "1.2300e+9999")
                                        .put(
                                                "integer",
                                                (EvidenceJson.RawNumber) () -> "9007199254740993")
                                        .put("negativeZero", (EvidenceJson.RawNumber) () -> "-0"));
        JSONObject b =
                record("B description")
                        .put("neededBy", new JSONArray().put("app.A"))
                        .put("dependencies", new JSONArray().put("app.C"));
        JSONObject c = record("C description").put("dependencies", new JSONArray().put("app.A"));
        byte[] fixture =
                json(new JSONObject()
                                .put("app.A", a)
                                .put("app.B", b)
                                .put("app.C", c)
                                .put("app.a", JSONObject.NULL))
                        .getBytes(StandardCharsets.UTF_8);
        Files.write(output.resolve("fixture-original.json"), fixture);
        PackageKnowledge catalog = read(fixture);
        require(catalog.entries.size() == 4 && catalog.byId.size() == 4, "Original record count");
        require(
                catalog.byId.get("app.A").get(0).description.equals(description),
                "Description UTF-16 loss");
        require(
                catalog.byId.get("APP.A") == null && catalog.byId.get("app.a").get(0).value == null,
                "Exact ID matching/type coverage");
        require(catalog.edges.size() == 9, "Original duplicate/malformed/cyclic declarations lost");
        String text = new String(fixture, StandardCharsets.UTF_8);
        for (PackageKnowledge.Entry entry : catalog.entries)
            require(
                    text.substring(entry.slice.start, entry.slice.end).equals(entry.slice.raw),
                    "Original source coordinates changed");
        EvidenceRows list = catalog.catalog("", "", false);
        require(
                list.select("full-description-needle", () -> false).count == 1,
                "Full value search clipped to preview");
        require(list.row(0, () -> false).encoded.length() < 20000, "Interactive row not bounded");
        require(
                catalog.card("APP.A").row(0, () -> false).json().getBoolean("exactMatch") == false,
                "Missing exact ID invented a match");
        EvidenceRows relations = catalog.relationships("app.A");
        require(relations.size() == 8, "Own/incoming occurrences confused");
        int duplicateB = 0, malformed = 0, missing = 0;
        for (int i = 0; i < relations.size(); i++) {
            JSONObject row = relations.row(i, () -> false).json();
            if (row.getString("packageId").equals("app.A")
                    && row.getString("declaredField").equals("dependencies")) {
                if ("app.B".equals(row.optString("targetPackageId"))) {
                    duplicateB++;
                    require(
                            row.getBoolean("reciprocalClaimRecorded"),
                            "Reciprocal source claim missing");
                }
                if (row.isNull("targetPackageId")) malformed++;
                if ("missing.ID".equals(row.optString("targetPackageId"))) {
                    missing++;
                    require(!row.getBoolean("targetRecordPresent"), "Absent target invented");
                }
            }
        }
        require(
                duplicateB == 2 && malformed == 1 && missing == 1,
                "Duplicate/unsupported target occurrences merged");
        write(output.resolve("fixture-catalog-report.json"), list, catalog);
        write(output.resolve("fixture-card-report.json"), catalog.card("app.A"), catalog);
        write(output.resolve("fixture-relations-report.json"), relations, catalog);
        PackageKnowledge duplicate =
                read("{\"a\":{},\"\\u0061\":{}}".getBytes(StandardCharsets.UTF_8));
        require(
                duplicate.entries.size() == 2 && duplicate.byId.get("a").size() == 2,
                "Duplicate original package occurrences lost");
        require(
                duplicate.card("a").metadata().getInt("exactIdRecordCount") == 2,
                "Ambiguity hidden");
        refuse(() -> duplicate.record("a", -1), "ambiguous original record without index");
        require(duplicate.record("a", 1).index == 1, "Original duplicate occurrence inaccessible");
        refuse(
                () -> catalog.record("app.A", catalog.byId.get("app.B").get(0).index),
                "foreign source record index");
        refuse(() -> catalog.record("app.A", 9999), "out-of-range source record index");
        refuse(
                () -> read("{\"a\":{\"x\":1,\"x\":2}}".getBytes(StandardCharsets.UTF_8)),
                "duplicate original field");
        refuse(
                () -> read("{\"a\":{\"x\":NaN}}".getBytes(StandardCharsets.UTF_8)),
                "non-JSON number");
        refuse(
                () -> read(new byte[] {'{', '"', (byte) 0xff, '"', ':', '{', '}', '}'}),
                "invalid source UTF-8");
        refuse(
                () -> PackageKnowledge.read(new ByteArrayInputStream(fixture), () -> true),
                "cancelled catalog");
        StringBuilder tooMany = new StringBuilder("{");
        for (int i = 0; i <= PackageKnowledge.MAX_PACKAGES; i++) {
            if (i > 0) tooMany.append(',');
            tooMany.append('"').append(i).append("\":null");
        }
        tooMany.append('}');
        refuse(() -> read(tooMany.toString().getBytes(StandardCharsets.UTF_8)), "record budget");

        File directory = output.resolve("owned").toFile();
        AtomicInteger opens = new AtomicInteger();
        PackageKnowledgeBackend backend =
                new PackageKnowledgeBackend(
                        () -> {
                            opens.incrementAndGet();
                            return new ByteArrayInputStream(fixture);
                        },
                        directory,
                        "app.A",
                        null,
                        catalog.sha256);
        String initial = backend.initialRequest(() -> false);
        EvidenceRows card = backend.open(initial, () -> false);
        require(
                card.kind.equals("package-knowledge-card"),
                "Initial exact package overview unavailable");
        require(
                card.row(0, () -> false).name.startsWith("What does"),
                "Practical description not first");
        String state = backend.retentionState();
        File owned = backend.byteSource();
        backend.close(true);
        PackageKnowledgeBackend restored =
                new PackageKnowledgeBackend(
                        () -> {
                            throw new AssertionError("Reopened expired source");
                        },
                        directory,
                        "app.A",
                        state,
                        catalog.sha256);
        EvidenceRows kept = restored.open(initial, () -> false);
        String recordRequest = PackageKnowledgeBackend.query("record", "app.A", "", "", false);
        PreparedExport staged =
                PreparedExport.stream(
                        output.resolve("exports").toFile(),
                        "record.json",
                        "application/json",
                        out -> restored.writeText(recordRequest, out, () -> false),
                        () -> false);
        byte[] originalExport = Files.readAllBytes(staged.file.toPath());
        require(
                Arrays.equals(fixture, restored.binaryValue("catalog-bytes", () -> false)),
                "Exact original catalog bytes changed");
        List<InvestigationBackend.Link> links =
                restored.related(initial, kept, kept.row(0, () -> false), () -> false);
        InvestigationBackend.Link field =
                links.stream()
                        .filter(link -> link.label.contains("complete original field"))
                        .findFirst()
                        .orElseThrow();
        StringWriter full = new StringWriter();
        restored.writeText(field.request, full, () -> false);
        Files.writeString(output.resolve("fixture-description-report.json"), full.toString());
        JSONObject wrongIndex = new JSONObject(initial).put("recordIndex", "0");
        refuse(
                () -> restored.open(wrongIndex.toString(), () -> false),
                "coerced original record index");
        JSONObject wrongType = new JSONObject(initial).put("packageId", 123);
        refuse(() -> restored.open(wrongType.toString(), () -> false), "coerced request ID");
        restored.close();
        require(!owned.exists() && opens.get() == 1, "Snapshot ownership/restoration reran source");
        ByteArrayOutputStream delayed = new ByteArrayOutputStream();
        staged.copyTo(delayed, () -> false);
        require(
                Arrays.equals(originalExport, delayed.toByteArray()),
                "Staged record changed after backend close");
        staged.discard();
        PackageKnowledgeBackend wrongPin =
                new PackageKnowledgeBackend(
                        () -> new ByteArrayInputStream(fixture),
                        directory,
                        null,
                        null,
                        "0".repeat(64));
        refuse(
                () -> wrongPin.open(wrongPin.initialRequest(() -> false), () -> false),
                "wrong pinned content");
        try (var files = Files.list(directory.toPath())) {
            require(files.count() == 0, "Rejected knowledge acquisition leaked");
        }

        try (InputStream input = Files.newInputStream(Path.of(args[1]))) {
            PackageKnowledge real = PackageKnowledge.read(input, () -> false);
            require(
                    real.sha256.equals(PackageKnowledge.PINNED_SHA256)
                            && real.entries.size() == 5402
                            && real.byId.size() == 5399,
                    "Bundled source pin/count changed");
            require(
                    real.byId
                            .get("org.lineageos.jelly")
                            .get(0)
                            .description
                            .startsWith("LineageOS Browser"),
                    "Actual practical record missing");
            require(
                    real.catalog("Google", "", false).size() == 184
                            && real.catalog("", "Unsafe", false).size() == 298,
                    "Actual source grouping differs");
            write(output.resolve("real-catalog-report.json"), real.catalog("", "", false), real);
            System.out.println(
                    "Actual pinned UAD-NG: "
                            + real.entries.size()
                            + " complete original records / "
                            + real.edges.size()
                            + " declared relationship occurrences.");
        }
        System.out.println(
                "Package knowledge exact IDs, Unicode/unknown/numeric/raw fields, full search,"
                        + " directed duplicate/malformed/missing/cyclic references, source expiry,"
                        + " pin/state/export ownership and refusal checks passed.");
    }
}
