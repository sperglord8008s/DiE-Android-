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
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;

/** Actual PhotoRec → gallery card → libarchive, with independently generated expected bytes. */
public final class GalleryRangesTest {
    interface Task {
        void run() throws Exception;
    }

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void refuse(Task task, String message) throws Exception {
        try {
            task.run();
            throw new AssertionError("Accepted " + message);
        } catch (IOException expected) {
            require(expected.getMessage() != null, message);
        }
    }

    static int fd(FileDescriptor descriptor) throws Exception {
        Field field = FileDescriptor.class.getDeclaredField("fd");
        field.setAccessible(true);
        return field.getInt(descriptor);
    }

    static final AtomicInteger photoCalls = new AtomicInteger(), archiveCalls = new AtomicInteger();
    static final ArchiveRunner NATIVE =
            new ArchiveRunner() {
                public String inspect(File source, long offset, long length, BooleanSupplier stop)
                        throws Exception {
                    EvidenceRows.check(stop);
                    archiveCalls.incrementAndGet();
                    try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
                        return ArchiveEngine.inspectFd(fd(input.getFD()), offset, length);
                    }
                }

                public String extract(
                        File source,
                        File target,
                        long offset,
                        long length,
                        int member,
                        BooleanSupplier stop)
                        throws Exception {
                    EvidenceRows.check(stop);
                    archiveCalls.incrementAndGet();
                    try (RandomAccessFile input = new RandomAccessFile(source, "r");
                            RandomAccessFile output = new RandomAccessFile(target, "rw")) {
                        return ArchiveEngine.extractFd(
                                fd(input.getFD()), fd(output.getFD()), offset, length, member);
                    }
                }
            };

    static BinaryRunner photo() {
        return (file, stop) -> {
            EvidenceRows.check(stop);
            photoCalls.incrementAndGet();
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                return PhotoRecEngine.scanFd(fd(input.getFD()));
            }
        };
    }

    static String sha(byte[] data) throws Exception {
        return Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    public static void main(String[] args) throws Exception {
        require(PhotoRecEngine.AVAILABLE, PhotoRecEngine.ERROR);
        require(ArchiveEngine.AVAILABLE, ArchiveEngine.ERROR);
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        byte[] payload =
                ("Independently expected gallery decode 世界 😀\u0000\n".repeat(1000))
                        .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(payload);
        }
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        blob.write(new byte[37]);
        blob.write(compressed.toByteArray());
        File original = root.resolve("mislabeled.image").toFile();
        Files.write(original.toPath(), blob.toByteArray());
        String display = "Gallery source 世界 😀 \ud800 name";
        try (DataOutputStream out =
                new DataOutputStream(
                        Files.newOutputStream(root.resolve("expected-name-utf16be")))) {
            for (int i = 0; i < display.length(); i++) out.writeChar(display.charAt(i));
        }
        File galleryDirectory = root.resolve("gallery").toFile();
        GallerizeSession gallery =
                GallerizeSession.open(
                        original,
                        galleryDirectory,
                        display,
                        sha(blob.toByteArray()),
                        blob.size(),
                        null,
                        photo(),
                        () -> false);
        ContentMap map = gallery.digest(() -> false);
        ContentMap.Card card =
                map.cards.stream()
                        .filter(
                                c ->
                                        c.nativeRow >= 0
                                                && c.offset == 37
                                                && c.kind == ContentMap.Kind.ARCHIVE)
                        .findFirst()
                        .orElseThrow();
        require(card.length == null, "This fixture must retain the actual unknown gzip extent");
        ArchiveLaunch launch = gallery.archiveLaunch(card, () -> false);
        require(
                launch.offset == 37
                        && launch.length == blob.size() - 37
                        && launch.findingIndex == card.nativeRow,
                "Wrong card parsing interval");
        JSONObject protocol = launch.json();
        require(protocol.isNull("recoveredExtentBytes"), "Parsing tail became a recovered extent");
        JSONObject finding = new JSONObject(protocol.getString("findingRecordRaw"));
        require(finding.isNull("reportedSizeBytes"), "Original unknown finding length changed");
        require(
                protocol.getJSONObject("galleryContext").getJSONObject("card").isNull("sizeBytes"),
                "Original card extent changed");
        require(
                protocol.getJSONObject("galleryContext")
                        .getJSONObject("gallery")
                        .getJSONObject("source")
                        .getString("name")
                        .equals(display),
                "Gallery context lost UTF-16 name");
        ContentMap.Card bad =
                new ContentMap.Card(
                        card.kind,
                        card.label,
                        card.format,
                        card.offset + 1,
                        card.length,
                        "",
                        card.nativeRow);
        refuse(() -> gallery.archiveLaunch(bad, () -> false), "mismatched original card/witness");
        refuse(() -> gallery.archiveLaunch(card, () -> true), "cancelled card launch");
        require(archiveCalls.get() == 0, "Card validation invoked native archive parsing");
        ContentMap.Card chosen = ContentMap.Card.inspection(card, gallery.input.size, 38, 3);
        require(
                chosen.explicitInterval && chosen.nativeRow == card.nativeRow,
                "Manual scope lost its associated witness");
        require(
                chosen.json().getString("extentMeaning").startsWith("Explicit user-selected"),
                "Manual scope claims a native extent");
        ArchiveLaunch manual = gallery.archiveLaunch(chosen, () -> false);
        require(
                manual.findingKind == null && manual.offset == 38 && manual.length == 3,
                "Manual interval became original finding geometry");
        require(
                new JSONObject(
                                manual.json()
                                        .getJSONObject("galleryContext")
                                        .getString("associatedFindingRaw"))
                        .isNull("reportedSizeBytes"),
                "Manual scope changed the associated raw finding");
        try (InputSnapshot selected =
                GallerizeSession.stageRange(
                        gallery.input,
                        chosen.range(gallery.input.size),
                        root.resolve("previews").toFile(),
                        () -> false)) {
            require(
                    Arrays.equals(
                            Arrays.copyOfRange(blob.toByteArray(), 38, 41),
                            Files.readAllBytes(selected.file.toPath())),
                    "Manual inspection bytes differ");
        }
        refuse(() -> ContentMap.Card.inspection(card, gallery.input.size, -1, 1), "negative range");
        refuse(
                () -> ContentMap.Card.inspection(card, gallery.input.size, gallery.input.size, 1),
                "range past EOF");
        refuse(
                () -> ContentMap.Card.inspection(card, gallery.input.size, 0, 0),
                "empty manual range");
        refuse(
                () ->
                        ContentMap.Card.inspection(
                                card, Long.MAX_VALUE, 0, FileRange.MAX_OPERATION_BYTES + 1),
                "manual range budget");
        try (Writer out =
                new OutputStreamWriter(
                        Files.newOutputStream(root.resolve("gallery-report.json")),
                        StandardCharsets.UTF_8
                                .newEncoder()
                                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT))) {
            gallery.writeReport(out, () -> false);
        }
        NativeArchiveSession archive =
                NativeArchiveSession.open(
                        gallery.input.file,
                        display,
                        root.resolve("archives").toFile(),
                        launch.offset,
                        launch.length,
                        NATIVE,
                        launch,
                        () -> false);
        require(
                archive.current().inventory.complete,
                "Actual archive inspection failed: " + archive.current().inventory.failure);
        require(archive.current().inventory.members.size() == 1, "Unexpected decoded member count");
        String retained = archive.retentionState();
        archive.close(true);
        gallery.close();
        require(original.delete(), "Original source deletion failed");
        ArchiveRunner noRescan =
                new ArchiveRunner() {
                    public String inspect(File f, long o, long l, BooleanSupplier s) {
                        throw new AssertionError("Restoration rescanned");
                    }

                    public String extract(
                            File f, File t, long o, long l, int m, BooleanSupplier s) {
                        throw new AssertionError("Restoration extracted");
                    }
                };
        NativeArchiveSession captured =
                NativeArchiveSession.restore(
                        root.resolve("archives").toFile(), retained, noRescan, () -> false);
        require(
                captured.launch.raw.equals(launch.raw),
                "Native restoration changed the full gallery launch");
        captured.close(true);
        NativeArchiveSession active =
                NativeArchiveSession.restore(
                        root.resolve("archives").toFile(), retained, NATIVE, () -> false);
        ContentOrigin origin;
        try (NativeArchiveStore.Acquired member =
                active.acquire(
                        active.current().inventory.members.get(0),
                        root.resolve("members").toFile(),
                        () -> false)) {
            require(
                    Arrays.equals(payload, Files.readAllBytes(member.input.file.toPath()))
                            && member.input.sha256.equals(sha(payload)),
                    "Actual decoded member differs from independent bytes");
            origin =
                    ContentOrigin.create(
                            root.resolve("origins").toFile(),
                            member.input,
                            ContentOrigin.member(active.current().input, 0),
                            records -> {
                                records.json(
                                        "archiveTrail",
                                        out -> active.writeCapturedEvidence(out, () -> false));
                                records.value(
                                        "selectedMember",
                                        new JSONObject()
                                                .put(
                                                        "acquisitionRawJson",
                                                        member.extractionReport));
                            },
                            () -> false);
        }
        active.close();
        PreparedExport export = origin.export(root.resolve("exports").toFile(), () -> false);
        origin.close();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        export.copyTo(bytes, () -> false);
        Files.write(root.resolve("origin.ndjson"), bytes.toByteArray());
        export.discard();
        require(
                photoCalls.get() == 1 && archiveCalls.get() == 2,
                "Unexpected rescan/parser call count");
        System.out.println(
                "Actual PhotoRec unknown-extent gzip → gallery card → source-bound libarchive"
                    + " member passed exact independent bytes, manual scope separation,"
                    + " stale/cancel/budget refusals, UTF-16 context, source expiry, no-rescan"
                    + " restore and complete origin export.");
    }
}
