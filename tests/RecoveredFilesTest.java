// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Payload/MIME/ZIP/picker ownership oracles, separate from native boundary discovery. */
public final class RecoveredFilesTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        File exports = root.resolve("exports").toFile();
        byte[][] payloads = {
            MetadataFixtureFactory.jpeg(),
            MetadataFixtureFactory.png(),
            MetadataFixtureFactory.wav(),
            "Recovered text 世界 😀\n".getBytes(StandardCharsets.UTF_8)
        };
        String[] extensions = {"jpg", "png", "wav", "utf-8"};
        ContentMap.Kind[] kinds = {
            ContentMap.Kind.IMAGE,
            ContentMap.Kind.IMAGE,
            ContentMap.Kind.AUDIO,
            ContentMap.Kind.TEXT
        };
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        blob.write(new byte[37]);
        List<ContentMap.Card> cards = new ArrayList<>();
        JSONObject oracle = new JSONObject();
        for (int i = 0; i < payloads.length; i++) {
            ContentMap.Card card =
                    new ContentMap.Card(
                            kinds[i],
                            "Untrusted ../label",
                            extensions[i],
                            blob.size(),
                            (long) payloads[i].length,
                            "",
                            i);
            cards.add(card);
            blob.write(payloads[i]);
            blob.write(new byte[17]);
            Files.write(root.resolve("expected-" + i), payloads[i]);
            oracle.put(RecoveredFiles.name(card), i);
        }
        cards.add(
                new ContentMap.Card(
                        ContentMap.Kind.IMAGE,
                        "Second native witness",
                        "jpeg",
                        cards.get(0).offset,
                        cards.get(0).length,
                        "",
                        19));
        cards.add(
                new ContentMap.Card(ContentMap.Kind.IMAGE, "Header alone", "jpg", 3, null, "", 23));
        Path source = root.resolve("mixed.blob");
        Files.write(source, blob.toByteArray());
        try (InputSnapshot input =
                InputSnapshot.acquire(
                        source.toFile(),
                        root.resolve("inputs").toFile(),
                        blob.size(),
                        () -> false)) {
            PreparedExport image = RecoveredFiles.file(input, cards.get(0), exports, () -> false);
            require(
                    image.name.endsWith(".jpg") && image.mime.equals("image/jpeg"),
                    "JPEG exported as generic binary");
            require(
                    Arrays.equals(payloads[0], Files.readAllBytes(image.file.toPath())),
                    "Single file bytes differ");
            image.discard();
            PreparedExport zip = RecoveredFiles.zip(input, cards, exports, () -> false);
            require(zip.mime.equals("application/zip"), "Bundle MIME differs");
            byte[] expectedZip = Files.readAllBytes(zip.file.toPath());
            try (ZipFile archive = new ZipFile(zip.file)) {
                require(
                        archive.size() == 5,
                        "Payloads duplicated/dropped or headers exported as files");
                for (int i = 0; i < payloads.length; i++) {
                    byte[] got =
                            archive.getInputStream(
                                            archive.getEntry(RecoveredFiles.name(cards.get(i))))
                                    .readAllBytes();
                    require(
                            Arrays.equals(got, payloads[i]),
                            "ZIP entry changed actual payload " + i);
                }
                JSONObject manifest =
                        new JSONObject(
                                new String(
                                        archive.getInputStream(archive.getEntry("_recovery.json"))
                                                .readAllBytes(),
                                        StandardCharsets.UTF_8));
                require(
                        manifest.getJSONArray("files").length() == 4,
                        "Wrong manifest payload count");
                require(
                        manifest.getJSONArray("files")
                                        .getJSONObject(0)
                                        .getJSONArray("discoveries")
                                        .length()
                                == 2,
                        "Duplicate witness lost");
                require(
                        manifest.getString("sourceSha256").equals(input.sha256),
                        "Input identity lost");
            }
            Files.copy(zip.file.toPath(), root.resolve("recovered-files.zip"));
            Files.writeString(root.resolve("oracle.json"), oracle.toString());
            input.close();
            ByteArrayOutputStream copied = new ByteArrayOutputStream();
            zip.copyTo(copied, () -> false);
            require(
                    Arrays.equals(expectedZip, copied.toByteArray()),
                    "Prepared actual files changed after input expiry");
            zip.discard();
        }
        try (InputSnapshot input =
                InputSnapshot.acquire(
                        source.toFile(),
                        root.resolve("inputs").toFile(),
                        blob.size(),
                        () -> false)) {
            try {
                RecoveredFiles.zip(input, cards, exports, () -> true);
                throw new AssertionError("Cancelled ZIP published");
            } catch (InterruptedIOException expected) {
            }
            AtomicBoolean mutated = new AtomicBoolean();
            try {
                RecoveredFiles.zip(
                        input,
                        cards,
                        exports,
                        () -> {
                            File[] stages = exports.listFiles();
                            if (stages != null
                                    && Arrays.stream(stages).anyMatch(f -> f.length() > 0)
                                    && mutated.compareAndSet(false, true)) {
                                try (RandomAccessFile file =
                                        new RandomAccessFile(input.file, "rw")) {
                                    file.write(99);
                                } catch (IOException error) {
                                    throw new UncheckedIOException(error);
                                }
                            }
                            return false;
                        });
                throw new AssertionError("Mutated source published a ZIP");
            } catch (IOException expected) {
            }
            require(mutated.get(), "Mutation fixture did not exercise the write phase");
            require(exports.list().length == 0, "Failed export leaked private staging");
        }
        require(
                RecoveredFiles.extension("../../bad\0name").equals("bin"),
                "Native label escaped into a path");
        System.out.println(
                "Exact JPEG/PNG/WAV/UTF-8 file payloads, correct names/MIME, ZIP content/witnesses,"
                        + " source-expiry ownership and cancellation/mutation cleanup passed.");
    }
}
