// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Parses captured service-list bytes. Diagnostics never become Binder registrations. */
final class BinderRegistry {
    static final String SCHEMA = "org.diemobile.binder-registry-capture";
    static final String REFERENCE_REVISION = "f7274fca5e36082674740bc6c976f73c4578d009";
    static final String REFERENCE_SHA256 =
            "b559db30c430011667ede52a1ebb4b05a2c474bbe4df85d47c398298191bab94";
    static final int MAX_BYTES = 1024 * 1024, MAX_LINES = 20000;
    private static final Pattern HEADER = Pattern.compile("Found ([0-9]+) services:");
    final JSONObject capture;
    final byte[] output;
    final List<JSONObject> lines = new ArrayList<>(),
            registrations = new ArrayList<>(),
            diagnostics = new ArrayList<>();
    final String outputSha256;
    private final JSONObject summary;

    static JSONObject envelope(BoundedProcessOutput.Result result, JSONObject context)
            throws Exception {
        return new JSONObject()
                .put("schema", SCHEMA)
                .put("schemaVersion", 1)
                .put("command", new JSONArray().put("/system/bin/service").put("list"))
                .put("context", context)
                .put("stderrMerged", true)
                .put("exitCode", result.exitCode == null ? JSONObject.NULL : result.exitCode)
                .put("outputComplete", result.complete)
                .put("timedOut", result.timedOut)
                .put("outputCapped", result.capped)
                .put("captureError", result.error)
                .put("outputSizeBytes", result.bytes.length)
                .put(
                        "outputSha256",
                        Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(result.bytes)))
                .put("outputBase64", Base64.getEncoder().encodeToString(result.bytes));
    }

    BinderRegistry(JSONObject capture, BooleanSupplier stop) throws Exception {
        this.capture = capture;
        JSONObject context = capture.getJSONObject("context");
        integer(context.get("callerUid"), Integer.MAX_VALUE);
        integer(context.get("callerPid"), Integer.MAX_VALUE);
        integer(context.get("androidApi"), Integer.MAX_VALUE);
        if (booleanValue(context, "privilegedBridgeUsed"))
            throw new IOException("This registry adapter accepts no privileged bridge capture");
        if (!capture.getString("schema").equals(SCHEMA)
                || integer(capture.get("schemaVersion"), 1) != 1
                || !capture.getJSONArray("command")
                        .toString()
                        .equals(new JSONArray().put("/system/bin/service").put("list").toString())
                || !booleanValue(capture, "stderrMerged"))
            throw new IOException("Invalid service-list capture contract");
        String encoded = capture.getString("outputBase64");
        if (encoded.length() > 4 * ((MAX_BYTES + 2) / 3))
            throw new IOException("Binder output exceeds 1 MiB");
        try {
            output = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid captured output encoding", invalid);
        }
        if (output.length > MAX_BYTES
                || integer(capture.get("outputSizeBytes"), MAX_BYTES) != output.length)
            throw new IOException("Captured Binder output length differs");
        outputSha256 = Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(output));
        if (!outputSha256.equals(capture.getString("outputSha256")))
            throw new IOException("Captured Binder output SHA-256 differs");
        boolean complete = booleanValue(capture, "outputComplete"),
                timedOut = booleanValue(capture, "timedOut"),
                capped = booleanValue(capture, "outputCapped");
        if (complete
                && (timedOut
                        || capped
                        || !capture.getString("captureError").isEmpty()
                        || capture.isNull("exitCode")))
            throw new IOException("Incomplete command presented as complete output");
        Long exit =
                capture.isNull("exitCode")
                        ? null
                        : integer(capture.get("exitCode"), Integer.MAX_VALUE);
        Set<Long> indices = new HashSet<>();
        Set<String> names = new HashSet<>();
        int headers = 0, duplicateIndices = 0, duplicateNames = 0;
        Long reported = null;
        for (int offset = 0; offset < output.length; ) {
            EvidenceRows.check(stop);
            if (lines.size() >= MAX_LINES)
                throw new IOException(
                        "Binder output exceeds 20,000 lines; no complete parsed view");
            int end = offset;
            while (end < output.length && output[end] != '\n') end++;
            int next = end < output.length ? end + 1 : end;
            int contentEnd =
                    end < output.length && end > offset && output[end - 1] == '\r' ? end - 1 : end;
            JSONObject row =
                    new JSONObject()
                            .put("lineIndex", lines.size())
                            .put("physicalOffset", offset)
                            .put("sizeBytes", next - offset)
                            .put("coordinateDomain", "captured command output bytes")
                            .put(
                                    "newline",
                                    end == output.length
                                            ? "none"
                                            : contentEnd < end ? "CRLF" : "LF");
            String text = null;
            try {
                text =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(output, offset, contentEnd - offset))
                                .toString();
            } catch (CharacterCodingException invalid) {
                row.put("rawHex", Inspector.toHex(Arrays.copyOfRange(output, offset, next)));
            }
            row.put("rawText", text == null ? JSONObject.NULL : text);
            String kind = "diagnostic",
                    reason =
                            text == null
                                    ? "Invalid UTF-8; exact bytes retained"
                                    : "Unrecognized command output; no registration inferred";
            if (text != null && text.isEmpty()) {
                kind = "blank";
                reason = "";
            }
            if (text != null) {
                Matcher header = HEADER.matcher(text);
                if (header.matches()) {
                    kind = "header";
                    headers++;
                    row.put("reportedCountDecimal", header.group(1));
                    try {
                        reported = Long.parseLong(header.group(1));
                    } catch (NumberFormatException overflow) {
                        kind = "diagnostic";
                        reason = "Reported count exceeds signed 64 bits";
                    }
                } else {
                    int tab = text.indexOf('\t'),
                            separator = text.indexOf(": [", Math.max(0, tab + 1));
                    if (tab > 0
                            && text.substring(0, tab).matches("[0-9]+")
                            && separator > tab + 1
                            && text.endsWith("]")
                            && text.indexOf(": [", separator + 3) < 0) {
                        try {
                            long index = Long.parseLong(text.substring(0, tab));
                            String name = text.substring(tab + 1, separator),
                                    descriptor = text.substring(separator + 3, text.length() - 1);
                            kind = "registration";
                            reason = "";
                            row.put("registryIndex", index)
                                    .put("serviceName", name)
                                    .put("interfaceDescriptor", descriptor)
                                    .put(
                                            "interfaceObservation",
                                            descriptor.isEmpty()
                                                    ? "Empty descriptor: lookup unavailable or"
                                                            + " interface descriptor empty;"
                                                            + " liveness/implementation unknown"
                                                    : "Interface descriptor returned during this"
                                                            + " command; no permission-to-invoke or"
                                                            + " runtime-use claim");
                            if (!indices.add(index)) {
                                duplicateIndices++;
                                row.put("duplicateRegistryIndex", true);
                            }
                            if (!names.add(name)) {
                                duplicateNames++;
                                row.put("duplicateServiceName", true);
                            }
                            registrations.add(row);
                        } catch (NumberFormatException overflow) {
                            reason = "Registry index exceeds signed 64 bits";
                        }
                    }
                }
            }
            row.put("kind", kind);
            if (!reason.isEmpty() && !kind.equals("header")) row.put("diagnostic", reason);
            if (kind.equals("diagnostic")) diagnostics.add(row);
            lines.add(row);
            offset = next;
        }
        boolean sequential = indices.size() == registrations.size();
        for (long i = 0; i < registrations.size(); i++)
            if (!indices.contains(i)) sequential = false;
        boolean success = complete && exit != null && exit == 0;
        summary =
                new JSONObject()
                        .put("schema", "org.diemobile.binder-registry-evidence")
                        .put("schemaVersion", 1)
                        .put("parserVersion", 1)
                        .put("sourceContractRevision", REFERENCE_REVISION)
                        .put("sourceContractSha256", REFERENCE_SHA256)
                        .put("outputSizeBytes", output.length)
                        .put("outputSha256", outputSha256)
                        .put("commandSucceeded", success)
                        .put("outputComplete", complete)
                        .put("headerCount", headers)
                        .put(
                                "reportedServiceCount",
                                headers != 1 || reported == null ? JSONObject.NULL : reported)
                        .put("parsedRegistrationRows", registrations.size())
                        .put("diagnosticRows", diagnostics.size())
                        .put("outputLines", lines.size())
                        .put("duplicateRegistryIndices", duplicateIndices)
                        .put("duplicateServiceNames", duplicateNames)
                        .put(
                                "consistentWithReportedList",
                                success
                                        && headers == 1
                                        && reported != null
                                        && reported == registrations.size()
                                        && diagnostics.isEmpty()
                                        && duplicateNames == 0
                                        && sequential)
                        .put("atomicRegistrySnapshot", false)
                        .put("globalServiceVisibilityVerified", false)
                        .put(
                                "scope",
                                "Observed normal-UID platform service-list output; not manifest"
                                    + " services, client usage, process identity or permission to"
                                    + " invoke. List names and subsequent descriptor queries are"
                                    + " not atomic.");
        JSONObject metadata = new JSONObject();
        for (Iterator<String> keys = capture.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!key.equals("outputBase64")) metadata.put(key, capture.get(key));
        }
        summary.put("captureMetadata", metadata)
                .put(
                        "rawOutputEncoding",
                        "Exact captured bytes exported separately; capped/timed-out captures may be"
                                + " prefixes");
    }

    JSONObject metadata() throws Exception {
        return new JSONObject(summary.toString());
    }

    static long integer(Object value, long maximum) throws IOException {
        if (!(value instanceof Number) || !value.toString().matches("(?:0|[1-9][0-9]{0,18})"))
            throw new IOException("Expected exact nonnegative capture integer");
        try {
            long result = Long.parseLong(value.toString());
            if (result > maximum) throw new IOException("Capture integer exceeds its bound");
            return result;
        } catch (NumberFormatException overflow) {
            throw new IOException("Capture integer exceeds signed 64 bits", overflow);
        }
    }

    private static boolean booleanValue(JSONObject object, String name) throws Exception {
        if (!(object.get(name) instanceof Boolean))
            throw new IOException("Expected capture boolean: " + name);
        return object.getBoolean(name);
    }
}
