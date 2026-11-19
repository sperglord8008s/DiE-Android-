// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Trusted child JVMs exercise process IO; captured command output is never executed. */
public final class BinderRegistryTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void fails(Action action) throws Exception {
        try {
            action.run();
        } catch (IOException | JSONException expected) {
            return;
        }
        throw new AssertionError("Invalid Binder evidence accepted");
    }

    static JSONObject context() throws Exception {
        return new JSONObject()
                .put("callerUid", 10001)
                .put("callerPid", 123)
                .put("androidApi", 35)
                .put("privilegedBridgeUsed", false)
                .put("future", "\udfff\u0000");
    }

    static JSONObject envelope(byte[] bytes, int exit, boolean complete) throws Exception {
        return BinderRegistry.envelope(
                new BoundedProcessOutput.Result(bytes, exit, complete, false, false, ""),
                context());
    }

    static String sha(byte[] bytes) throws Exception {
        return Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Process child(String mode) throws Exception {
        return new ProcessBuilder(
                        new File(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        BinderRegistryTest.class.getName(),
                        "child",
                        mode)
                .redirectErrorStream(true)
                .start();
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("child")) {
            if (args[1].equals("large")) {
                System.out.write(new byte[8192]);
                System.out.flush();
            } else if (args[1].equals("wait")) {
                Thread.sleep(5000);
            } else if (args[1].equals("error")) {
                System.err.print("Permission denial\n");
                System.exit(7);
            } else
                System.out.print(
                        "Found 2 services:\n"
                                + "0\tactivity: [android.app.IActivityManager]\n"
                                + "1\t媒体: []\n");
            return;
        }
        // JAVA_TOOL_OPTIONS may produce a diagnostic line in child stderr. That
        // output is intentionally retained and cannot become a registration.
        BoundedProcessOutput.Result actual =
                BoundedProcessOutput.capture(child("normal"), 1024 * 1024, 5000, () -> false);
        require(actual.complete && actual.exitCode == 0, "Trusted normal child capture failed");
        BinderRegistry real =
                new BinderRegistry(BinderRegistry.envelope(actual, context()), () -> false);
        require(real.registrations.size() == 2, "Actual process bytes did not parse");
        Process large = child("large");
        BoundedProcessOutput.Result capped =
                BoundedProcessOutput.capture(large, 128, 5000, () -> false);
        require(
                !large.isAlive()
                        && capped.capped
                        && !capped.complete
                        && capped.bytes.length == 128
                        && !capped.error.isEmpty(),
                "Output cap/ownership failed");
        Process slow = child("wait");
        BoundedProcessOutput.Result timeout =
                BoundedProcessOutput.capture(slow, 1024, 100, () -> false);
        require(
                timeout.timedOut && !timeout.complete && !slow.isAlive(),
                "Timeout did not settle owned child");
        Process cancelled = child("wait");
        fails(() -> BoundedProcessOutput.capture(cancelled, 1024, 5000, () -> true));
        require(!cancelled.isAlive(), "Cancellation leaked the owned process");
        BoundedProcessOutput.Result denied =
                BoundedProcessOutput.capture(child("error"), 1024 * 1024, 5000, () -> false);
        BinderRegistry denial =
                new BinderRegistry(BinderRegistry.envelope(denied, context()), () -> false);
        require(
                denied.complete
                        && denied.exitCode == 7
                        && denial.registrations.isEmpty()
                        && !denial.metadata().getBoolean("commandSucceeded"),
                "Command errors became service registrations");

        byte[] ordinary =
                "Found 2 services:\r\n0\tactivity: [android.app.IActivityManager]\r\n1\t媒体: []"
                        .getBytes(StandardCharsets.UTF_8);
        JSONObject capture = envelope(ordinary, 0, true);
        BinderRegistry parsed = new BinderRegistry(capture, () -> false);
        require(
                parsed.registrations.size() == 2
                        && parsed.metadata().getBoolean("consistentWithReportedList"),
                "Valid reference-shaped records lost");
        require(
                parsed.registrations.get(1).getString("interfaceDescriptor").isEmpty(),
                "Empty interface invented liveness");
        for (JSONObject row : parsed.lines) {
            int start = row.getInt("physicalOffset"), count = row.getInt("sizeBytes");
            String line =
                    new String(
                            Arrays.copyOfRange(ordinary, start, start + count),
                            StandardCharsets.UTF_8);
            require(
                    line.startsWith(row.getString("rawText")),
                    "Raw output byte coordinates differ");
        }
        ByteArrayOutputStream malformed = new ByteArrayOutputStream();
        malformed.write("Found 2 services:\n0\tleft: [a]\n".getBytes(StandardCharsets.UTF_8));
        malformed.write(new byte[] {(byte) 0xff, '\n'});
        malformed.write("1\tright: [b]\nPermission denied\n".getBytes(StandardCharsets.UTF_8));
        BinderRegistry bad =
                new BinderRegistry(envelope(malformed.toByteArray(), 0, true), () -> false);
        require(
                bad.registrations.size() == 2
                        && bad.diagnostics.size() == 2
                        && !bad.metadata().getBoolean("consistentWithReportedList"),
                "Unknown/invalid output erased or promoted");
        String repeated = "Found 2 services:\n0\tsame: [a]\n0\tsame: [b]\n";
        BinderRegistry duplicate =
                new BinderRegistry(
                        envelope(repeated.getBytes(StandardCharsets.UTF_8), 0, true), () -> false);
        require(
                duplicate.registrations.size() == 2
                        && duplicate.metadata().getInt("duplicateRegistryIndices") == 1
                        && duplicate.metadata().getInt("duplicateServiceNames") == 1
                        && !duplicate.metadata().getBoolean("consistentWithReportedList"),
                "Duplicate registry observations collapsed");
        BinderRegistry finalCr =
                new BinderRegistry(
                        envelope("Found 0 services:\r".getBytes(StandardCharsets.UTF_8), 0, true),
                        () -> false);
        require(
                finalCr.diagnostics.size() == 1
                        && finalCr.lines.get(0).getString("rawText").endsWith("\r"),
                "Final CR byte was removed without a CRLF terminator");
        BinderRegistry multipleHeaders =
                new BinderRegistry(
                        envelope(
                                "Found 0 services:\nFound 1 services:\n0\tname: [type]\n"
                                        .getBytes(StandardCharsets.UTF_8),
                                0,
                                true),
                        () -> false);
        require(
                multipleHeaders.metadata().isNull("reportedServiceCount")
                        && !multipleHeaders.metadata().getBoolean("consistentWithReportedList"),
                "Repeated headers were silently replaced by one count");
        BinderRegistry overflow =
                new BinderRegistry(
                        envelope(
                                "Found 18446744073709551615 services:\n"
                                        .getBytes(StandardCharsets.UTF_8),
                                0,
                                true),
                        () -> false);
        require(
                overflow.diagnostics.size() == 1
                        && overflow.lines
                                .get(0)
                                .getString("reportedCountDecimal")
                                .equals("18446744073709551615"),
                "Oversized decimal evidence was lost");
        JSONObject corrupt = new JSONObject(capture.toString());
        corrupt.put("outputSha256", "0".repeat(64));
        fails(() -> new BinderRegistry(corrupt, () -> false));
        JSONObject claimed = new JSONObject(capture.toString());
        claimed.put("timedOut", true);
        fails(() -> new BinderRegistry(claimed, () -> false));
        String excess = "\n".repeat(BinderRegistry.MAX_LINES + 1);
        fails(
                () ->
                        new BinderRegistry(
                                envelope(excess.getBytes(StandardCharsets.UTF_8), 0, true),
                                () -> false));

        File captures = new File(args[0], "captures");
        captures.mkdirs();
        File source = File.createTempFile("binder-command-", ".json", captures);
        StringWriter json = new StringWriter();
        EvidenceJson.write(json, capture, () -> false);
        byte[] envelopeBytes = json.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(source.toPath(), envelopeBytes);
        File snapshots = new File(args[0], "snapshots");
        BinderRegistryBackend backend =
                new BinderRegistryBackend(
                        source, snapshots, captures, sha(envelopeBytes), envelopeBytes.length);
        EvidenceRows rows = backend.open(backend.initialRequest(() -> false), () -> false);
        require(
                rows.size() == 2
                        && Arrays.equals(backend.binaryValue("output", () -> false), ordinary),
                "Provider changed exact bytes/records");
        StringWriter report = new StringWriter();
        rows.writeReport(report, backend.provenance(), "no matches", 0, 10, () -> false);
        require(
                new JSONObject(report.toString()).getJSONArray("rows").length() == 2,
                "Filter truncated scoped export");
        PreparedExport pending =
                PreparedExport.bytes(
                        new File(args[0], "exports"),
                        "captured.bin",
                        backend.binaryValue("output", () -> false),
                        () -> false);
        backend.close(true);
        require(source.isFile(), "Recreation destroyed the immutable capture source");
        try (BinderRegistryBackend restored =
                new BinderRegistryBackend(
                        source, snapshots, captures, sha(envelopeBytes), envelopeBytes.length)) {
            require(
                    restored.open(restored.initialRequest(() -> false), () -> false).size() == 2,
                    "Source could not restore without rerunning command");
            Files.write(source.toPath(), new byte[] {1});
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            pending.copyTo(bytes, () -> false);
            pending.discard();
            require(
                    Arrays.equals(ordinary, bytes.toByteArray()),
                    "Pending output changed after original source mutation");
        }
        require(
                !source.exists() && snapshots.list().length == 0,
                "Owned Binder snapshots/capture leaked");
        System.out.println(
                "Binder registry passed: trusted-process IO/timeout/cap/cancel/cleanup; exact"
                        + " Unicode/CRLF/invalid bytes, errors and duplicates; source identity,"
                        + " complete scoped exports and immutable recreation/picker ownership.");
    }
}
