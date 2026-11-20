// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/** Owns one immutable input and its original native capture, independently of the parent screen. */
final class GallerizeSession implements AutoCloseable {
    final InputSnapshot input;
    final String name;
    private final File directory;
    private final BinaryRunner runner;
    private PhotoRecBackend backend;
    private File capture;
    private String captureSha;
    private long captureSize;
    private ContentMap map;
    private EvidenceRows raw, catalog;

    private GallerizeSession(
            InputSnapshot input, String name, File directory, BinaryRunner runner) {
        this.input = input;
        this.name = name;
        this.directory = directory;
        this.runner = runner;
    }

    static GallerizeSession open(
            File source,
            File directory,
            String name,
            String expectedSha,
            long expectedSize,
            String retained,
            BinaryRunner runner,
            BooleanSupplier stop)
            throws Exception {
        InputSnapshot input = null;
        try {
            if (name == null
                    || name.length() > 16384
                    || retained != null && retained.length() > 16384)
                throw new IOException("Gallerize name/state exceeds 16 Ki-characters");
            JSONObject state = retained == null ? null : new JSONObject(retained);
            if (state == null)
                input =
                        InputSnapshot.acquire(
                                source, directory, PhotoRecReport.MAX_INPUT_BYTES, stop);
            else {
                if (!state.getString("schema").equals("org.diemobile.gallerize-session")
                        || state.getInt("schemaVersion") != 1)
                    throw new IOException("Unsupported Gallerize session state");
                if (state.getLong("sizeBytes") < 0
                        || state.getLong("sizeBytes") > PhotoRecReport.MAX_INPUT_BYTES)
                    throw new IOException("Restored Gallerize input exceeds 256 MiB");
                input =
                        InputSnapshot.restore(
                                new File(state.getString("inputPath")),
                                directory,
                                state.getLong("sizeBytes"),
                                state.getString("sha256"),
                                stop);
            }
            if (expectedSha != null && !input.sha256.equals(expectedSha)
                    || expectedSize >= 0 && input.size != expectedSize)
                throw new IOException("Gallerize source differs from the selected scan identity");
            GallerizeSession session = new GallerizeSession(input, name, directory, runner);
            if (state != null && state.has("capturePath")) {
                File capture = new File(state.getString("capturePath")).getCanonicalFile();
                if (!directory.getCanonicalFile().equals(capture.getParentFile())
                        || !capture.getName().matches("gallerize-capture-[A-Za-z0-9._-]+\\.json"))
                    throw new IOException("Native capture escaped the private Gallerize directory");
                session.capture = capture;
                session.captureSha = state.getString("captureSha256");
                session.captureSize = state.getLong("captureSizeBytes");
                session.readCapture(
                        stop); // Restore must validate the original capture, never silently rescan.
            }
            return session;
        } catch (Exception error) {
            if (input != null && retained == null) input.close();
            try {
                runner.close();
            } catch (Exception close) {
                error.addSuppressed(close);
            }
            throw error;
        }
    }

    ContentMap digest(BooleanSupplier stop) throws Exception {
        if (map != null) return map;
        File previousCapture = capture;
        PhotoRecBackend candidate =
                new PhotoRecBackend(
                        input,
                        name,
                        (file, cancelled) -> {
                            if (capture != null) return readCapture(cancelled);
                            String encoded = runner.scan(file, cancelled);
                            if (encoded == null || encoded.length() > 32 * 1024 * 1024)
                                throw new IOException(
                                        "Native Gallerize capture exceeds 32 Mi-characters");
                            byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
                            if (bytes.length > 64 * 1024 * 1024)
                                throw new IOException("Native capture exceeds 64 MiB");
                            File stage =
                                    File.createTempFile("gallerize-capture-", ".json", directory);
                            try {
                                try (OutputStream output = new FileOutputStream(stage)) {
                                    output.write(bytes);
                                }
                                EvidenceRows.check(cancelled);
                                captureSha =
                                        Inspector.toHex(
                                                MessageDigest.getInstance("SHA-256").digest(bytes));
                                captureSize = bytes.length;
                                capture = stage;
                            } finally {
                                if (capture != stage) stage.delete();
                            }
                            return encoded;
                        });
        boolean success = false;
        try {
            EvidenceRows evidence = candidate.open("all", stop);
            EvidenceRows patterns = candidate.open("catalog", stop);
            ContentMap result = ContentMap.build(input, name, evidence, stop);
            input.checkIdentity(stop);
            backend = candidate;
            raw = evidence;
            catalog = patterns;
            map = result;
            success = true;
            return result;
        } finally {
            if (!success) {
                candidate.close();
                if (previousCapture == null && capture != null) {
                    capture.delete();
                    capture = null;
                }
            }
        }
    }

