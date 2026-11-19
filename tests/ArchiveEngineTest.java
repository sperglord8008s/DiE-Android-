// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Actual pinned native reader; independent fixture expectations and byte identities. */
public final class ArchiveEngineTest {
    static int fd(FileDescriptor value) throws Exception {
        Field f = FileDescriptor.class.getDeclaredField("fd");
        f.setAccessible(true);
        return f.getInt(value);
    }

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String name(JSONObject row) throws Exception {
        JSONObject text = row.optJSONObject("pathnameUtf8");
        if (text == null) text = row.optJSONObject("pathname");
        return text == null ? "" : text.getString("text");
    }

    public static void main(String[] args) throws Exception {
        require(ArchiveEngine.AVAILABLE, ArchiveEngine.ERROR);
        Path root = Path.of(args[0]);
        JSONObject fixtures =
                new JSONObject(
                        new String(
                                Files.readAllBytes(root.resolve("expected.json")),
                                StandardCharsets.UTF_8));
        int captures = 0, acquisitions = 0;
        for (String fixture : fixtures.keySet()) {
            JSONObject expected = fixtures.getJSONObject(fixture);
            File file = root.resolve(fixture).toFile();
            long start = expected.optLong("offset", 0);
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                input.seek(73);
                JSONObject report =
                        new JSONObject(
                                ArchiveEngine.inspectFd(
                                        fd(input.getFD()), start, file.length() - start));
                captures++;
                require(
                        input.getFilePointer() == 73,
                        "Inspect moved caller seek position: " + fixture);
                require(
                        sha(Files.readAllBytes(file.toPath())).equals(expected.getString("sha256")),
                        "Source changed");
                if (fixture.startsWith("budget-")) {
                    require(
                            !report.getBoolean("ok"),
                            "Oversized codec dictionary/window was accepted: " + fixture);
                    continue;
                }
                if (fixture.equals("unknown.dat")) {
                    require(!report.getBoolean("ok"), "Raw fallback fabricated an archive");
                    continue;
                }
                if (fixture.equals("bad-crc.zip")) {
                    try (RandomAccessFile output =
                            new RandomAccessFile(root.resolve("bad-output").toFile(), "rw")) {
                        output.setLength(0);
                        JSONObject failed =
                                new JSONObject(
                                        ArchiveEngine.extractFd(
                                                fd(input.getFD()),
                                                fd(output.getFD()),
                                                0,
                                                file.length(),
                                                0));
                        require(
                                !failed.getBoolean("ok") && failed.isNull("acquiredSizeBytes"),
                                "CRC failure published successful member bytes");
                    }
                    continue;
                }
                require(report.getBoolean("ok"), fixture + ": " + report);
                require(
                        report.getBoolean("callerDescriptorPositionPreserved")
                                && !report.getBoolean("externalProgramsEnabled")
                                && !report.getBoolean("filesystemReaderEnabled"),
                        "Changed reader contract");
                require(
                        report.getString("engineDetails").contains("3.8.11"),
                        "Wrong native library");
                JSONArray rows = report.getJSONArray("entries"),
                        wanted = expected.getJSONArray("entries");
                require(
                        rows.length() == wanted.length(),
                        fixture + ": entry count differs " + rows);
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i), want = wanted.getJSONObject(i);
                    String original = name(row);
                    require(
                            original.equals(want.getString("name")),
                            fixture + ": wrong name " + original);
                    require(
                            row.isNull("digestPresence"),
                            "Unconditional digest accessor buffer became a declared checksum");
                    try (RandomAccessFile output =
                            new RandomAccessFile(root.resolve("member-output").toFile(), "rw")) {
                        output.setLength(0);
                        output.seek(11);
                        JSONObject result =
                                new JSONObject(
                                        ArchiveEngine.extractFd(
                                                fd(input.getFD()),
                                                fd(output.getFD()),
                                                start,
                                                file.length() - start,
                                                i));
                        require(
                                input.getFilePointer() == 73 && output.getFilePointer() == 11,
                                "Extraction changed caller descriptor position");
                        if (want.optBoolean("link") || want.optBoolean("directory")) {
                            require(!result.getBoolean("ok"), "Link/directory materialized");
                            continue;
                        }
                        require(result.getBoolean("ok"), fixture + " #" + i + ": " + result);
                        acquisitions++;
                        byte[] bytes = Files.readAllBytes(root.resolve("member-output"));
                        require(
                                bytes.length == want.getLong("size"),
                                "Extracted size differs: " + fixture);
                        if (want.has("sha256"))
                            require(
                                    sha(bytes).equals(want.getString("sha256")),
                                    "Independent complete extracted bytes differ: " + fixture);
                        else
                            require(
                                    new String(bytes, StandardCharsets.UTF_8)
                                            .startsWith(want.getString("prefix")),
                                    "Upstream prefix expectation differs: " + fixture);
                        JSONArray before = new JSONArray(rows.toString()),
                                after = new JSONArray(result.getJSONArray("entries").toString());
                        if (fixture.equals("upstream-test_read_format_cab_3.cab") && i == 0) {
                            JSONObject oracle =
                                    new JSONObject(
                                            new String(
                                                    Files.readAllBytes(
                                                            root.resolve(
                                                                    "cab-position-oracle.json")),
                                                    StandardCharsets.UTF_8));
                            require(
                                    rows.getJSONObject(1)
                                                            .getLong(
                                                                    "headerPositionUncompressedStream")
                                                    == oracle.getLong("skips")
                                            && result.getJSONArray("entries")
                                                            .getJSONObject(1)
                                                            .getLong(
                                                                    "headerPositionUncompressedStream")
                                                    == oracle.getLong("dataRead"),
                                    "CAB raw positions differ from direct upstream API oracle");
                        }
                        for (int n = 0; n < before.length(); n++) {
                            // The original accessor observations remain in each report. Only this
                            // context-dependent
                            // coordinate is excluded from stable entry identity, as verified
                            // against direct upstream calls.
                            before.getJSONObject(n).remove("headerPositionUncompressedStream");
                            after.getJSONObject(n).remove("headerPositionUncompressedStream");
                        }
                        require(
                                before.similar(after),
                                fixture + " stable complete directory metadata changed");
                        if (fixture.startsWith("tar.") || fixture.equals("mislabeled.bin")) {
                            if (i == 0) {
                                require(
                                        row.getLong("uid") == 1234 && row.getLong("gid") == 5678,
                                        "PAX owner fields lost");
                                require(row.getJSONArray("xattrs").length() == 1, "Xattr lost");
                            }
                        }
                    }
                }
            }
        }
        require(
                !new JSONObject(ArchiveEngine.inspectFd(-1, 0, 0)).getBoolean("ok"),
                "Invalid descriptor accepted");
        File safe = root.resolve("mislabeled.apk").toFile();
        byte[] unchanged = Files.readAllBytes(safe.toPath());
        try (FileOutputStream writeOnly = new FileOutputStream(safe, true)) {
            require(
                    !new JSONObject(
                                    ArchiveEngine.inspectFd(
                                            fd(writeOnly.getFD()), 0, safe.length()))
                            .getBoolean("ok"),
                    "Write-only input accepted");
        }
        try (RandomAccessFile input = new RandomAccessFile(safe, "r")) {
            require(
                    !new JSONObject(ArchiveEngine.inspectFd(fd(input.getFD()), -1, safe.length()))
                            .getBoolean("ok"),
                    "Negative scope accepted");
            require(
                    !new JSONObject(ArchiveEngine.inspectFd(fd(input.getFD()), Long.MAX_VALUE, 1))
                            .getBoolean("ok"),
                    "Overflow scope accepted");
            try (RandomAccessFile readOnly =
                    new RandomAccessFile(root.resolve("member-output").toFile(), "r")) {
                require(
                        !new JSONObject(
                                        ArchiveEngine.extractFd(
                                                fd(input.getFD()),
                                                fd(readOnly.getFD()),
                                                0,
                                                safe.length(),
                                                0))
                                .getBoolean("ok"),
                        "Read-only output accepted");
            }
        }
        require(
                Arrays.equals(unchanged, Files.readAllBytes(safe.toPath())),
                "Descriptor refusals changed original source");
        File oversized = root.resolve("oversized-sparse").toFile();
        try (RandomAccessFile source = new RandomAccessFile(oversized, "rw")) {
            source.setLength(1024L * 1024 * 1024 + 1);
            require(
                    !new JSONObject(ArchiveEngine.inspectFd(fd(source.getFD()), 0, source.length()))
                            .getBoolean("ok"),
                    "Oversize source accepted");
        } finally {
            Files.delete(oversized.toPath());
        }
        require(!root.resolve("../outside.txt").toFile().exists(), "Member path escaped");
        System.out.println(
                "Native archive fixtures passed: "
                        + captures
                        + " complete/failed captures, "
                        + acquisitions
                        + " exact derived acquisitions, content-based formats, PAX"
                        + " metadata/links/duplicates, CRC refusal, embedded scopes and caller FD"
                        + " ownership.");
    }
}
