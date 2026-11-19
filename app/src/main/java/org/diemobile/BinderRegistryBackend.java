// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Captured normal-UID registry evidence; navigation/recreation never reruns the OS command. */
final class BinderRegistryBackend extends InvestigationBackend {
    interface Reference {
        InputStream open() throws Exception;
    }

    static final long MAX_CAPTURE_BYTES = 4L * 1024 * 1024;
    private final File input, directory, ownedCaptures;
    private final String expectedSha;
    private final long expectedSize;
    private final Reference reference;
    private InputSnapshot envelope, raw;
    private BinderRegistry registry;

    BinderRegistryBackend(
            File input, File directory, File ownedCaptures, String expectedSha, long expectedSize) {
        this(input, directory, ownedCaptures, expectedSha, expectedSize, null);
    }

    BinderRegistryBackend(
            File input,
            File directory,
            File ownedCaptures,
            String expectedSha,
            long expectedSize,
            Reference reference) {
        this.input = input;
        this.directory = directory;
        this.ownedCaptures = ownedCaptures;
        this.expectedSha = expectedSha;
        this.expectedSize = expectedSize;
        this.reference = reference;
    }

    private void acquire(BooleanSupplier stop) throws Exception {
        if (registry != null) return;
        InputSnapshot captured = InputSnapshot.acquire(input, directory, MAX_CAPTURE_BYTES, stop),
                output = null;
        boolean success = false;
        try {
            if (expectedSize < 0
                    || captured.size != expectedSize
                    || expectedSha == null
                    || !captured.sha256.equals(expectedSha))
                throw new IOException("Binder capture differs from its selected byte identity");
            String encoded =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(
                                    java.nio.ByteBuffer.wrap(
                                            Files.readAllBytes(captured.file.toPath())))
                            .toString();
            BinderRegistry parsed = new BinderRegistry(new JSONObject(encoded), stop);
            output =
                    InputSnapshot.acquire(
                            new ByteArrayInputStream(parsed.output),
                            directory,
                            BinderRegistry.MAX_BYTES,
                            stop);
            captured.checkIdentity(stop);
            output.checkIdentity(stop);
            envelope = captured;
            raw = output;
            registry = parsed;
            success = true;
        } finally {
            if (!success) {
                captured.close();
                if (output != null) output.close();
            }
        }
    }

    @Override
    String initialRequest(BooleanSupplier stop) throws Exception {
        acquire(stop);
        return registry.metadata().getBoolean("consistentWithReportedList")
                ? "registrations"
                : "summary";
    }

    @Override
    EvidenceRows open(String request, BooleanSupplier stop) throws Exception {
        acquire(stop);
        List<JSONObject> records;
        switch (request) {
            case "registrations":
                records = registry.registrations;
                break;
            case "diagnostics":
                records = registry.diagnostics;
                break;
            case "all":
                records = registry.lines;
                break;
            case "summary":
                records = Collections.singletonList(registry.metadata());
                break;
            default:
                throw new IOException("Unknown Binder registry evidence scope");
        }
        return new EvidenceRows(
                "binder-registry-" + request, "Captured Binder registry • " + request) {
            @Override
            int size() {
                return records.size();
            }

            @Override
            JSONObject metadata() throws Exception {
                return registry.metadata();
            }

            @Override
            Row row(int index, BooleanSupplier cancelled) throws Exception {
                EvidenceRows.check(cancelled);
                if (index < 0 || index >= records.size())
                    throw new IOException("Binder record outside captured scope");
                JSONObject row = new JSONObject(records.get(index).toString());
                String label, detail;
                if (row.has("serviceName")) {
                    label =
                            "Registry ordinal "
                                    + row.getLong("registryIndex")
                                    + " • "
                                    + row.getString("serviceName");
                    String descriptor = row.getString("interfaceDescriptor");
                    detail =
                            (descriptor.isEmpty() ? "<empty descriptor>" : preview(descriptor, 256))
                                    + " • "
                                    + row.getString("interfaceObservation");
                } else if (row.has("commandSucceeded")) {
                    JSONObject capture = row.getJSONObject("captureMetadata");
                    label =
                            (row.getBoolean("commandSucceeded")
                                            ? "Command exited successfully"
                                            : "Command failed or incomplete")
                                    + " • "
                                    + row.getInt("parsedRegistrationRows")
                                    + " parsed registration observations";
                    detail =
                            "Exit="
                                    + capture.opt("exitCode")
                                    + " • output complete="
                                    + row.getBoolean("outputComplete")
                                    + " • list consistent="
                                    + row.getBoolean("consistentWithReportedList")
                                    + " • diagnostics="
                                    + row.getInt("diagnosticRows")
                                    + (capture.optString("captureError").isEmpty()
                                            ? ""
                                            : " • "
                                                    + preview(
                                                            capture.getString("captureError"),
                                                            512));
                } else {
                    label =
                            "Output line "
                                    + row.getInt("lineIndex")
                                    + " • "
                                    + row.getString("kind");
                    detail =
                            row.isNull("rawText")
                                    ? "Bytes: " + preview(row.optString("rawHex"), 64)
                                    : preview(row.getString("rawText"), 512);
                    if (row.has("diagnostic")) detail += " • " + row.getString("diagnostic");
                }
                label = preview(label, 256);
                return new Row(
                        index,
                        label,
                        detail,
                        row.has("physicalOffset") ? row.getLong("physicalOffset") : null,
                        row.has("sizeBytes") ? row.getLong("sizeBytes") : null,
                        row);
            }
        };
    }

