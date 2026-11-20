// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Independent subject ownership, strict association, UTF-16 and duplicate-claim boundaries. */
public final class PackageKnowledgeSubjectTest {
    private static final String ID = "org.example.same";

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void refused(Action action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected association refusal");
        } catch (IOException expected) {
        }
    }

    private static JSONObject fixture(String text) throws Exception {
        return new JSONObject()
                .put("schema", PackageKnowledgeSubject.SCHEMA)
                .put("schemaVersion", 1)
                .put("kind", "apk-literal-package")
                .put("packageId", ID)
                .put(
                        "capturedApk",
                        new JSONObject()
                                .put("sha256", "a".repeat(64))
                                .put("sizeBytes", 1234)
                                .put("acquired", true)
                                .put("manifestParsed", true)
                                .put("identityStableDuringAnalysis", true)
                                .put(
                                        "manifestInput",
                                        new JSONObject()
                                                .put("sha256", "b".repeat(64))
                                                .put("sizeBytes", 512)))
                .put(
                        "packageDeclaration",
                        new JSONObject()
                                .put("key", "package")
                                .put("kind", "string")
                                .put("status", "literal")
                                .put("value", ID)
                                .put("defaultApplied", false)
                                .put(
                                        "originalAttributeRecords",
                                        new JSONArray()
                                                .put(new JSONObject().put("unknownUtf16", text))))
                .put(
                        "unknownFutureField",
                        new JSONObject().put("null", JSONObject.NULL).put("rawText", text));
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        Path subjects = root.resolve("subjects"), catalogs = root.resolve("catalogs");
        char[] all = new char[65536];
        for (int i = 0; i < all.length; i++) all[i] = (char) i;
        String utf16 = new String(all);
        JSONObject original = fixture(utf16);
        PackageKnowledgeSubject subject =
                PackageKnowledgeSubject.capture(subjects.toFile(), original, () -> false);
        String descriptor = subject.descriptor();
        File subjectFile = subject.input.file;
        StringWriter streamed = new StringWriter();
        subject.write(streamed, () -> false);
        require(
                streamed.toString().getBytes(StandardCharsets.UTF_8).length == subject.input.size,
                "Subject stream length changed");
        Files.writeString(
                root.resolve("subject.json"), streamed.toString(), StandardCharsets.UTF_8);
        try (DataOutputStream out =
                new DataOutputStream(Files.newOutputStream(root.resolve("expected-utf16be")))) {
            for (char code : all) out.writeChar(code);
        }
        JSONObject copied = subject.json();
        copied.put("packageId", "changed");
        require(
                subject.json().getString("packageId").equals(ID),
                "Mutable subject escaped its owner");
        require(
                PackageKnowledgeSubject.restore(subjects.toFile(), descriptor, ID, () -> false)
                        .json()
                        .getJSONObject("unknownFutureField")
                        .getString("rawText")
                        .equals(utf16),
                "UTF-16 changed");
        refused(
                () ->
                        PackageKnowledgeSubject.restore(
                                subjects.toFile(), descriptor, "another.id", () -> false));
        require(subjectFile.isFile(), "Failed restore removed another owner's evidence");
        JSONObject bad = fixture("");
        bad.getJSONObject("packageDeclaration").put("status", "ambiguous-attributes");
        refused(() -> PackageKnowledgeSubject.capture(subjects.toFile(), bad, () -> false));
        JSONObject numeric = fixture("");
        numeric.put("schemaVersion", "1");
        refused(() -> PackageKnowledgeSubject.capture(subjects.toFile(), numeric, () -> false));
        refused(
                () ->
                        PackageKnowledgeSubject.capture(
                                subjects.toFile(), fixture("x".repeat(1024 * 1024)), () -> false));
        refused(() -> PackageKnowledgeSubject.capture(subjects.toFile(), fixture(""), () -> true));
        JSONObject escaped =
                new JSONObject(descriptor).put("path", root.resolve("outside.snapshot").toString());
        refused(
                () ->
                        PackageKnowledgeSubject.restore(
                                subjects.toFile(), escaped.toString(), ID, () -> false));
        require(
                subjects.toFile().list().length == 1,
                "Refused subject left a partial private file");

        byte[] bytes =
                ("{\""
                                + ID
                                + "\":{\"description\":\"first claim\",\"unknown\":1.2300e+9999},"
                                + "\""
                                + ID
                                + "\":{\"description\":\"second claim\",\"removal\":\"Unsafe\"}}")
                        .getBytes(StandardCharsets.UTF_8);
        String pin = Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        int[] calls = {0};
        PackageKnowledgeBackend backend =
                new PackageKnowledgeBackend(
                        () -> {
                            calls[0]++;
                            return new ByteArrayInputStream(bytes);
                        },
                        catalogs.toFile(),
                        ID,
                        null,
                        pin,
                        subjects.toFile(),
                        descriptor);
        EvidenceRows rows = backend.open(backend.initialRequest(() -> false), () -> false);
        require(
                rows.size() == 4 && rows.metadata().getInt("exactIdRecordCount") == 2,
                "Exact-ID lookup hid a duplicate community occurrence or its fields");
        require(
                backend.provenance()
                        .getJSONObject("capturedApkAssociation")
                        .getString("packageId")
                        .equals(ID),
                "Context lost");
        JSONObject request =
                new JSONObject(PackageKnowledgeBackend.query("record", ID, "", "", false))
                        .put("recordIndex", 0);
        StringWriter record = new StringWriter();
        backend.writeText(request.toString(), record, () -> false);
        Files.writeString(root.resolve("community-record.json"), record.toString());
        require(record.toString().contains("1.2300e+9999"), "Community numeric token changed");
        String retained = backend.retentionState();
        File catalogFile = backend.byteSource();
        backend.close(true);
        PackageKnowledgeBackend wrong =
                new PackageKnowledgeBackend(
                        () -> {
                            throw new AssertionError("No source reread");
                        },
                        catalogs.toFile(),
                        "wrong.id",
                        retained,
                        pin,
                        subjects.toFile(),
                        null);
        refused(() -> wrong.open(wrong.initialRequest(() -> false), () -> false));
        wrong.close();
        require(
                catalogFile.isFile() && subjectFile.isFile(),
                "Failed candidate restoration deleted existing captures");
        PackageKnowledgeBackend restored =
                new PackageKnowledgeBackend(
                        () -> {
                            throw new AssertionError("No source reread");
                        },
                        catalogs.toFile(),
                        ID,
                        retained,
                        pin,
                        subjects.toFile(),
                        null);
        require(
                restored.open(restored.initialRequest(() -> false), () -> false).size() == 4,
                "Retained claims changed");
        require(calls[0] == 1, "Catalog was reread on restoration");
        StringWriter association = new StringWriter();
        restored.writeText(
                PackageKnowledgeBackend.query("subject", ID, "", "", false),
                association,
                () -> false);
        require(
                association.toString().equals(streamed.toString()),
                "Complete original association stream changed");
        restored.close();
        require(
                !subjectFile.exists() && !catalogFile.exists(),
                "Final consumer did not clean its independent captures");
        System.out.println(
                "APK association strict identity/ownership/UTF-16/budget/cancellation, duplicate"
                        + " claims, source-free restore and failed-restore preservation passed.");
    }
}
