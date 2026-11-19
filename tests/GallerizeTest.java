// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real PhotoRec plus independent byte/range/entropy expectations; no inspected input executes. */
public final class GallerizeTest {
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static BinaryRunner nativeRunner(AtomicInteger calls) {
        return (file, stop) -> {
            calls.incrementAndGet();
            try (RandomAccessFile source = new RandomAccessFile(file, "r")) {
                Field field = FileDescriptor.class.getDeclaredField("fd");
                field.setAccessible(true);
                return PhotoRecEngine.scanFd(field.getInt(source.getFD()));
            }
        };
    }

    private static void largeInput(Path root, File directory) throws Exception {
        long size = 80L * 1024 * 1024 + 17, pngAt = 70L * 1024 * 1024 + 5;
        File source = root.resolve("fully-written-large.blob").toFile();
        byte[] zero = new byte[65536], png = MetadataFixtureFactory.png();
        byte[] text =
                "Large-file tail text 世界 🔬 remains exact.\n".getBytes(StandardCharsets.UTF_8);
        try {
            try (OutputStream output = new FileOutputStream(source)) {
                for (long remaining = size; remaining > 0; ) {
                    int count = (int) Math.min(remaining, zero.length);
                    output.write(zero, 0, count);
                    remaining -= count;
                }
            }
            try (RandomAccessFile output = new RandomAccessFile(source, "rw")) {
                output.seek(pngAt);
                output.write(png);
                output.seek(size - text.length);
                output.write(text);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input =
                    new java.security.DigestInputStream(new FileInputStream(source), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            String expectedSha = HexFormat.of().formatHex(digest.digest());
            AtomicInteger calls = new AtomicInteger();
            try (GallerizeSession session =
                    GallerizeSession.open(
                            source,
                            directory,
                            source.getName(),
                            expectedSha,
                            size,
                            null,
                            nativeRunner(calls),
                            () -> false)) {
                ContentMap map = session.digest(() -> false);
                require(
                        map.cards.stream()
                                .anyMatch(
                                        card ->
                                                card.kind == ContentMap.Kind.IMAGE
                                                        && card.offset == pngAt
                                                        && card.length != null
                                                        && card.length == png.length),
                        "Artifact beyond 64 MiB not in gallery");
                require(
                        map.cards.stream()
                                .anyMatch(
                                        card ->
                                                card.kind == ContentMap.Kind.TEXT
                                                        && card.offset == size - text.length
                                                        && card.length == text.length),
                        "Large-file tail text omitted");
                ByteGrid.Result grid =
                        ByteGrid.measure(
                                session.input,
                                ByteGrid.automatic(size, ByteGrid.Mode.ENTROPY),
                                ByteGrid.Mode.ENTROPY,
                                () -> false);
                require(
                        grid.sha256.equals(expectedSha),
                        "Large grid differs from independent whole-input hash");
                boolean measuredPng = false;
                for (int i = 0; i < grid.layout.used; i++) {
                    FileRange range = grid.layout.range(i);
                    if (range.offset <= pngAt && pngAt < range.offset + range.length) {
                        require(grid.values[i] > 0, "Beyond-prefix PNG not measured");
                        measuredPng = true;
                    }
                }
                require(measuredPng, "Large grid omitted PNG cell");
                System.out.println(
                        "Gallerize 80 MiB + 17 fully written bytes passed: exact PNG beyond 64 MiB,"
                            + " Unicode tail, whole-input SHA and complete local-entropy grid.");
            }
        } finally {
            Files.deleteIfExists(source.toPath());
        }
    }

    public static void main(String[] args) throws Exception {
        require(PhotoRecEngine.AVAILABLE, "Actual PhotoRec JNI required: " + PhotoRecEngine.ERROR);
        Path root = Path.of(args[0]);
        File directory = root.resolve("gallery-owned").toFile();
        byte[][] files = {
            MetadataFixtureFactory.png(),
            MetadataFixtureFactory.jpeg(),
            MetadataFixtureFactory.wav(),
            Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        };
        String text =
                "Plain text with https://example.invalid/path and 世界 🔬 — end of concatenation.\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int[] offsets = new int[4];
        for (int i = 0; i < files.length; i++) {
            offsets[i] = output.size();
            output.write(files[i]);
        }
        int textAt = output.size();
        output.write(text.getBytes(StandardCharsets.UTF_8));
        byte[] blob = output.toByteArray();
        File source = root.resolve("misleading.exe").toFile();
        Files.write(source.toPath(), blob);
        AtomicInteger calls = new AtomicInteger();
        GallerizeSession session =
                GallerizeSession.open(
                        source,
                        directory,
                        source.getName(),
                        sha(blob),
                        blob.length,
                        null,
                        nativeRunner(calls),
                        () -> false);
        ContentMap map = session.digest(() -> false);
        require(
                map.composition.startsWith("Multiple separate stored artifacts"),
                "Composite evidence not obvious: " + map.composition);
        for (int i = 0; i < files.length; i++) {
            final int n = i;
            ContentMap.Card card =
                    map.cards.stream()
                            .filter(
                                    c ->
                                            c.nativeRow >= 0
                                                    && c.offset == offsets[n]
                                                    && c.length != null
                                                    && c.length == files[n].length)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "Exact concatenated artifact missing: " + n));
            require(i != 2 || card.kind == ContentMap.Kind.AUDIO, "WAV not labeled audio");
            try (InputSnapshot bytes =
                    GallerizeSession.stageRange(
                            session.input,
                            card.range(blob.length),
                            root.resolve("previews").toFile(),
                            () -> false)) {
                require(
                        Arrays.equals(files[i], Files.readAllBytes(bytes.file.toPath())),
                        "Preview changed original stored bytes");
                require(bytes.sha256.equals(sha(files[i])), "Derived SHA differs");
            }
        }
        ContentMap.Card txt =
                map.cards.stream()
                        .filter(c -> c.kind == ContentMap.Kind.TEXT && c.offset == textAt)
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("Concatenated Unicode text not mapped"));
        require(
                txt.length == text.getBytes(StandardCharsets.UTF_8).length
                        && txt.preview.equals(text),
                "Text coordinates/Unicode changed");
        StringWriter report = new StringWriter();
        session.writeReport(report, () -> false);
        JSONObject complete = new JSONObject(report.toString());
        require(
                complete.getJSONObject("nativeEvidence").getJSONArray("rows").length()
                        == session.raw().size(),
                "Native findings dropped from export");
        require(
                complete.getJSONObject("nativeCatalog").getJSONArray("rows").length() == 774,
                "Catalog incomplete");
        String retained = session.retentionState();
        String captureSha = complete.getJSONObject("nativeCapture").getString("sha256");
        File owned = session.input.file;
        session.close(true);
        require(owned.isFile(), "Borrowed carving input deleted");
        Files.delete(source.toPath());
        File captureFile = new File(new JSONObject(retained).getString("capturePath"));
        byte[] originalCapture = Files.readAllBytes(captureFile.toPath());
        byte[] corruptedCapture = originalCapture.clone();
        corruptedCapture[corruptedCapture.length / 2] ^= 1;
        Files.write(captureFile.toPath(), corruptedCapture);
        try {
            GallerizeSession.open(
                    source,
                    directory,
                    source.getName(),
                    sha(blob),
                    blob.length,
                    retained,
                    (file, stop) -> {
                        throw new AssertionError("Corrupt capture silently rescanned");
                    },
                    () -> false);
            throw new AssertionError("Changed original capture accepted");
        } catch (IOException expected) {
            require(
                    expected.getMessage().contains("identity changed"),
                    "Wrong corrupted capture failure");
        }
        Files.write(captureFile.toPath(), originalCapture);
        session =
                GallerizeSession.open(
                        source,
                        directory,
                        source.getName(),
                        sha(blob),
                        blob.length,
                        retained,
                        (file, stop) -> {
                            throw new AssertionError("Restoration reran native capture");
                        },
                        () -> false);
        require(
                session.digest(() -> false).cards.size() == map.cards.size(),
                "Restored content changed");
        require(
                session.provenance()
                                .getJSONObject("nativeCapture")
                                .getString("sha256")
                                .equals(captureSha)
                        && calls.get() == 1,
                "Original native identity changed");
        session.close();
        require(directory.list().length == 0, "Session input/capture leaked");