    private static String preview(String value, int codePoints) {
        return value.codePointCount(0, value.length()) <= codePoints
                ? value
                : value.substring(0, value.offsetByCodePoints(0, codePoints))
                        + "… (complete value in fields)";
    }

    @Override
    List<Link> roots(String request, BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        List<Link> links =
                new ArrayList<>(
                        Arrays.asList(
                                Link.view("Parsed registration observations", "registrations"),
                                Link.view("Every captured output line / original order", "all"),
                                Link.view(
                                        "Command diagnostics / unparsed and invalid-UTF-8 lines",
                                        "diagnostics"),
                                Link.view(
                                        "Capture identity / command status / visibility / limits",
                                        "summary"),
                                Link.blob(
                                        "Export exact captured output bytes (may be an incomplete"
                                                + " prefix)",
                                        "output",
                                        "service-list-output.bin"),
                                Link.blob(
                                        "Export original complete capture envelope",
                                        "capture",
                                        "binder-registry-capture.json")));
        if (reference != null)
            links.add(
                    Link.text(
                            "Pinned AOSP command-output contract / original source",
                            "contract",
                            "android-15-service.cpp"));
        return links;
    }

    @Override
    List<Link> related(
            String request, EvidenceRows rows, EvidenceRows.Row row, BooleanSupplier stop)
            throws Exception {
        EvidenceRows.check(stop);
        JSONObject value = row.json();
        if (!value.has("lineIndex")) return Collections.emptyList();
        return Collections.singletonList(
                Link.jump(
                        "Original captured output line / byte coordinates",
                        "all",
                        value.getInt("lineIndex")));
    }

    @Override
    JSONObject provenance() throws Exception {
        return new JSONObject()
                .put("engine", "DiE Android Binder registry parser")
                .put("parserVersion", 1)
                .put(
                        "input",
                        new JSONObject()
                                .put("sizeBytes", raw.size)
                                .put("sha256", raw.sha256)
                                .put("coordinateDomain", "captured command output bytes"))
                .put(
                        "captureEnvelope",
                        new JSONObject()
                                .put("sizeBytes", envelope.size)
                                .put("sha256", envelope.sha256))
                .put("sourceContractRevision", BinderRegistry.REFERENCE_REVISION)
                .put("sourceContractSha256", BinderRegistry.REFERENCE_SHA256)
                .put("networkUsed", false)
                .put("inspectedProgramsExecuted", false)
                .put("privilegedBridgeUsed", false);
    }

    @Override
    File byteSource() {
        return raw == null ? input : raw.file;
    }

    @Override
    String sourceName() {
        return "Captured service-list output";
    }

    @Override
    String help(String request) {
        return "Binder is Android's interprocess call mechanism. These are names returned by the"
                + " platform service manager and subsequent interface-descriptor queries as the"
                + " app's normal UID. They are separate from manifest service declarations,"
                + " process identity and observed client use. Empty descriptors cannot"
                + " distinguish lookup failure from an empty interface name. Names and"
                + " descriptor queries are not atomic. UID/SELinux, OS version and timing"
                + " restrict visibility; a matching reported count does not verify global"
                + " completeness. Errors and unknown lines remain diagnostics. Byte offsets"
                + " belong only to the captured output text; registry indices are not addresses."
                + " Root views expose every raw line, status, original output and"
                + " capture-envelope exports.";
    }

    @Override
    byte[] binaryValue(String request, BooleanSupplier stop) throws Exception {
        acquire(stop);
        InputSnapshot selected =
                request.equals("output") ? raw : request.equals("capture") ? envelope : null;
        if (selected == null) throw new IOException("Unknown Binder byte export");
        selected.checkIdentity(stop);
        return Files.readAllBytes(selected.file.toPath());
    }

    @Override
    void writeText(String request, Writer output, BooleanSupplier stop) throws Exception {
        if (!request.equals("contract") || reference == null)
            throw new IOException("Unknown Binder text export");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = reference.open()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                EvidenceRows.check(stop);
                if (count > 65536 - bytes.size())
                    throw new IOException("Service reference exceeds 64 KiB");
                bytes.write(buffer, 0, count);
            }
        }
        byte[] source = bytes.toByteArray();
        String sha =
                Inspector.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(source));
        if (!sha.equals(BinderRegistry.REFERENCE_SHA256))
            throw new IOException("Service reference source identity differs");
        output.write(new String(source, StandardCharsets.UTF_8));
    }

    @Override
    public void close(boolean retainInputsForRecreation) throws Exception {
        if (envelope != null) envelope.close();
        if (raw != null) raw.close();
        if (!retainInputsForRecreation
                && ownedCaptures != null
                && input.getCanonicalFile().getParentFile().equals(ownedCaptures.getCanonicalFile())
                && input.getName().startsWith("binder-command-")
                && input.getName().endsWith(".json")) Files.deleteIfExists(input.toPath());
    }

    @Override
    public void close() throws Exception {
        close(false);
    }
}
