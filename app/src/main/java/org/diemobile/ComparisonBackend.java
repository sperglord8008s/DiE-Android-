// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Owned two-source comparison. A failed replacement never changes the accepted pair. */
final class ComparisonBackend extends InvestigationBackend {
    private final File input, directory;
    private final String initialName, expectedSha, restoredState;
    private final long expectedSize;
    private File session;
    private InputSnapshot left, right;
    private String leftName, rightName = "", capturedAt;
    private BinaryComparison comparison;
    private boolean showRight;

    ComparisonBackend(
            File input,
            File directory,
            String name,
            String expectedSha,
            long expectedSize,
            String restoredState) {
        this.input = input;
        this.directory = directory;
        initialName = name;
        this.expectedSha = expectedSha;
        this.expectedSize = expectedSize;
        this.restoredState = restoredState;
    }

    private static String request(String table, String side, int range, String pair)
            throws JSONException {
        return new JSONObject()
                .put("table", table)
                .put("side", side)
                .put("range", range)
                .put("pairId", pair)
                .toString();
    }

    private void ensure(BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        if (left != null) return;
        InputSnapshot acquired = null, other = null;
        boolean adopted = false;
        try {
            if (restoredState == null) {
                validateName(initialName);
                if (!directory.isDirectory() && !directory.mkdirs())
                    throw new IOException("Cannot create comparison snapshot root");
                session =
                        Files.createTempDirectory(directory.toPath(), "compare-session-").toFile();
                leftName = initialName;
                acquired =
                        InputSnapshot.acquire(
                                input, session, BinaryComparison.MAX_SOURCE_BYTES, stop);
                capturedAt = java.time.Instant.now().toString();
            } else {
                String basename = new JSONObject(restoredState).getString("session");
                if (!basename.matches("compare-session-[A-Za-z0-9_-]{1,96}"))
                    throw new IOException("Invalid comparison session name");
                session = new File(directory, basename);
                if (!session.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())
                        || !session.getName().equals(session.getCanonicalFile().getName()))
                    throw new IOException("Comparison session escaped its owned directory");
                File manifest = new File(session, "source-state.json");
                if (!manifest.isFile() || manifest.length() > 128 * 1024)
                    throw new IOException("Comparison source state expired / exceeds 128 KiB");
                JSONObject state =
                        new JSONObject(
                                StandardCharsets.UTF_8
                                        .newDecoder()
                                        .decode(
                                                ByteBuffer.wrap(
                                                        Files.readAllBytes(manifest.toPath())))
                                        .toString());
                if (!"org.diemobile.comparison-inputs".equals(state.getString("schema"))
                        || state.getInt("schemaVersion") != 1)
                    throw new IOException("Invalid comparison source state schema");
                acquired = restore(state.getJSONObject("left"), stop);
                leftName = state.getString("leftName");
                validateName(leftName);
                capturedAt = state.getString("capturedAt");
                if (!state.isNull("right")) {
                    other = restore(state.getJSONObject("right"), stop);
                    rightName = state.getString("rightName");
                    validateName(rightName);
                }
            }
            if (expectedSize >= 0 && acquired.size != expectedSize
                    || expectedSha != null && !expectedSha.equals(acquired.sha256))
                throw new IOException(
                        "Left comparison source differs from the selected scan identity");
            BinaryComparison result =
                    other == null ? null : BinaryComparison.compare(acquired, other, stop);
            acquired.checkIdentity(stop);
            if (other != null) other.checkIdentity(stop);
            if (restoredState == null) persist(acquired, other, rightName, stop);
            EvidenceRows.check(stop);
            left = acquired;
            right = other;
            comparison = result;
            adopted = true;
        } finally {
            if (!adopted) {
                // Failed restoration does not destroy retained evidence needed for diagnosis/retry.
                if (restoredState == null) {
                    if (acquired != null) acquired.close();
                    if (other != null) other.close();
                    cleanup();
                }
            }
        }
    }

    private InputSnapshot restore(JSONObject source, BooleanSupplier stop) throws Exception {
        long size = source.getLong("sizeBytes");
        if (size < 0 || size > BinaryComparison.MAX_SOURCE_BYTES)
            throw new IOException("Restored comparison source exceeds 256 MiB");
        return InputSnapshot.restore(
                new File(source.getString("path")),
                session,
                size,
                source.getString("sha256"),
                stop);
    }

    @Override
    String initialRequest(BooleanSupplier stop) throws Exception {
        ensure(stop);
        return request("summary", "", -1, "");
    }

    @Override
    EvidenceRows open(String encoded, BooleanSupplier stop) throws Exception {
        ensure(stop);
        JSONObject query = new JSONObject(encoded);
        String table = query.getString("table"),
                side = query.optString("side", ""),
                pair = query.optString("pairId", "");
        showRight = false;
        JSONObject origin = provenance();
        if (!pair.isEmpty() && (comparison == null || !pair.equals(comparison.pairId)))
            return single(
                    "comparison-expired-scope",
                    "Previous comparison scope expired",
                    new JSONObject()
                            .put("name", "Requested range belongs to a previous pair")
                            .put("requestedPairId", pair)
                            .put(
                                    "currentPairId",
                                    comparison == null ? JSONObject.NULL : comparison.pairId)
                            .put(
                                    "explanation",
                                    "Select Root views to inspect the current accepted pair."
                                            + " Previous range ordinals are never applied to"
                                            + " replacement bytes."),
                    origin,
                    null,
                    null);
        if (table.equals("summary"))
            return single(
                    "comparison-summary",
                    "Binary comparison • complete pair identity / coverage",
                    summary(),
                    origin,
                    null,
                    null);
        if (table.equals("ranges")) {
            if (comparison == null)
                throw new IOException("Select a right-hand document before comparing");
            BinaryComparison captured = comparison;
            return new EvidenceRows(
                    "comparison-ranges",
                    "Binary comparison • every exact differing run / one-sided tail") {
                @Override
                int size() {
                    return captured.ranges.size();
                }

                @Override
                JSONObject metadata() {
                    return origin;
                }

                @Override
                Row row(int index, BooleanSupplier cancelled) throws Exception {
                    EvidenceRows.check(cancelled);
                    if (index < 0 || index >= size())
                        throw new IOException("Difference range outside captured pair");
                    BinaryComparison.Range range = captured.ranges.get(index);
                    return new Row(
                            index,
                            range.kind + " @ 0x" + Long.toHexString(range.offset),
                            "left="
                                    + range.leftLength
                                    + " bytes • right="
                                    + range.rightLength
                                    + " bytes • aligned physical coordinates",
                            null,
                            null,
                            range.json().put("pairId", captured.pairId));
                }
            };
        }
        if (!table.equals("input") && !table.equals("range"))
            throw new IOException("Unknown comparison table");
        InputSnapshot selected = side(side);
        selected.checkIdentity(stop);
        showRight = side.equals("right");
        JSONObject selectedOrigin = provenance();
        long offset = 0, length = selected.size;
        JSONObject fields =
                new JSONObject()
                        .put("name", showRight ? rightName : leftName)
                        .put("side", side)
                        .put("input", source(selected, showRight ? rightName : leftName))
                        .put(
                                "coordinateDomain",
                                "Exact captured whole-file physical offsets; input SHA-256 covers"
                                    + " the complete parent, not this selected range");
        if (table.equals("range")) {
            if (comparison == null) throw new IOException("Comparison range unavailable");
            int index = query.getInt("range");
            if (index < 0 || index >= comparison.ranges.size())
                throw new IOException("Comparison range index outside captured pair");
            BinaryComparison.Range range = comparison.ranges.get(index);
            offset = range.offset;
            length = showRight ? range.rightLength : range.leftLength;
            if (length == 0) throw new IOException("This side has no bytes in the one-sided tail");
            fields.put("range", range.json()).put("pairId", comparison.pairId);
        }
        new FileRange(selected.size, offset, length);
        return single(
                "comparison-" + table + "-" + side,
                "Comparison • " + side + " • " + table,
                fields,
                selectedOrigin,
                offset,
                length);
    }

    private static EvidenceRows single(
            String kind,
            String title,
            JSONObject fields,
            JSONObject metadata,
            Long offset,
            Long length) {
        return new EvidenceRows(kind, title) {
            @Override
            int size() {
                return 1;
            }

            @Override
            JSONObject metadata() {
                return metadata;
            }

            @Override
            Row row(int index, BooleanSupplier stop) throws Exception {
                EvidenceRows.check(stop);
                if (index != 0) throw new IOException("Comparison record outside selected scope");
                return new Row(
                        0,
                        fields.optString("name", title),
                        fields.optString("explanation", title),
                        offset,
                        length,
                        new JSONObject(fields.toString()));
            }
        };
    }

    private InputSnapshot side(String name) throws IOException {
        if (name.equals("left")) return left;
        if (name.equals("right") && right != null) return right;
        throw new IOException("Comparison input side unavailable");
    }

    private JSONObject summary() throws Exception {
        if (comparison != null)
            return comparison
                    .metadata(leftName, rightName)
                    .put("name", "Complete aligned byte comparison");
        return new JSONObject()
                .put("name", "Left input captured; select a right-hand document")
                .put("complete", false)
                .put("status", "awaiting-right-input")
                .put("left", source(left, leftName))
                .put("right", JSONObject.NULL)
                .put(
                        "explanation",
                        "Use Root views → Select / replace right-hand comparison input. No"
                                + " comparison has run.");
    }

    @Override
    List<Link> roots(String query, BooleanSupplier stop) throws Exception {
        ensure(stop);
        List<Link> links = new ArrayList<>();
        links.add(
                Link.view(
                        "Pair identity / whole-input coverage / limits",
                        request("summary", "", -1, "")));
        links.add(Link.input("Select / replace right-hand comparison input", "right"));
        links.add(
                Link.view(
                        "Left owned input / hex / exact bytes", request("input", "left", -1, "")));
        if (right != null) {
            links.add(
                    Link.view(
                            "Right owned input / hex / exact bytes",
                            request("input", "right", -1, "")));
            links.add(
                    Link.view(
                            "Every differing byte run / one-sided tail",
                            request("ranges", "", -1, comparison.pairId)));
            links.add(
                    Link.text(
                            "Preview / export complete comparison JSON",
                            "complete",
                            "die-binary-comparison.json"));
        }
        return links;
    }

    @Override
    List<Link> related(String query, EvidenceRows rows, EvidenceRows.Row row, BooleanSupplier stop)
            throws Exception {
        EvidenceRows.check(stop);
        JSONObject fields = row.json();
        List<Link> links = new ArrayList<>();
        if (rows.kind.equals("comparison-ranges")) {
            if (comparison == null || !comparison.pairId.equals(fields.getString("pairId")))
                throw new IOException("This row belongs to an expired comparison pair");
            int index = fields.getInt("rangeIndex");
            BinaryComparison.Range range = comparison.ranges.get(index);
            if (range.leftLength > 0)
                links.add(
                        Link.view(
                                "Left exact corresponding range / hex / export",
                                request("range", "left", index, comparison.pairId)));
            if (range.rightLength > 0)
                links.add(
                        Link.view(
                                "Right exact corresponding range / hex / export",
                                request("range", "right", index, comparison.pairId)));
        }
        if (rows.kind.startsWith("comparison-input-") || rows.kind.startsWith("comparison-range-"))
            side(fields.getString("side")).checkIdentity(stop);
        return links;
    }

    @Override
    void acceptInput(String request, InputStream input, String name, BooleanSupplier stop)
            throws Exception {
        ensure(stop);
        if (!request.equals("right") || input == null)
            throw new IOException("Expected a right-hand comparison document");
        validateName(name);
        InputSnapshot candidate =
                InputSnapshot.acquire(input, session, BinaryComparison.MAX_SOURCE_BYTES, stop);
        boolean adopted = false;
        try {
            BinaryComparison result = BinaryComparison.compare(left, candidate, stop);
            left.checkIdentity(stop);
            candidate.checkIdentity(stop);
            persist(left, candidate, name, stop);
            // Publication is the commit point; cancellation afterward cannot relabel the old pair.
            InputSnapshot previous = right;
            right = candidate;
            rightName = name;
            comparison = result;
            showRight = false;
            adopted = true;
            if (previous != null) previous.close();
        } finally {
            if (!adopted) candidate.close();
        }
    }

    @Override
    JSONObject provenance() throws Exception {
        return new JSONObject()
                .put("schema", BinaryComparison.SCHEMA)
                .put("schemaVersion", BinaryComparison.VERSION)
                .put("capturedAt", capturedAt)
                .put("algorithm", "aligned-byte-runs/v1")
                .put("pairId", comparison == null ? JSONObject.NULL : comparison.pairId)
                .put("left", source(left, leftName))
                .put("right", right == null ? JSONObject.NULL : source(right, rightName))
                .put("byteSourceSide", showRight ? "right" : "left")
                .put("limits", BinaryComparison.limits())
                .put("networkUsed", false);
    }

    @Override
    void writeText(String request, Writer output, BooleanSupplier stop) throws Exception {
        ensure(stop);
        if (!request.equals("complete") || comparison == null)
            throw new IOException("No complete comparison available");
        comparison.writeReport(output, leftName, rightName, stop);
    }

    private static JSONObject source(InputSnapshot value, String name) throws Exception {
        return BinaryComparison.input(name, value.size, value.sha256);
    }

    private void persist(
            InputSnapshot first, InputSnapshot second, String name, BooleanSupplier stop)
            throws Exception {
        JSONObject state =
                new JSONObject()
                        .put("schema", "org.diemobile.comparison-inputs")
                        .put("schemaVersion", 1)
                        .put("capturedAt", capturedAt)
                        .put("leftName", leftName)
                        .put("rightName", name)
                        .put("left", descriptor(first))
                        .put("right", second == null ? JSONObject.NULL : descriptor(second));
        File staged = File.createTempFile("source-state-", ".json", session);
        try {
            try (FileOutputStream file = new FileOutputStream(staged)) {
                Writer writer = new OutputStreamWriter(file, StandardCharsets.UTF_8.newEncoder());
                EvidenceJson.write(writer, state, stop);
                writer.flush();
                file.getFD().sync();
            }
            if (staged.length() > 128 * 1024)
                throw new IOException("Comparison source state exceeds 128 KiB");
            EvidenceRows.check(stop);
            Files.move(
                    staged.toPath(),
                    new File(session, "source-state.json").toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            staged.delete();
        }
    }

    private static JSONObject descriptor(InputSnapshot input) throws Exception {
        return new JSONObject()
                .put("path", input.file.getAbsolutePath())
                .put("sizeBytes", input.size)
                .put("sha256", input.sha256);
    }

    private static void validateName(String name) throws IOException {
        if (name == null || name.length() > 4096)
            throw new IOException("Comparison input label exceeds 4096 UTF-16 units");
    }

    @Override
    String retentionState() throws Exception {
        return left == null ? null : new JSONObject().put("session", session.getName()).toString();
    }

    @Override
    File byteSource() {
        return showRight && right != null ? right.file : left == null ? input : left.file;
    }

    @Override
    String sourceName() {
        return showRight && right != null ? rightName : leftName == null ? initialName : leftName;
    }

    @Override
    String help(String request) {
        return "Exact aligned byte comparison: paired bytes use the same physical offset."
                + " Consecutive unequal bytes coalesce; one-sided tails report length"
                + " differences. There is no insertion alignment, patch inference, fuzzy score"
                + " or semantic/behavior verdict. Each side has independent whole-file"
                + " SHA-256/length. Aggregate rows have no single-file offset; select a"
                + " corresponding left/right range before byte actions. Prefix hex is bounded to"
                + " 32 bytes; complete ranges/reports remain exportable. Failed/cancelled"
                + " replacement retains the prior pair.";
    }

    @Override
    public void close() {
        close(false);
    }

    @Override
    void close(boolean retainInputs) {
        if (retainInputs || left == null && restoredState != null) return;
        if (left != null) left.close();
        if (right != null) right.close();
        cleanup();
    }

    private void cleanup() {
        if (session == null) return;
        File[] files = session.listFiles();
        if (files != null)
            for (File file : files)
                if (file.getName().equals("source-state.json")
                        || file.getName().startsWith("source-state-")
                                && file.getName().endsWith(".json")
                        || file.getName().startsWith("analysis-input-")
                                && file.getName().endsWith(".snapshot")) file.delete();
        session.delete();
    }
}