        byte[] seams = new byte[131100];
        Arrays.fill(seams, (byte) 0);
        byte[] unicode = "12345678 世界 🔬 Unicode seam".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(unicode, 0, seams, 65523, unicode.length);
        byte[] longText = new byte[1024];
        Arrays.fill(longText, (byte) 'x');
        System.arraycopy(longText, 0, seams, 80000, longText.length);
        seams[100000] = (byte) 0xc0;
        seams[100001] = (byte) 0xaf;
        File seamFile = root.resolve("text-seams.bin").toFile();
        Files.write(seamFile.toPath(), seams);
        try (InputSnapshot input =
                InputSnapshot.acquire(seamFile, directory, seams.length, () -> false)) {
            EvidenceRows empty =
                    new EvidenceRows("none", "none") {
                        int size() {
                            return 0;
                        }

                        Row row(int i, java.util.function.BooleanSupplier stop) {
                            throw new AssertionError();
                        }

                        JSONObject metadata() {
                            return new JSONObject();
                        }
                    };
            ContentMap textMap = ContentMap.build(input, "raw", empty, () -> false);
            require(
                    textMap.cards.stream()
                            .anyMatch(
                                    c ->
                                            c.kind == ContentMap.Kind.TEXT
                                                    && c.offset == 65523
                                                    && c.length == unicode.length
                                                    && c.preview.equals(
                                                            new String(
                                                                    unicode,
                                                                    StandardCharsets.UTF_8))),
                    "UTF-8 IO seam broke text");
            require(
                    textMap.cards.stream()
                            .anyMatch(
                                    c ->
                                            c.kind == ContentMap.Kind.TEXT
                                                    && c.offset == 80000
                                                    && c.length == 1024
                                                    && c.preview.contains("bounded preview")),
                    "Long text extent lost");
            require(textMap.textScannedBytes == seams.length, "Gap text scan omitted bytes");
        }

