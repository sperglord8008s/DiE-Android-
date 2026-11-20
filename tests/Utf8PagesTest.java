// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Independent UTF-8 reconstruction, byte coverage and supplementary-character seam oracles. */
public final class Utf8PagesTest {
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    interface Task {
        void run() throws Exception;
    }

    static void refuse(Task task, String message) throws Exception {
        try {
            task.run();
            throw new AssertionError("Accepted " + message);
        } catch (IOException expected) {
            require(expected.getMessage() != null, "Missing diagnostic");
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("glassdigestor-utf8-pages");
        try {
            Random random = new Random(0x5f637173L);
            int cases = 0, pages = 0;
            for (int test = 0; test < 90; test++) {
                StringBuilder wanted = new StringBuilder();
                int count = test < 8 ? test : 1000 + random.nextInt(16000);
                for (int i = 0; i < count; i++) {
                    int[] codepoints = {
                        0, 9, 10, 13, 32, 65, 0x7f, 0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff,
                        0x10000, 0x1f600, 0x10ffff
                    };
                    wanted.appendCodePoint(codepoints[random.nextInt(codepoints.length)]);
                }
                byte[] expected = wanted.toString().getBytes(StandardCharsets.UTF_8);
                File file = root.resolve("case-" + test).toFile();
                Files.write(file.toPath(), expected);
                int characters = test % 3 == 0 ? 2 : test % 3 == 1 ? 71 : Utf8Pages.CHARACTERS;
                StringBuilder actual = new StringBuilder();
                ByteArrayOutputStream covered = new ByteArrayOutputStream();
                long offset = 0;
                do {
                    Utf8Pages.Page page =
                            Utf8Pages.read(file, expected.length, offset, characters, () -> false);
                    require(
                            page.offset == offset && page.end <= expected.length,
                            "Page interval changed");
                    require(
                            page.text.length() <= characters
                                    && (!page.more() || page.end > page.offset),
                            "Unbounded/stalled page");
                    require(!page.text.isEmpty() || expected.length == 0, "Dropped source bytes");
                    byte[] bytes = page.text.getBytes(StandardCharsets.UTF_8);
                    require(
                            bytes.length == page.end - page.offset,
                            "Decoded span differs from consumed bytes");
                    covered.write(bytes);
                    actual.append(page.text);
                    offset = page.end;
                    pages++;
                } while (offset < expected.length);
                require(
                        actual.toString().equals(wanted.toString()),
                        "Unicode reconstruction differs");
                require(
                        Arrays.equals(covered.toByteArray(), expected),
                        "Physical byte coverage differs");
                Utf8Pages.Page tail =
                        Utf8Pages.tail(file, expected.length, characters, () -> false);
                require(
                        tail.end == expected.length && tail.text.length() <= characters,
                        "Wrong tail bounds");
                require(
                        wanted.toString().endsWith(tail.text),
                        "Tail differs from independent string suffix");
                require(
                        Arrays.equals(
                                Arrays.copyOfRange(expected, (int) tail.offset, expected.length),
                                tail.text.getBytes(StandardCharsets.UTF_8)),
                        "Tail source interval differs");
                cases++;
            }
            File large = root.resolve("large.txt").toFile();
            byte[] block = ("long-line\t世界 😀\r\n".repeat(1024)).getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = new FileOutputStream(large)) {
                for (int i = 0; i < 1024; i++) output.write(block);
                output.write("FINAL-TAIL-終😀".getBytes(StandardCharsets.UTF_8));
            }
            require(large.length() > 16L * 1024 * 1024, "Large fixture was not actually written");
            require(
                    Utf8Pages.tail(large, large.length(), Utf8Pages.CHARACTERS, () -> false)
                            .text
                            .endsWith("FINAL-TAIL-終😀"),
                    "Large-file tail missing");
            refuse(
                    () -> Utf8Pages.read(large, large.length(), 0, 10, () -> true),
                    "cancelled page");
            refuse(
                    () -> Utf8Pages.read(large, large.length() - 1, 0, 10, () -> false),
                    "stale size");
            File malformed = root.resolve("malformed.txt").toFile();
            Files.write(
                    malformed.toPath(),
                    new byte[] {
                        65,
                        (byte) 0xf0,
                        (byte) 0x9f,
                        (byte) 0x98,
                        (byte) 0x80,
                        (byte) 0xc0,
                        (byte) 0xaf
                    });
            refuse(
                    () -> Utf8Pages.read(malformed, malformed.length(), 0, 20, () -> false),
                    "overlong UTF-8");
            refuse(
                    () -> Utf8Pages.read(malformed, malformed.length(), 2, 20, () -> false),
                    "continuation-byte start");
            Files.write(malformed.toPath(), new byte[] {(byte) 0xf0, (byte) 0x9f, (byte) 0x98});
            refuse(
                    () -> Utf8Pages.read(malformed, malformed.length(), 0, 20, () -> false),
                    "truncated EOF codepoint");
            refuse(
                    () -> Utf8Pages.tail(malformed, malformed.length(), 20, () -> false),
                    "invalid UTF-8 tail");
            refuse(
                    () -> Utf8Pages.read(large, large.length(), Long.MAX_VALUE, 2, () -> false),
                    "out-of-bounds offset");
            System.out.println(
                    "UTF-8 paging passed "
                            + cases
                            + " independent Unicode cases / "
                            + pages
                            + " complete byte-partition pages, a fully written "
                            + large.length()
                            + "-byte file/tail, cancellation and malformed/bounds refusals.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }
}
