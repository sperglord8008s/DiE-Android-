// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.content.Context;
import android.os.Build;

import org.json.*;

import java.io.*;
import java.nio.file.*;
import java.util.function.BooleanSupplier;

/** Runs only the fixed read-only platform command. Never accepts an executable or arguments. */
final class BinderServiceCapture {
    static File directory(Context context) {
        return new File(context.getCacheDir(), "binder-command-captures");
    }

    static final class Captured {
        final File file;
        final long size;
        final String sha256;

        Captured(File file, String sha256) {
            this.file = file;
            this.size = file.length();
            this.sha256 = sha256;
        }
    }

    static Captured capture(Context context, BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        File root = directory(context);
        Files.createDirectories(root.toPath());
        File[] retained =
                root.listFiles(
                        file ->
                                file.getName().startsWith("binder-command-")
                                        && file.getName().endsWith(".json"));
        if (retained == null || retained.length >= 8)
            throw new IOException(
                    "Eight private Binder captures already remain; close their viewers or clear app"
                            + " cache before capturing more");
        String started = java.time.Instant.now().toString();
        BoundedProcessOutput.Result result;
        try {
            Process process =
                    new ProcessBuilder("/system/bin/service", "list")
                            .redirectErrorStream(true)
                            .start();
            result = BoundedProcessOutput.capture(process, BinderRegistry.MAX_BYTES, 5000, stop);
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException unavailable) {
            result =
                    new BoundedProcessOutput.Result(
                            new byte[0], null, false, false, false, unavailable.toString());
        }
        EvidenceRows.check(stop);
        JSONObject platform =
                new JSONObject()
                        .put("startedAt", started)
                        .put("completedAt", java.time.Instant.now().toString())
                        .put("callerUid", android.os.Process.myUid())
                        .put("callerPid", android.os.Process.myPid())
                        .put("androidApi", Build.VERSION.SDK_INT)
                        .put("androidRelease", Build.VERSION.RELEASE)
                        .put("buildFingerprint", Build.FINGERPRINT)
                        .put("privilegedBridgeUsed", false)
                        .put("cooperativeCommandDeadlineMs", 5000)
                        .put("maximumOutputBytes", BinderRegistry.MAX_BYTES)
                        .put("cleanupGraceMs", 1500)
                        .put("installedToolBinaryIdentityVerified", false);
        JSONObject capture = BinderRegistry.envelope(result, platform);
        File output = File.createTempFile("binder-command-", ".json", root);
        boolean success = false;
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try {
            try (Writer writer =
                    new OutputStreamWriter(
                            new java.security.DigestOutputStream(
                                    new FileOutputStream(output), digest),
                            java.nio.charset.StandardCharsets.UTF_8)) {
                EvidenceJson.write(writer, capture, stop);
            }
            if (output.length() > BinderRegistryBackend.MAX_CAPTURE_BYTES)
                throw new IOException("Binder capture envelope exceeds 4 MiB");
            EvidenceRows.check(stop);
            success = true;
            return new Captured(output, Inspector.toHex(digest.digest()));
        } finally {
            if (!success) Files.deleteIfExists(output.toPath());
        }
    }

    private BinderServiceCapture() {}
}
