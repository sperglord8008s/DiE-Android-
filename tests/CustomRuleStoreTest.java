// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Transaction, untrusted ZIP and persistence tests, with no Android or native dependency. */
public final class CustomRuleStoreTest {
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("die-rules-test");
        try {
            File store = temporary.resolve("store").toFile();
            require(CustomRuleStore.active(store) == null, "Fresh store has an active snapshot");
            File archive = zip(temporary.resolve("valid.zip"), Map.of("db_custom/ELF/tool_fixture.sg", "meta(\"tool\", \"fixture\");",
                "db_custom/LICENSE.txt", "Fixture license", "db_custom/_metadata/ELF.txt", "tool_fixture.sg#test"));
            CustomRuleStore.Snapshot first = CustomRuleStore.install(store, archive, "fixture.zip");
            require(first.ruleCount == 1 && first.database.isDirectory(), "Rules were not imported");
            require(CustomRuleStore.active(store) == null, "Import activated unreviewed scripts");
            require(Files.readString(new File(first.database, "LICENSE.txt").toPath()).equals("Fixture license"), "License notice lost");
            require(Arrays.equals(Files.readAllBytes(archive.toPath()), Files.readAllBytes(first.archive.toPath())), "Original archive not preserved");
            CustomRuleStore.activate(store, first.digest);
            require(CustomRuleStore.active(store).digest.equals(first.digest), "Active snapshot not persisted");
            require(CustomRuleStore.install(store, archive, "alias.zip").digest.equals(first.digest), "Duplicate archive created another identity");
            require(CustomRuleStore.list(store).size() == 1, "Duplicate snapshot stored");
            File other = zip(temporary.resolve("other.zip"), Map.of("PE/tool_fixture.sg", "meta(\"tool\", \"other\");"));
            CustomRuleStore.Snapshot second = CustomRuleStore.install(store, other, "other.zip");
            CustomRuleStore.activate(store, second.digest);
            require(!second.database.equals(first.database), "Database path did not change for new content");
            CustomRuleStore.activate(store, first.digest);
            require(CustomRuleStore.active(store).digest.equals(first.digest), "Rollback failed");

            for (String unsafe : new String[]{"../escape.sg", "/absolute.sg", "db_custom/../escape.sg", "ELF/../../escape.sg",
                "ELF\\escape.sg", "ELF/./a.sg", "ELF//a.sg", "C:/a.sg", "ELF/nested/a.sg", "unknown/a.sg", "ELF/a.js"}) {
                File bad = zip(temporary.resolve("unsafe.zip"), Map.of(unsafe, "untrusted"));
                rejected(() -> CustomRuleStore.install(store, bad, "unsafe.zip"));
            }
            File duplicate = zip(temporary.resolve("duplicate.zip"), Map.of("ELF/a.sg", "one", "db/ELF/a.sg", "two"));
            rejected(() -> CustomRuleStore.install(store, duplicate, "duplicate.zip"));
            File onlyInit = zip(temporary.resolve("only-init.zip"), Map.of("ELF/_init", "framework"));
            rejected(() -> CustomRuleStore.install(store, onlyInit, "only-init.zip"));
            File oversized = zip(temporary.resolve("oversized.zip"), Map.of("ELF/a.sg", "a".repeat(CustomRuleStore.MAX_FILE + 1)));
            rejected(() -> CustomRuleStore.install(store, oversized, "oversized.zip"));
            File largeArchive = temporary.resolve("large.zip").toFile();
            try (RandomAccessFile sparse = new RandomAccessFile(largeArchive, "rw")) { sparse.setLength(CustomRuleStore.MAX_ARCHIVE + 1); }
            rejected(() -> CustomRuleStore.install(store, largeArchive, "large.zip"));
            File expanded = temporary.resolve("expanded.zip").toFile();
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(expanded))) {
                byte[] megabyte = new byte[CustomRuleStore.MAX_FILE];
                for (int i = 0; i < 65; i++) { out.putNextEntry(new ZipEntry("ELF/rule_" + i + ".sg")); out.write(megabyte); out.closeEntry(); }
            }
            rejected(() -> CustomRuleStore.install(store, expanded, "expanded.zip"));

            File crc = zip(temporary.resolve("crc.zip"), Map.of("ELF/a.sg", "DIE_RULE_CRC_FIXTURE"));
            byte[] bytes = Files.readAllBytes(crc.toPath());
            byte[] marker = "DIE_RULE_CRC_FIXTURE".getBytes(StandardCharsets.UTF_8);
            int at = indexOf(bytes, marker); require(at >= 0, "Stored member missing"); bytes[at] ^= 1;
            Files.write(crc.toPath(), bytes);
            rejected(() -> CustomRuleStore.install(store, crc, "crc.zip"));
            Files.write(temporary.resolve("truncated.zip"), Arrays.copyOf(bytes, 24));
            rejected(() -> CustomRuleStore.install(store, temporary.resolve("truncated.zip").toFile(), "truncated.zip"));
            require(CustomRuleStore.active(store).digest.equals(first.digest), "Failed import changed active database");
            require(CustomRuleStore.list(store).size() == 2, "Failed import published a snapshot");
            require(Arrays.stream(Objects.requireNonNull(store.list())).noneMatch(s -> s.startsWith(".staging-")), "Failed import left staging data");
            require(!temporary.resolve("escape.sg").toFile().exists(), "Traversal wrote outside staging");
            System.out.println("Custom database fixtures passed: import, notices, digest, persistence, rollback, paths, duplicates, limits, CRC and truncation.");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static File zip(Path path, Map<String, String> entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var item : entries.entrySet()) {
                byte[] data = item.getValue().getBytes(StandardCharsets.UTF_8);
                CRC32 crc = new CRC32(); crc.update(data);
                ZipEntry entry = new ZipEntry(item.getKey()); entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length); entry.setCrc(crc.getValue());
                out.putNextEntry(entry); out.write(data); out.closeEntry();
            }
        }
        return path.toFile();
    }
    interface Action { void run() throws Exception; }
    private static void rejected(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid import accepted"); } catch (IOException expected) {}
    }
    private static int indexOf(byte[] all, byte[] value) {
        outer: for (int i = 0; i <= all.length - value.length; i++) {
            for (int j = 0; j < value.length; j++) if (all[i+j] != value[j]) continue outer;
            return i;
        }
        return -1;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
