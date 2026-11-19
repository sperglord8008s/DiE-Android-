// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class OwnedContentTest {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        root.mkdirs();
        byte[] original = new byte[3 * 65536 + 17];
        new Random(9017).nextBytes(original);
        File selected = new File(root, "selected.blob");
        Files.write(selected.toPath(), original);
        File snapshots = new File(root, "sources"),
                derived = new File(root, "derived"),
                exports = new File(root, "exports");
        derived.mkdirs();
        exports.mkdirs();
        try (InputSnapshot input =
                InputSnapshot.acquire(selected, snapshots, original.length, () -> false)) {
            FileRange range = new FileRange(input.size, 7, original.length - 19);
            byte[] expected = Arrays.copyOfRange(original, 7, original.length - 12);
            try (InputSnapshot preview =
                    OwnedContent.stageRange(input, range, derived, () -> false)) {
                if (!Arrays.equals(expected, Files.readAllBytes(preview.file.toPath())))
                    throw new AssertionError("Wrong preview range");
                PreparedExport exported =
                        OwnedContent.exportRange(
                                input, range, exports, "recovered.bin", () -> false);
                input.checkIdentity(() -> false);
                try {
                    if (!Arrays.equals(expected, Files.readAllBytes(exported.file.toPath())))
                        throw new AssertionError("Wrong exported range");
                    input.close();
                    ByteArrayOutputStream copy = new ByteArrayOutputStream();
                    exported.copyTo(copy, () -> false);
                    if (!Arrays.equals(expected, copy.toByteArray()))
                        throw new AssertionError("Export lost identity after parent expiry");
                    preview.checkIdentity(() -> false);
                } finally {
                    exported.discard();
                }
            }
        }
        try (InputSnapshot input =
                InputSnapshot.acquire(selected, snapshots, original.length, () -> false)) {
            FileRange whole = new FileRange(input.size, 0, input.size);
            try {
                OwnedContent.stageRange(
                        input,
                        whole,
                        derived,
                        () -> {
                            File[] files = derived.listFiles();
                            return files != null
                                    && Arrays.stream(files).anyMatch(file -> file.length() > 0);
                        });
                throw new AssertionError("Cancelled acquisition succeeded");
            } catch (InterruptedIOException expected) {
            }
            if (derived.list().length != 0) throw new AssertionError("Cancelled bytes leaked");
            AtomicBoolean changed = new AtomicBoolean();
            try {
                OwnedContent.exportRange(
                        input,
                        whole,
                        exports,
                        "changed.bin",
                        () -> {
                            File[] files = exports.listFiles();
                            if (files != null
                                    && Arrays.stream(files).anyMatch(file -> file.length() > 0)
                                    && changed.compareAndSet(false, true)) {
                                try (RandomAccessFile mutate =
                                        new RandomAccessFile(input.file, "rw")) {
                                    mutate.seek(3);
                                    mutate.write(original[3] ^ 1);
                                } catch (IOException error) {
                                    throw new UncheckedIOException(error);
                                }
                            }
                            return false;
                        });
                throw new AssertionError("Changed parent was accepted");
            } catch (IOException expected) {
            }
            if (!changed.get() || exports.list().length != 0)
                throw new AssertionError("Changed export did not discard staged bytes");
        }
        if (!Arrays.equals(original, Files.readAllBytes(selected.toPath())))
            throw new AssertionError("Original selected file changed");
        System.out.println(
                "Owned content passed: exact preview/export bytes, independent lifetimes,"
                    + " cancellation cleanup, same-length parent mutation refusal and unchanged"
                    + " selected source.");
    }
}