        Random random = new Random(81922);
        for (int size : new int[] {0, 1, 7, 4097, 640 * 640}) {
            byte[] bytes = new byte[size];
            random.nextBytes(bytes);
            File file = root.resolve("grid.bin").toFile();
            Files.write(file.toPath(), bytes);
            try (InputSnapshot input =
                    InputSnapshot.acquire(file, directory, bytes.length, () -> false)) {
                for (ByteGrid.Mode mode : ByteGrid.Mode.values()) {
                    ByteGrid.Layout layout =
                            new ByteGrid.Layout(
                                    size,
                                    mode == ByteGrid.Mode.BYTES ? 640 : 17,
                                    mode == ByteGrid.Mode.BYTES ? 640 : 13);
                    ByteGrid.Result grid = ByteGrid.measure(input, layout, mode, () -> false);
                    if (size == 4097 && mode == ByteGrid.Mode.ENTROPY) {
                        StringWriter gridReport = new StringWriter();
                        ByteGrid.writeReport(grid, "grid.bin", gridReport, () -> false);
                        JSONObject saved = new JSONObject(gridReport.toString());
                        require(
                                saved.getJSONArray("cells").length() == layout.width * layout.height
                                        && !saved.getBoolean("sampling"),
                                "Grid export omitted cells or changed scope");
                        for (int cell = 0; cell < layout.used; cell++)
                            require(
                                    saved.getJSONArray("cells")
                                            .getJSONObject(cell)
                                            .getString("valueIeee754Hex")
                                            .equals(Double.toHexString(grid.values[cell])),
                                    "Grid export changed exact values");
                    }
                    long end = 0;
                    for (int cell = 0; cell < grid.values.length; cell++) {
                        FileRange range = layout.range(cell);
                        require(range.offset == end, "Gap/overlap in cell partition");
                        end += range.length;
                        if (range.length == 0) {
                            require(
                                    Double.isNaN(grid.values[cell]),
                                    "Unused cell invented a value");
                            continue;
                        }
                        long sum = 0;
                        int[] counts = new int[256];
                        for (long i = range.offset; i < range.offset + range.length; i++) {
                            int b = bytes[(int) i] & 255;
                            sum += b;
                            counts[b]++;
                        }
                        double expected = (double) sum / range.length;
                        if (mode == ByteGrid.Mode.ENTROPY) {
                            // H = log2(n) - sum(c*log2(c))/n, independently from the production
                            // p*log(p) formula.
                            expected = Math.log(range.length) / Math.log(2);
                            for (int count : counts)
                                if (count != 0)
                                    expected -=
                                            count * Math.log(count) / Math.log(2) / range.length;
                        }
                        require(
                                Math.abs(expected - grid.values[cell]) < 1e-11,
                                "Independent grid value differs");
                    }
                    require(
                            end == size && grid.sha256.equals(sha(bytes)),
                            "Grid coverage/source changed");
                }
                if (size == 640 * 640) {
                    ByteGrid.Layout auto = ByteGrid.automatic(size, ByteGrid.Mode.BYTES);
                    require(
                            auto.width == 640 && auto.height == 640,
                            "Exact familiar raster not selected");
                }
                try {
                    ByteGrid.measure(
                            input,
                            new ByteGrid.Layout(size, 1, 1),
                            ByteGrid.Mode.BYTES,
                            () -> true);
                    throw new AssertionError("Cancellation ignored");
                } catch (InterruptedIOException expected) {
                }
                Files.write(
                        file.toPath(),
                        new byte[] {
                            1, 2
                        }); // Original provider mutation must not alter owned bytes.
                require(
                        ByteGrid.measure(
                                        input,
                                        new ByteGrid.Layout(size, 1, 1),
                                        ByteGrid.Mode.BYTES,
                                        () -> false)
                                .sha256
                                .equals(sha(bytes)),
                        "External source mutation changed snapshot");
            }
        }
        for (int[] dimensions : new int[][] {{0, 1}, {1, 0}, {2049, 1}, {2048, 2048}}) {
            try {
                new ByteGrid.Layout(1, dimensions[0], dimensions[1]);
                throw new AssertionError("Invalid dimensions accepted");
            } catch (IllegalArgumentException expected) {
            }
        }
        largeInput(root, directory);
        System.out.println(
                "Gallerize passed: real four-file + Unicode text composition, exact native/derived"
                        + " ranges, full evidence/catalog exports, capture-preserving restoration,"
                        + " strict text seams and independent complete byte/entropy grids.");
    }
}
