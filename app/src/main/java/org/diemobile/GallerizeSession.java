// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.util.function.BooleanSupplier;

/**
 * Owns one immutable input and both original native captures, independently of the parent screen.
 */
final class GallerizeSession implements AutoCloseable {
    final InputSnapshot input;
    final String name;
    private final GalleryDiscovery photoRec, binwalk;
    private ContentMap map;

    private GallerizeSession(
            InputSnapshot input,
            String name,
            File directory,
            BinaryRunner photoRecRunner,
            BinaryRunner binwalkRunner) {
        this.input = input;
        this.name = name;
        photoRec = new GalleryDiscovery("photorec", directory, photoRecRunner);
        binwalk = new GalleryDiscovery("binwalk", directory, binwalkRunner);
    }

    /** Legacy callers retain their PhotoRec-only capture; new galleries use both engines. */
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
        return open(
                source, directory, name, expectedSha, expectedSize, retained, runner, null, stop);
    }

    static GallerizeSession open(
            File source,
            File directory,
            String name,
            String expectedSha,
            long expectedSize,
            String retained,
            BinaryRunner runner,
            BinaryRunner binwalkRunner,
            BooleanSupplier stop)
            throws Exception {
        InputSnapshot input = null;
        try {
            if (name == null
                    || name.length() > 16384
                    || retained != null && retained.length() > 128 * 1024)
                throw new IOException(
                        "Gallerize name exceeds 16 Ki-characters or state exceeds 128"
                                + " Ki-characters");
            JSONObject state = retained == null ? null : new JSONObject(retained);
            if (state == null)
                input =
                        InputSnapshot.acquire(
                                source, directory, PhotoRecReport.MAX_INPUT_BYTES, stop);
            else {
                if (!state.getString("schema").equals("org.diemobile.gallerize-session")
                        || (state.getInt("schemaVersion") != 1
                                && state.getInt("schemaVersion") != 2))
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
            GallerizeSession session =
                    new GallerizeSession(
                            input,
                            name,
                            directory,
                            runner,
                            state != null && !state.has("binwalk") ? null : binwalkRunner);
            if (state != null && !state.has("binwalk") && binwalkRunner != null)
                binwalkRunner.close();
            if (state != null) {
                session.photoRec.restore(state, stop);
                if (state.has("binwalk"))
                    session.binwalk.restore(state.getJSONObject("binwalk"), stop);
            }
            return session;
        } catch (Exception error) {
            if (input != null && retained == null) input.close();
            try {
                if (runner != null) runner.close();
                if (binwalkRunner != null) binwalkRunner.close();
            } catch (Exception close) {
                error.addSuppressed(close);
            }
            throw error;
        }
    }

    ContentMap digest(BooleanSupplier stop) throws Exception {
        if (map != null) return map;
        photoRec.load(input, name, stop);
        binwalk.load(input, name, stop);
        java.util.List<ContentMap.Source> discoveries = new java.util.ArrayList<>();
        if (photoRec.rows != null)
            discoveries.add(new ContentMap.Source("photorec", photoRec.rows));
        if (binwalk.rows != null) discoveries.add(new ContentMap.Source("binwalk", binwalk.rows));
        ContentMap result = ContentMap.build(input, name, discoveries, stop);
        input.checkIdentity(stop);
        map = result;
        return result;
    }

    String retentionState() throws Exception {
        JSONObject state = photoRec.state();
        state.put("schema", "org.diemobile.gallerize-session")
                .put("schemaVersion", 2)
                .put("inputPath", input.file.getAbsolutePath())
                .put("sizeBytes", input.size)
                .put("sha256", input.sha256)
                .put("binwalk", binwalk.state());
        return state.toString();
    }

    String statusSummary() {
        return "PhotoRec · " + photoRec.status() + "\nBinwalk · " + binwalk.status();
    }

    static InputSnapshot stageRange(
            InputSnapshot source, FileRange range, File directory, BooleanSupplier stop)
            throws Exception {
        return OwnedContent.stageRange(source, range, directory, stop);
    }

    ArchiveLaunch archiveLaunch(ContentMap.Card card, BooleanSupplier stop) throws Exception {
        input.checkIdentity(stop);
        JSONObject context = new JSONObject().put("card", card.json()).put("gallery", provenance());
        org.json.JSONArray witnesses = findings(card, stop);
        context.put("nativeFindings", witnesses);
        EvidenceRows.Row finding = primaryFinding(card, stop);
        if (finding != null) context.put("associatedFindingRaw", finding.encoded);
        ArchiveLaunch launch;
        if (finding != null && !card.explicitInterval)
            launch =
                    ArchiveLaunch.fromFinding(
                            input,
                            discovery(card.nativeEngine).rows.kind,
                            finding,
                            discovery(card.nativeEngine).backend.provenance());
        else {
            FileRange range = card.range(input.size);
            launch = ArchiveLaunch.forInput(input.sha256, input.size, range.offset, range.length);
        }
        launch = launch.withGalleryContext(context);
        input.checkIdentity(stop);
        return launch;
    }

    private GalleryDiscovery discovery(String engine) throws IOException {
        if ("photorec".equals(engine)) return photoRec;
        if ("binwalk".equals(engine)) return binwalk;
        throw new IOException("Unknown gallery finding engine");
    }

    EvidenceRows raw() throws IOException {
        return raw("photorec");
    }

    EvidenceRows raw(String engine) throws IOException {
        EvidenceRows rows = discovery(engine).rows;
        if (rows == null) throw new IOException("No completed " + engine + " capture");
        return rows;
    }

    private EvidenceRows.Row checkedFinding(ContentMap.NativeRef reference, BooleanSupplier stop)
            throws Exception {
        EvidenceRows rows = raw(reference.engine);
        if (reference.row < 0 || reference.row >= rows.size())
            throw new IOException("Gallery native witness index expired");
        EvidenceRows.Row finding = rows.row(reference.row, stop);
        if (finding.index != reference.row
                || finding.physicalOffset == null
                || finding.physicalOffset != reference.offset
                || !java.util.Objects.equals(finding.sizeBytes, reference.length))
            throw new IOException("Gallery witness differs from its retained native finding");
        return finding;
    }

    EvidenceRows.Row primaryFinding(ContentMap.Card card, BooleanSupplier stop) throws Exception {
        if (card.findings.isEmpty()) return null;
        EvidenceRows.Row finding = checkedFinding(card.findings.get(0), stop);
        if (!card.explicitInterval
                && (finding.physicalOffset != card.offset
                        || !java.util.Objects.equals(finding.sizeBytes, card.length)))
            throw new IOException("Gallery card differs from its primary native finding");
        return finding;
    }

    org.json.JSONArray findings(ContentMap.Card card, BooleanSupplier stop) throws Exception {
        org.json.JSONArray witnesses = new org.json.JSONArray();
        primaryFinding(card, stop);
        for (ContentMap.NativeRef reference : card.findings) {
            EvidenceRows.Row row = checkedFinding(reference, stop);
            witnesses.put(
                    reference
                            .json(card.offset, card.length, card.explicitInterval)
                            .put("originalRecordJson", row.encoded));
        }
        return witnesses;
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
                        photoRec.captureIdentity() == null
                                ? JSONObject.NULL
                                : photoRec.captureIdentity())
                .put(
                        "discoveries",
                        new org.json.JSONArray()
                                .put(photoRec.provenance())
                                .put(binwalk.provenance()))
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
                .put(
                        "native",
                        photoRec.backend == null ? JSONObject.NULL : photoRec.backend.provenance());
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
        writeNative(output, photoRec, false, stop);
        output.write(",\"nativeCatalog\":");
        writeNative(output, photoRec, true, stop);
        output.write(",\"binwalkNativeEvidence\":");
        writeNative(output, binwalk, false, stop);
        output.write(",\"binwalkNativeCatalog\":");
        writeNative(output, binwalk, true, stop);
        output.write('}');
    }

    private static void writeNative(
            Writer output, GalleryDiscovery discovery, boolean catalog, BooleanSupplier stop)
            throws Exception {
        EvidenceRows rows = catalog ? discovery.catalog : discovery.rows;
        if (rows == null) output.write("null");
        else rows.writeReport(output, discovery.backend.provenance(), "", 0, stop);
    }

    void close(boolean retain) throws Exception {
        try {
            photoRec.close(retain);
        } finally {
            try {
                binwalk.close(retain);
            } finally {
                if (!retain) input.close();
            }
        }
    }

    @Override
    public void close() throws Exception {
        close(false);
    }
}
