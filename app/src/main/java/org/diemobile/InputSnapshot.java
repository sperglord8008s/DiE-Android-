// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/** An explicitly owned private byte snapshot; display names never become paths. */
final class InputSnapshot implements AutoCloseable {
    final File file;
    final long size;
    final String sha256;

    private InputSnapshot(File file, long size, String sha256) {
        this.file = file;
        this.size = size;
        this.sha256 = sha256;
    }

    static InputSnapshot acquire(
            File source, File directory, long maximum, BooleanSupplier cancelled) throws Exception {
        EvidenceRows.check(cancelled);
        long size = source.length();
        if (!source.isFile() || size < 0 || size > maximum)
            throw new IOException(
                    "Input unavailable or exceeds the " + maximum + "-byte snapshot budget");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create private input snapshot directory");
        File output = File.createTempFile("analysis-input-", ".snapshot", directory);
        boolean success = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (InputStream in = new FileInputStream(source);
                    OutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[65536];
                while (copied < size) {
                    EvidenceRows.check(cancelled);
                    int count = in.read(buffer, 0, (int) Math.min(buffer.length, size - copied));
                    if (count < 0)
                        throw new EOFException("Input shortened during snapshot acquisition");
                    out.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    copied += count;
                }
                if (in.read() >= 0 || source.length() != size)
                    throw new IOException("Input length changed during snapshot acquisition");
            }
            EvidenceRows.check(cancelled);
            success = true;
            return new InputSnapshot(output, size, Inspector.toHex(digest.digest()));
        } finally {
            if (!success) output.delete();
        }
    }

    /** Caller owns the content stream. Unknown-length providers are bounded while copying. */
    static InputSnapshot acquire(
            InputStream input, File directory, long maximum, BooleanSupplier cancelled)
            throws Exception {
        EvidenceRows.check(cancelled);
        if (maximum < 0) throw new IOException("Invalid stream snapshot budget");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create private input snapshot directory");
        File output = File.createTempFile("analysis-input-", ".snapshot", directory);
        boolean success = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (OutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    EvidenceRows.check(cancelled);
                    if (count == 0) continue;
                    if (count > maximum - copied)
                        throw new IOException(
                                "Content stream exceeds the " + maximum + "-byte snapshot budget");
                    out.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    copied += count;
                }
            }
            EvidenceRows.check(cancelled);
            success = true;
            return new InputSnapshot(output, copied, Inspector.toHex(digest.digest()));
        } finally {
            if (!success) output.delete();
        }
    }

    static InputSnapshot restore(
            File input, File directory, long size, String sha256, BooleanSupplier cancelled)
            throws Exception {
        if (size < 0
                || sha256 == null
                || !sha256.matches("[0-9a-f]{64}")
                || !input.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())
                || !input.getName().startsWith("analysis-input-")
                || !input.getName().endsWith(".snapshot"))
            throw new IOException("Invalid private input restoration descriptor");
        InputSnapshot snapshot = new InputSnapshot(input, size, sha256);
        snapshot.checkIdentity(cancelled);
        return snapshot;
    }

    void checkIdentity(BooleanSupplier cancelled) throws Exception {
        EvidenceRows.check(cancelled);
        if (!file.isFile() || file.length() != size)
            throw new IOException("Owned input snapshot expired or changed length");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long read = 0;
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                EvidenceRows.check(cancelled);
                if (count > size - read)
                    throw new IOException("Owned input snapshot grew during identity check");
                digest.update(buffer, 0, count);
                read += count;
            }
        }
        if (read != size
                || file.length() != size
                || !sha256.equals(Inspector.toHex(digest.digest())))
            throw new IOException("Owned input snapshot SHA-256 changed");
        EvidenceRows.check(cancelled);
    }

    @Override
    public void close() {
        file.delete();
    }
}
