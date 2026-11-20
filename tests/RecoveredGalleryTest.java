// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

/** Real native discoveries -> combined cards -> exact files. The source bytes are the oracle. */
public final class RecoveredGalleryTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static BinaryRunner runner(String engine, AtomicInteger calls) {
        return (input, stop) -> {
            calls.incrementAndGet();
            EvidenceRows.check(stop);
            try (RandomAccessFile file = new RandomAccessFile(input, "r")) {
                Field descriptor = FileDescriptor.class.getDeclaredField("fd");
                descriptor.setAccessible(true);
                int fd = descriptor.getInt(file.getFD());
                return engine.equals("photorec")
                        ? PhotoRecEngine.scanFd(fd)
                        : BinwalkEngine.scanFd(fd);
            }
        };
    }

    public static void main(String[] args) throws Exception {
        require(PhotoRecEngine.AVAILABLE, PhotoRecEngine.ERROR);
        require(BinwalkEngine.AVAILABLE, BinwalkEngine.ERROR);
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        File directory = root.resolve("inputs").toFile();
        File exports = root.resolve("exports").toFile();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(
                    "Recovered archive member 世界 😀\n".repeat(40).getBytes(StandardCharsets.UTF_8));
        }
        byte[][] payloads = {
            MetadataFixtureFactory.jpeg(),
            MetadataFixtureFactory.png(),
            MetadataFixtureFactory.wav(),
            Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"),
            compressed.toByteArray(),
            Files.readAllBytes(Path.of("tests/fixtures/media/green-64x48.mp4")),
            "Recovered gallery text 世界 😀\n".getBytes(StandardCharsets.UTF_8)
        };
        String[] formats = {"jpg", "png", "wav", "gif", "gz", "mp4", "txt"};
        long[] offsets = new long[payloads.length];
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        blob.write(new byte[37]);
        for (int i = 0; i < payloads.length; i++) {
            offsets[i] = blob.size();
            blob.write(payloads[i]);
            blob.write(new byte[17]);
            Files.write(root.resolve("expected-" + i), payloads[i]);
        }
        File source = root.resolve("mislabelled.exe").toFile();
        Files.write(source.toPath(), blob.toByteArray());
        AtomicInteger photoCalls = new AtomicInteger(), binCalls = new AtomicInteger();
        GallerizeSession session =
                GallerizeSession.open(
                        source,
                        directory,
                        source.getName(),
                        null,
                        blob.size(),
                        null,
                        runner("photorec", photoCalls),
                        runner("binwalk", binCalls),
                        () -> false);
        ContentMap map = session.digest(() -> false);
        JSONObject oracle = new JSONObject();
        for (int i = 0; i < payloads.length; i++) {
            final int index = i;
            ContentMap.Card card =
                    map.cards.stream()
                            .filter(
                                    c ->
                                            c.offset == offsets[index]
                                                    && c.length != null
                                                    && c.length == payloads[index].length
                                                    && RecoveredFiles.extension(c.format)
                                                            .equals(formats[index]))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "Missing actual " + formats[index] + " file"));
            require(RecoveredFiles.fileCard(card), "File missing from default group");
            if (i == 0) {
                require(
                        card.findings.stream()
                                .anyMatch(
                                        r ->
                                                r.engine.equals("binwalk")
                                                        && r.length != null
                                                        && r.length == payloads[0].length),
                        "Binwalk JPEG file is absent from the combined gallery");
                JSONArray witnesses = session.findings(card, () -> false);
                require(witnesses.length() == card.findings.size(), "Native witness dropped");
                ArchiveLaunch launch = session.archiveLaunch(card, () -> false);
                require(
                        launch.findingKind.equals(card.nativeEngine + "-candidates"),
                        "Archive hypothesis used wrong engine");
            }
            PreparedExport single = RecoveredFiles.file(session.input, card, exports, () -> false);
            require(
                    Arrays.equals(Files.readAllBytes(single.file.toPath()), payloads[i]),
                    "Single file bytes changed");
            require(single.name.endsWith("." + formats[i]), "File type/name lost");
            single.discard();
            oracle.put(RecoveredFiles.name(card), i);
        }
        ContentMap.Card png =
                map.cards.stream()
                        .filter(
                                c ->
                                        c.offset == offsets[1]
                                                && c.length != null
                                                && c.length == payloads[1].length
                                                && c.format.equals("png"))
                        .findFirst()
                        .orElseThrow();
        require(
                png.findings.stream().anyMatch(r -> r.engine.equals("binwalk"))
                        && png.findings.stream().anyMatch(r -> r.engine.equals("photorec")),
                "Duplicate file lost an engine");
        ContentMap.Card gzip =
                map.cards.stream()
                        .filter(
                                c ->
                                        c.offset == offsets[4]
                                                && c.format.equals("gz")
                                                && c.length != null
                                                && c.length == payloads[4].length)
                        .findFirst()
                        .orElseThrow();
        require(
                gzip.nativeEngine.equals("binwalk"),
                "Gzip file boundary did not come from Binwalk");
        require(
                gzip.findings.stream()
                        .anyMatch(r -> r.engine.equals("photorec") && r.length == null),
                "Original unknown PhotoRec gzip extent was changed or lost");
        StringWriter report = new StringWriter();
        session.writeReport(report, () -> false);
        JSONObject complete = new JSONObject(report.toString());
        require(
                map.rawCount == session.raw().size() + session.raw("binwalk").size(),
                "Native count omitted an engine");
        require(
                complete.getJSONObject("nativeCatalog").getJSONArray("rows").length() == 774,
                "PhotoRec catalog changed");
        require(
                complete.getJSONObject("binwalkNativeCatalog").getJSONArray("rows").length() == 251,
                "Binwalk catalog omitted");
        Files.writeString(root.resolve("gallery-report.json"), report.toString());
        List<ContentMap.Card> actual =
                map.cards.stream().filter(c -> oracle.has(RecoveredFiles.name(c))).toList();
        PreparedExport bundle = RecoveredFiles.zip(session.input, actual, exports, () -> false);
        String state = session.retentionState();
        session.close(true);
        Files.delete(source.toPath());
        File binCapture =
                new File(new JSONObject(state).getJSONObject("binwalk").getString("capturePath"));
        byte[] saved = Files.readAllBytes(binCapture.toPath());
        byte[] corrupt = saved.clone();
        corrupt[corrupt.length / 2] ^= 1;
        Files.write(binCapture.toPath(), corrupt);
        BinaryRunner noRescan =
                (file, stop) -> {
                    throw new AssertionError("Retained capture was rescanned");
                };
        try {
            GallerizeSession.open(
                    source,
                    directory,
                    source.getName(),
                    null,
                    blob.size(),
                    state,
                    noRescan,
                    noRescan,
                    () -> false);
            throw new AssertionError("Changed Binwalk capture accepted");
        } catch (IOException expected) {
            require(expected.getMessage().contains("identity changed"), "Wrong restore error");
        }
        Files.write(binCapture.toPath(), saved);
        try (GallerizeSession restored =
                GallerizeSession.open(
                        source,
                        directory,
                        source.getName(),
                        null,
                        blob.size(),
                        state,
                        noRescan,
                        noRescan,
                        () -> false)) {
            require(
                    restored.digest(() -> false).cards.size() == map.cards.size(),
                    "Restored gallery changed");
            require(photoCalls.get() == 1 && binCalls.get() == 1, "Engine rescanned");
        }
        require(
                !new File(new JSONObject(state).getString("inputPath")).exists(),
                "Owned source retained after close");
        Files.copy(bundle.file.toPath(), root.resolve("recovered-gallery.zip"));
        bundle.discard();
        Files.writeString(root.resolve("oracle.json"), oracle.toString());

        // A failed engine must not make the other engine's actual files disappear.
        Files.write(source.toPath(), payloads[0]);
        BinaryRunner failure =
                (file, stop) -> {
                    throw new IOException("deliberate engine failure");
                };
        try (GallerizeSession partial =
                GallerizeSession.open(
                        source,
                        directory,
                        "partial",
                        null,
                        source.length(),
                        null,
                        failure,
                        runner("binwalk", new AtomicInteger()),
                        () -> false)) {
            require(
                    partial.digest(() -> false).cards.stream()
                            .anyMatch(
                                    c ->
                                            c.format.equals("jpg")
                                                    && c.length != null
                                                    && c.length == payloads[0].length),
                    "PhotoRec failure hid Binwalk JPEG");
            require(
                    partial.statusSummary().contains("deliberate engine failure"),
                    "Engine failure hidden");
            String retained = partial.retentionState();
            partial.close(true);
            try (GallerizeSession restored =
                    GallerizeSession.open(
                            source,
                            directory,
                            "partial",
                            null,
                            source.length(),
                            retained,
                            noRescan,
                            noRescan,
                            () -> false)) {
                require(
                        restored.digest(() -> false).boundedCount > 0,
                        "Partial retained files lost");
                require(
                        restored.statusSummary().contains("deliberate engine failure"),
                        "Retained failure lost");
            }
        }
        Files.write(source.toPath(), Arrays.copyOf(payloads[0], payloads[0].length - 2));
        try (GallerizeSession partial =
                GallerizeSession.open(
                        source,
                        directory,
                        "partial",
                        null,
                        source.length(),
                        null,
                        runner("photorec", new AtomicInteger()),
                        failure,
                        () -> false)) {
            ContentMap partialMap = partial.digest(() -> false);
            require(
                    partialMap.cards.stream()
                            .anyMatch(c -> c.format.equals("jpg") && c.length == null),
                    "Header-only evidence disappeared on Binwalk failure");
            require(
                    partialMap.cards.stream()
                            .noneMatch(c -> c.format.equals("jpg") && c.length != null),
                    "Unknown JPEG boundary inferred on Binwalk failure");
        }
        modelAlternatives(root);
        System.out.println(
                "Actual PhotoRec + Binwalk recoveries, merged/unknown witnesses, exact"
                    + " JPEG/PNG/WAV/GIF/gzip/MP4/text files, complete catalogs, retained restore"
                    + " and independent engine failures passed.");
    }

    private static void modelAlternatives(Path root) throws Exception {
        File source = root.resolve("model-ranges.bin").toFile();
        Files.write(source.toPath(), new byte[10]);
        EvidenceRows unknown = modelRows("photorec", new Long[] {null});
        EvidenceRows bounded = modelRows("binwalk", new Long[] {5L, 7L});
        try (InputSnapshot input =
                InputSnapshot.acquire(
                        source, root.resolve("model-inputs").toFile(), 10, () -> false)) {
            ContentMap map =
                    ContentMap.build(
                            input,
                            "model",
                            List.of(
                                    new ContentMap.Source("photorec", unknown),
                                    new ContentMap.Source("binwalk", bounded)),
                            () -> false);
            List<ContentMap.Card> files =
                    map.cards.stream().filter(RecoveredFiles::fileCard).toList();
            require(
                    files.size() == 2,
                    "Conflicting bounded extents were collapsed or duplicated by a header");
            for (ContentMap.Card card : files) {
                require(
                        card.nativeEngine.equals("binwalk"),
                        "Unknown header supplied the displayed boundary");
                require(
                        card.findings.size() == 2 && card.findings.get(1).length == null,
                        "Unknown witness acquired a boundary or was dropped");
                JSONArray witnesses = card.json().getJSONArray("nativeFindings");
                require(
                        witnesses.getJSONObject(0).getBoolean("supportsDisplayedExtent")
                                && !witnesses
                                        .getJSONObject(1)
                                        .getBoolean("supportsDisplayedExtent"),
                        "Extent attribution changed");
            }
        }
    }

    private static EvidenceRows modelRows(String engine, Long[] sizes) {
        return new EvidenceRows(engine + "-candidates", "Synthetic range relationship fixture") {
            int size() {
                return sizes.length;
            }

            Row row(int index, java.util.function.BooleanSupplier stop) throws Exception {
                EvidenceRows.check(stop);
                return new Row(
                        index,
                        "jpeg",
                        "synthetic model fixture",
                        0L,
                        sizes[index],
                        new JSONObject().put("parserAccepted", true));
            }

            JSONObject metadata() {
                return new JSONObject();
            }
        };
    }
}
