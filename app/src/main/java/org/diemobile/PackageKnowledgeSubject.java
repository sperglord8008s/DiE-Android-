// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.JSONObject;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/**
 * Independently owned evidence for an exact-ID community lookup, separate from community claims.
 */
final class PackageKnowledgeSubject implements AutoCloseable {
    static final String SCHEMA = "org.diemobile.package-knowledge-subject";
    static final long MAX_BYTES = 1024 * 1024;
    static final String MEANING =
            "The observed APK contains this one literal manifest package ID. Community records are"
                + " associated only by exact ID, including every duplicate source record. The"
                + " association does not verify publisher identity, SDK/version/device"
                + " applicability, runtime behavior or a removal recommendation. Following another"
                + " community ID does not associate that ID with the captured APK.";
    final InputSnapshot input;
    final String packageId;
    private final String encoded;

    private PackageKnowledgeSubject(InputSnapshot input, String encoded, String expectedId)
            throws Exception {
        JSONObject value = new JSONObject(encoded);
        validate(value);
        packageId = text(value, "packageId");
        if (expectedId != null && !expectedId.equals(packageId))
            throw new IOException(
                    "Captured APK declaration differs from the requested knowledge ID");
        this.input = input;
        this.encoded = encoded;
    }

    static PackageKnowledgeSubject capture(File directory, JSONObject value, BooleanSupplier stop)
            throws Exception {
        validate(value);
        InputSnapshot input = null;
        PreparedExport stage = null;
        boolean success = false;
        try {
            stage =
                    PreparedExport.stream(
                            directory,
                            "apk-knowledge-subject.json",
                            "application/json",
                            out -> EvidenceJson.write(out, value, stop),
                            stop);
            if (stage.size > MAX_BYTES)
                throw new IOException("APK knowledge association exceeds its 1 MiB budget");
            input = InputSnapshot.acquire(stage.file, directory, MAX_BYTES, stop);
            PackageKnowledgeSubject result = read(input, null, stop);
            success = true;
            return result;
        } finally {
            if (stage != null) stage.discard();
            if (!success && input != null) input.close();
        }
    }

    static PackageKnowledgeSubject restore(
            File directory, String descriptor, String expectedId, BooleanSupplier stop)
            throws Exception {
        if (descriptor == null || descriptor.length() > 2048)
            throw new IOException("APK knowledge association descriptor exceeds its budget");
        JSONObject saved = new JSONObject(descriptor);
        if (!SCHEMA.equals(text(saved, "schema")) || integer(saved, "schemaVersion") != 1)
            throw new IOException("Unsupported APK knowledge association descriptor");
        long size = integer(saved, "sizeBytes");
        if (size < 1 || size > MAX_BYTES)
            throw new IOException("APK knowledge association exceeds 1 MiB");
        InputSnapshot input =
                InputSnapshot.restore(
                        new File(text(saved, "path")),
                        directory,
                        size,
                        text(saved, "sha256"),
                        stop);
        // A failed restoration must not delete an existing owner's evidence.
        return read(input, expectedId, stop);
    }

    private static PackageKnowledgeSubject read(
            InputSnapshot input, String expectedId, BooleanSupplier stop) throws Exception {
        input.checkIdentity(stop);
        byte[] bytes = new byte[(int) input.size];
        try (InputStream source = new FileInputStream(input.file)) {
            int position = 0;
            while (position < bytes.length) {
                EvidenceRows.check(stop);
                int count = source.read(bytes, position, bytes.length - position);
                if (count < 0) throw new EOFException("Captured APK association shortened");
                position += count;
            }
            if (source.read() != -1) throw new IOException("Captured APK association grew");
        }
        String encoded =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
        PackageKnowledgeSubject result = new PackageKnowledgeSubject(input, encoded, expectedId);
        input.checkIdentity(stop);
        return result;
    }

    JSONObject json() throws Exception {
        return new JSONObject(encoded);
    }

    String descriptor() throws Exception {
        return new JSONObject()
                .put("schema", SCHEMA)
                .put("schemaVersion", 1)
                .put("path", input.file.getAbsolutePath())
                .put("sizeBytes", input.size)
                .put("sha256", input.sha256)
                .toString();
    }

    void write(Writer output, BooleanSupplier stop) throws Exception {
        input.checkIdentity(stop);
        try (Reader reader =
                new InputStreamReader(new FileInputStream(input.file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                EvidenceRows.check(stop);
                output.write(buffer, 0, count);
            }
        }
        input.checkIdentity(stop);
    }

    private static void validate(JSONObject value) throws Exception {
        if (!SCHEMA.equals(text(value, "schema"))
                || integer(value, "schemaVersion") != 1
                || !"apk-literal-package".equals(text(value, "kind")))
            throw new IOException("Unsupported captured APK knowledge subject");
        String id = text(value, "packageId");
        if (id.isEmpty() || id.length() > 1024)
            throw new IOException("Observed package ID must contain 1–1024 UTF-16 units");
        JSONObject apk = value.getJSONObject("capturedApk");
        if (!text(apk, "sha256").matches("[0-9a-f]{64}")
                || integer(apk, "sizeBytes") < 0
                || integer(apk, "sizeBytes") > FileRange.MAX_OPERATION_BYTES
                || !Boolean.TRUE.equals(apk.get("acquired"))
                || !Boolean.TRUE.equals(apk.get("manifestParsed"))
                || !Boolean.TRUE.equals(apk.get("identityStableDuringAnalysis")))
            throw new IOException("No complete stable captured APK identity for this association");
        JSONObject manifest = apk.getJSONObject("manifestInput");
        if (!text(manifest, "sha256").matches("[0-9a-f]{64}")
                || integer(manifest, "sizeBytes") < 1
                || integer(manifest, "sizeBytes") > 8L * 1024 * 1024)
            throw new IOException("No exact acquired manifest identity for this association");
        JSONObject declaration = value.getJSONObject("packageDeclaration");
        if (!"package".equals(text(declaration, "key"))
                || !"string".equals(text(declaration, "kind"))
                || !"literal".equals(text(declaration, "status"))
                || !id.equals(text(declaration, "value"))
                || !Boolean.FALSE.equals(declaration.get("defaultApplied"))
                || declaration.getJSONArray("originalAttributeRecords").length() != 1)
            throw new IOException("Association requires one original literal package attribute");
    }

    private static String text(JSONObject value, String key) throws Exception {
        Object field = value.get(key);
        if (!(field instanceof String))
            throw new IOException("APK association " + key + " is not text");
        return (String) field;
    }

    private static long integer(JSONObject value, String key) throws Exception {
        Object field = value.get(key);
        if (!(field instanceof Integer) && !(field instanceof Long))
            throw new IOException("APK association " + key + " is not an exact integer");
        return ((Number) field).longValue();
    }

    static void discard(File directory, String descriptor) {
        try {
            restore(directory, descriptor, null, () -> false).close();
        } catch (Exception ignored) {
            /* Never remove through an invalid descriptor. */
        }
    }

    @Override
    public void close() {
        input.close();
    }
}