    private String readCapture(BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        if (captureSize < 0
                || captureSize > 64L * 1024 * 1024
                || !capture.isFile()
                || capture.length() != captureSize
                || captureSha == null
                || !captureSha.matches("[0-9a-f]{64}"))
            throw new IOException("Original native capture expired or exceeded its budget");
        byte[] bytes = Files.readAllBytes(capture.toPath());
        if (bytes.length != captureSize
                || !captureSha.equals(
                        Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
            throw new IOException("Original native capture identity changed");
        EvidenceRows.check(stop);
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    String retentionState() throws Exception {
        JSONObject state =
                new JSONObject()
                        .put("schema", "org.diemobile.gallerize-session")
                        .put("schemaVersion", 1)
                        .put("inputPath", input.file.getAbsolutePath())
                        .put("sizeBytes", input.size)
                        .put("sha256", input.sha256);
        if (capture != null)
            state.put("capturePath", capture.getAbsolutePath())
                    .put("captureSizeBytes", captureSize)
                    .put("captureSha256", captureSha);
        return state.toString();
    }

    String statusSummary() {
        return backend == null
                ? "Native content evidence has not completed"
                : backend.statusSummary();
    }

    static InputSnapshot stageRange(
            InputSnapshot source, FileRange range, File directory, BooleanSupplier stop)
            throws Exception {
        return OwnedContent.stageRange(source, range, directory, stop);
    }

    ArchiveLaunch archiveLaunch(ContentMap.Card card, BooleanSupplier stop) throws Exception {
        input.checkIdentity(stop);
        JSONObject context = new JSONObject().put("card", card.json()).put("gallery", provenance());
        EvidenceRows.Row finding = null;
        if (card.nativeRow >= 0) {
            EvidenceRows captured = raw();
            if (card.nativeRow >= captured.size())
                throw new IOException("Gallery native witness index expired");
            finding = captured.row(card.nativeRow, stop);
            if (finding.index != card.nativeRow
                    || !card.explicitInterval
                            && (finding.physicalOffset == null
                                    || finding.physicalOffset != card.offset
                                    || !java.util.Objects.equals(finding.sizeBytes, card.length)))
                throw new IOException("Gallery card differs from its retained native finding");
            context.put("associatedFindingRaw", finding.encoded);
        }
        ArchiveLaunch launch;
        if (finding != null && !card.explicitInterval)
            launch = ArchiveLaunch.fromFinding(input, raw().kind, finding, backend.provenance());
        else {
            FileRange range = card.range(input.size);
            launch = ArchiveLaunch.forInput(input.sha256, input.size, range.offset, range.length);
        }
        launch = launch.withGalleryContext(context);
        input.checkIdentity(stop);
        return launch;
    }

    EvidenceRows raw() throws IOException {
        if (raw == null) throw new IOException("No completed native capture");
        return raw;
    }

    JSONObject provenance() throws Exception {
        return new JSONObject()
                .put("schema", "org.diemobile.gallerize")
                .put("schemaVersion", 1)
                .put(
                        "source",
                        new JSONObject()
                                .put("name", name)
                                .put("sha256", input.sha256)
                                .put("sizeBytes", input.size))
                .put(
                        "nativeCapture",
                        capture == null
                                ? JSONObject.NULL
                                : new JSONObject()
                                        .put("sha256", captureSha)
                                        .put("sizeBytes", captureSize))
                .put(
                        "textScope",
                        "Strict printable UTF-8 runs of at least 8 code points outside the union of"
                                + " native bounded artifacts; UTF-16 and other encodings remain in"
                                + " classified strings")
                .put(
                        "limits",
                        new JSONObject()
                                .put("maximumInputBytes", PhotoRecReport.MAX_INPUT_BYTES)
                                .put("maximumCards", ContentMap.MAX_CARDS)
                                .put("textPreviewCodepoints", ContentMap.TEXT_PREVIEW_CODEPOINTS)
                                .put("unknownExtentsInferred", false)
                                .put("decodingProvesCompleteIntegrity", false)
                                .put("externalExtractorsExecuted", false))
                .put("native", backend == null ? JSONObject.NULL : backend.provenance());
    }

    void writeReport(Writer output, BooleanSupplier stop) throws Exception {
        ContentMap content = digest(stop);
        JSONObject header =
                provenance()
                        .put("primaryType", content.primaryType)
                        .put("composition", content.composition)
                        .put("cardCount", content.cards.size())
                        .put("rawNativeRowCount", content.rawCount)
                        .put("acceptedHeaderCount", content.acceptedCount)
                        .put("boundedArtifactCount", content.boundedCount)
                        .put("textCandidateCount", content.textCount)
                        .put("coveredBytes", content.coveredBytes)
                        .put("textScannedBytes", content.textScannedBytes);
        java.io.StringWriter prefix = new java.io.StringWriter();
        EvidenceJson.write(prefix, header, stop);
        String encoded = prefix.toString();
        output.write(encoded, 0, encoded.length() - 1);
        output.write(",\"cards\":[");
        for (int i = 0; i < content.cards.size(); i++) {
            EvidenceRows.check(stop);
            if (i > 0) output.write(',');
            EvidenceJson.write(output, content.cards.get(i).json(), stop);
        }
        output.write("],\"nativeEvidence\":");
        raw.writeReport(output, backend.provenance(), "", 0, stop);
        output.write(",\"nativeCatalog\":");
        catalog.writeReport(output, backend.provenance(), "", 0, stop);
        output.write('}');
    }

    void close(boolean retain) throws Exception {
        try {
            if (backend != null) backend.close();
        } finally {
            try {
                runner.close();
            } finally {
                if (!retain) {
                    input.close();
                    if (capture != null) capture.delete();
                }
            }
        }
    }

    @Override
    public void close() throws Exception {
        close(false);
    }
}
