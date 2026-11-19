// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

/** Actual ZIP directory/payload fixtures; no Android, installation or execution. */
public final class ArchiveStoreTest {
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("die-archive-test");
        try {
            File snapshots = temporary.resolve("snapshots").toFile();
            byte[] payload = "DIE_ARCHIVE_CRC_PAYLOAD\0\u00e9".getBytes(StandardCharsets.UTF_8);
            File archive = zip(temporary.resolve("valid.zip"), Map.of("../escape.bin", payload, "empty", new byte[0], "folder/", new byte[0]), false);
            byte[] original = Files.readAllBytes(archive.toPath());
            ArchiveStore.Inventory inventory = ArchiveStore.read(archive, () -> false);
            require(inventory.members.size() == 3, "Directory lost entries");
            ArchiveStore.Member unsafe = member(inventory, "../escape.bin");
            ArchiveStore.Extracted extracted = ArchiveStore.extract(inventory, unsafe, snapshots, () -> false);
            require(extracted.file.getCanonicalFile().getParentFile().equals(snapshots.getCanonicalFile()), "Archive name became an output path");
            require(Arrays.equals(payload, Files.readAllBytes(extracted.file.toPath())), "Extracted bytes differ");
            require(extracted.sha256.equals(Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(payload))), "Extracted SHA-256 differs");
            require(!temporary.resolve("escape.bin").toFile().exists(), "Traversal escaped snapshot directory");
            ArchiveStore.Extracted empty = ArchiveStore.extract(inventory, member(inventory, "empty"), snapshots, () -> false);
            require(empty.size == 0 && empty.sha256.equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), "Empty payload lost");
            rejected(() -> ArchiveStore.extract(inventory, member(inventory, "folder/"), snapshots, () -> false));
            rejected(() -> ArchiveStore.extract(inventory, unsafe, snapshots, () -> true));
            rejected(() -> ArchiveStore.read(archive, () -> true));
            ArchiveStore.Inventory separate = ArchiveStore.read(archive, () -> false);
            rejected(() -> ArchiveStore.extract(inventory, separate.members.get(0), snapshots, () -> false));
            require(Arrays.equals(original, Files.readAllBytes(archive.toPath())), "Analysis modified the input");

            File deflated = zip(temporary.resolve("deflated.zip"), Map.of("data", payload), true);
            ArchiveStore.Inventory deflatedInventory = ArchiveStore.read(deflated, () -> false);
            ArchiveStore.Extracted decoded = ArchiveStore.extract(deflatedInventory, deflatedInventory.members.get(0), snapshots, () -> false);
            require(Arrays.equals(payload, Files.readAllBytes(decoded.file.toPath())), "Deflated content differs");
            int published = Objects.requireNonNull(snapshots.list()).length;

            File duplicate = zip(temporary.resolve("duplicate.zip"), Map.of("same-a.bin", payload, "same-b.bin", payload), false);
            byte[] duplicateBytes = Files.readAllBytes(duplicate.toPath()); replace(duplicateBytes, "same-b.bin", "same-a.bin"); Files.write(duplicate.toPath(), duplicateBytes);
            ArchiveStore.Inventory duplicateInventory = ArchiveStore.read(duplicate, () -> false);
            require(duplicateInventory.members.size() == 2 && duplicateInventory.members.get(0).occurrences == 2, "Duplicate names were hidden");
            rejected(() -> ArchiveStore.extract(duplicateInventory, duplicateInventory.members.get(0), snapshots, () -> false));

            File corrupt = zip(temporary.resolve("corrupt.zip"), Map.of("payload", payload), false);
            byte[] corruptBytes = Files.readAllBytes(corrupt.toPath()); int at = indexOf(corruptBytes, payload); require(at >= 0, "Stored fixture missing"); corruptBytes[at] ^= 1;
            Files.write(corrupt.toPath(), corruptBytes); ArchiveStore.Inventory corruptInventory = ArchiveStore.read(corrupt, () -> false);
            rejected(() -> ArchiveStore.extract(corruptInventory, corruptInventory.members.get(0), snapshots, () -> false));

            byte[] large = new byte[4 * 65536]; new Random(734).nextBytes(large);
            File cancellable = zip(temporary.resolve("cancel.zip"), Map.of("data", large), true);
            ArchiveStore.Inventory cancellableInventory = ArchiveStore.read(cancellable, () -> false); AtomicInteger checks = new AtomicInteger();
            rejected(() -> ArchiveStore.extract(cancellableInventory, cancellableInventory.members.get(0), snapshots, () -> checks.incrementAndGet() > 5));
            require(checks.get() > 5, "Cancellation fixture did not reach streaming I/O");

            File changed = zip(temporary.resolve("changed.zip"), Map.of("data", "one".getBytes(StandardCharsets.UTF_8)), false);
            ArchiveStore.Inventory changedInventory = ArchiveStore.read(changed, () -> false);
            zip(changed.toPath(), Map.of("data", "two".getBytes(StandardCharsets.UTF_8)), false);
            require(changed.length() == changedInventory.sourceSize, "Changed-directory fixture must retain file length");
            rejected(() -> ArchiveStore.extract(changedInventory, changedInventory.members.get(0), snapshots, () -> false));

            File unrelatedChange = zip(temporary.resolve("unrelated-change.zip"), Map.of("selected", payload, "unrelated", new byte[]{1}), false);
            ArchiveStore.Inventory beforeChange = ArchiveStore.read(unrelatedChange, () -> false);
            zip(unrelatedChange.toPath(), Map.of("selected", payload, "unrelated", new byte[]{2}), false);
            rejected(() -> ArchiveStore.extract(beforeChange, member(beforeChange, "selected"), snapshots, () -> false));

            File oversized = zip(temporary.resolve("oversized.zip"), Map.of("data", payload), false);
            byte[] oversizedBytes = Files.readAllBytes(oversized.toPath());
            putInt(oversizedBytes, 22, ArchiveStore.MAX_MEMBER + 1);
            putInt(oversizedBytes, indexOf(oversizedBytes, new byte[]{0x50, 0x4b, 1, 2}) + 24, ArchiveStore.MAX_MEMBER + 1);
            Files.write(oversized.toPath(), oversizedBytes); ArchiveStore.Inventory oversizedInventory = ArchiveStore.read(oversized, () -> false);
            rejected(() -> ArchiveStore.extract(oversizedInventory, oversizedInventory.members.get(0), snapshots, () -> false));

            File many = temporary.resolve("many.zip").toFile();
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(many))) {
                for (int i = 0; i < ArchiveStore.MAX_ENTRIES + 1; i++) add(out, "entry-" + i, new byte[0], false);
            }
            rejected(() -> ArchiveStore.read(many, () -> false));
            File tooLargeArchive = temporary.resolve("too-large.zip").toFile();
            try (RandomAccessFile sparse = new RandomAccessFile(tooLargeArchive, "rw")) { sparse.setLength(ArchiveStore.MAX_ARCHIVE + 1); }
            rejected(() -> ArchiveStore.read(tooLargeArchive, () -> false));
            Files.write(temporary.resolve("truncated.zip"), Arrays.copyOf(original, 24));
            rejected(() -> ArchiveStore.read(temporary.resolve("truncated.zip").toFile(), () -> false));
            require(Objects.requireNonNull(snapshots.list()).length == published, "Failure/cancellation left a staged file");
            System.out.println("Archive fixtures passed: stored/deflate/empty bytes, SHA-256, path safety, duplicates, CRC, directory changes, caps, truncation and cancelled-stage cleanup.");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static ArchiveStore.Member member(ArchiveStore.Inventory inventory, String name) {
        return inventory.members.stream().filter(member -> member.name.equals(name)).findFirst().orElseThrow();
    }
    static File zip(Path path, Map<String, byte[]> members, boolean deflated) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : members.entrySet()) add(out, entry.getKey(), entry.getValue(), deflated);
        }
        return path.toFile();
    }
    private static void add(ZipOutputStream out, String name, byte[] data, boolean deflated) throws IOException {
        ZipEntry entry = new ZipEntry(name); CRC32 crc = new CRC32(); crc.update(data);
        entry.setMethod(deflated ? ZipEntry.DEFLATED : ZipEntry.STORED); entry.setSize(data.length); entry.setCrc(crc.getValue());
        if (!deflated) entry.setCompressedSize(data.length);
        out.putNextEntry(entry); out.write(data); out.closeEntry();
    }
    private static void replace(byte[] bytes, String from, String to) {
        byte[] before = from.getBytes(StandardCharsets.UTF_8), after = to.getBytes(StandardCharsets.UTF_8);
        require(before.length == after.length, "Replacement lengths differ");
        int count = 0;
        for (int i = 0; i <= bytes.length - before.length; i++) {
            boolean match = true; for (int j = 0; j < before.length; j++) if (bytes[i+j] != before[j]) { match = false; break; }
            if (match) { System.arraycopy(after, 0, bytes, i, after.length); count++; i += after.length - 1; }
        }
        require(count == 2, "Expected local and central filename");
    }
    private static int indexOf(byte[] all, byte[] value) {
        outer: for (int i = 0; i <= all.length - value.length; i++) {
            for (int j = 0; j < value.length; j++) if (all[i+j] != value[j]) continue outer;
            return i;
        }
        return -1;
    }
    private static void putInt(byte[] bytes, int at, long value) { for (int i = 0; i < 4; i++) bytes[at + i] = (byte)(value >>> (i * 8)); }
    interface Action { void run() throws Exception; }
    private static void rejected(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid archive operation succeeded"); } catch (IOException expected) {}
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
