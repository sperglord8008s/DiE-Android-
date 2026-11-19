// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exact aligned byte comparison. No insertion alignment, executable semantics or fuzzy score. */
final class BinaryComparison {
    static final String SCHEMA = "org.diemobile.binary-comparison";
    static final int VERSION = 1, MAX_RANGES = 100_000, PREFIX_BYTES = 32;
    static final long MAX_SOURCE_BYTES = 256L * 1024 * 1024;
    static final long MAX_NANOS = 30_000_000_000L;

    static final class Range {
        final int index;
        final String kind, leftPrefixHex, rightPrefixHex;
        final long offset, leftLength, rightLength;

        Range(
                int index,
                String kind,
                long offset,
                long leftLength,
                long rightLength,
                byte[] leftPrefix,
                byte[] rightPrefix) {
            this.index = index;
            this.kind = kind;
            this.offset = offset;
            this.leftLength = leftLength;
            this.rightLength = rightLength;
            leftPrefixHex = Inspector.toHex(leftPrefix);
            rightPrefixHex = Inspector.toHex(rightPrefix);
        }

        JSONObject json() throws JSONException {
            return new JSONObject()
                    .put("rangeIndex", index)
                    .put("kind", kind)
                    .put("alignedPhysicalOffset", offset)
                    .put("left", side(leftLength, leftPrefixHex))
                    .put("right", side(rightLength, rightPrefixHex));
        }

        private JSONObject side(long length, String prefix) throws JSONException {
            return new JSONObject()
                    .put("physicalOffset", length == 0 ? JSONObject.NULL : offset)
                    .put("sizeBytes", length)
                    .put("prefixHex", prefix)
                    .put("prefixBytes", prefix.length() / 2)
                    .put("prefixIsCompleteRange", length <= PREFIX_BYTES);
        }
    }

    final long leftSize, rightSize, equalBytes, differentBytes, elapsedMs;
    final String leftSha256, rightSha256, pairId, comparedAt;
    final List<Range> ranges;

    private BinaryComparison(
            InputSnapshot left,
            InputSnapshot right,
            long equal,
            long different,
            List<Range> ranges,
            long started)
            throws Exception {
        leftSize = left.size;
        rightSize = right.size;
        leftSha256 = left.sha256;
        rightSha256 = right.sha256;
        equalBytes = equal;
        differentBytes = different;
        this.ranges = Collections.unmodifiableList(ranges);
        elapsedMs = (System.nanoTime() - started) / 1_000_000;
        comparedAt = java.time.Instant.now().toString();
        pairId =
                Inspector.toHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(
                                        ("aligned-byte-runs/v1:"
                                                        + leftSize
                                                        + ":"
                                                        + leftSha256
                                                        + ":"
                                                        + rightSize
                                                        + ":"
                                                        + rightSha256)
                                                .getBytes(StandardCharsets.US_ASCII)));
    }

    static BinaryComparison compare(InputSnapshot left, InputSnapshot right, BooleanSupplier stop)
            throws Exception {
        long started = System.nanoTime();
        check(stop, started);
        for (InputSnapshot input : new InputSnapshot[] {left, right})
            if (input.size < 0
                    || input.size > MAX_SOURCE_BYTES
                    || !input.file.isFile()
                    || input.file.length() != input.size)
                throw new IOException("Comparison source unavailable / exceeds 256 MiB");
        MessageDigest leftDigest = MessageDigest.getInstance("SHA-256"),
                rightDigest = MessageDigest.getInstance("SHA-256");
        byte[] leftBytes = new byte[65536], rightBytes = new byte[65536];
        List<Range> ranges = new ArrayList<>();
        long common = Math.min(left.size, right.size), maximum = Math.max(left.size, right.size);
        long same = 0, different = 0, runStart = -1;
        ByteArrayOutputStream runLeft = new ByteArrayOutputStream(PREFIX_BYTES),
                runRight = new ByteArrayOutputStream(PREFIX_BYTES);
        ByteArrayOutputStream tailLeft = new ByteArrayOutputStream(PREFIX_BYTES),
                tailRight = new ByteArrayOutputStream(PREFIX_BYTES);
        try (DataInputStream leftInput = new DataInputStream(new FileInputStream(left.file));
                DataInputStream rightInput = new DataInputStream(new FileInputStream(right.file))) {
            for (long position = 0; position < maximum; position += leftBytes.length) {
                check(stop, started);
                int leftCount = (int) Math.min(leftBytes.length, Math.max(0, left.size - position));
                int rightCount =
                        (int) Math.min(rightBytes.length, Math.max(0, right.size - position));
                leftInput.readFully(leftBytes, 0, leftCount);
                rightInput.readFully(rightBytes, 0, rightCount);
                leftDigest.update(leftBytes, 0, leftCount);
                rightDigest.update(rightBytes, 0, rightCount);
                int paired = Math.min(leftCount, rightCount);
                for (int i = 0; i < paired; i++) {
                    if ((i & 4095) == 0) check(stop, started);
                    long offset = position + i;
                    if (leftBytes[i] != rightBytes[i]) {
                        different++;
                        if (runStart < 0) {
                            runStart = offset;
                            runLeft.reset();
                            runRight.reset();
                        }
                        if (runLeft.size() < PREFIX_BYTES) {
                            runLeft.write(leftBytes[i]);
                            runRight.write(rightBytes[i]);
                        }
                    } else {
                        same++;
                        if (runStart >= 0) {
                            add(
                                    ranges,
                                    "different-byte-run",
                                    runStart,
                                    offset - runStart,
                                    offset - runStart,
                                    runLeft,
                                    runRight);
                            runStart = -1;
                        }
                    }
                }
                if (position + paired >= common && runStart >= 0) {
                    add(
                            ranges,
                            "different-byte-run",
                            runStart,
                            common - runStart,
                            common - runStart,
                            runLeft,
                            runRight);
                    runStart = -1;
                }
                if (leftCount > paired) prefix(tailLeft, leftBytes, paired, leftCount - paired);
                if (rightCount > paired) prefix(tailRight, rightBytes, paired, rightCount - paired);
            }
            if (leftInput.read() != -1 || rightInput.read() != -1)
                throw new IOException("Comparison input grew during reading");
        }
        if (left.size != right.size)
            add(
                    ranges,
                    left.size > right.size ? "left-only-tail" : "right-only-tail",
                    common,
                    left.size - common,
                    right.size - common,
                    tailLeft,
                    tailRight);
        check(stop, started);
        if (left.file.length() != left.size
                || right.file.length() != right.size
                || !left.sha256.equals(Inspector.toHex(leftDigest.digest()))
                || !right.sha256.equals(Inspector.toHex(rightDigest.digest())))
            throw new IOException("Comparison source SHA-256 changed; no result adopted");
        if (same + different != common)
            throw new IOException("Comparison coverage invariant failed");
        return new BinaryComparison(left, right, same, different, ranges, started);
    }

    private static void prefix(ByteArrayOutputStream output, byte[] bytes, int offset, int count) {
        output.write(bytes, offset, Math.min(count, PREFIX_BYTES - output.size()));
    }

    private static void add(
            List<Range> ranges,
            String kind,
            long offset,
            long leftLength,
            long rightLength,
            ByteArrayOutputStream left,
            ByteArrayOutputStream right)
            throws IOException {
        if (ranges.size() >= MAX_RANGES)
            throw new IOException(
                    "Comparison exceeds 100,000 exact difference ranges; no partial result"
                            + " adopted");
        ranges.add(
                new Range(
                        ranges.size(),
                        kind,
                        offset,
                        leftLength,
                        rightLength,
                        left.toByteArray(),
                        right.toByteArray()));
    }

    private static void check(BooleanSupplier stop, long started) throws IOException {
        EvidenceRows.check(stop);
        if (System.nanoTime() - started > MAX_NANOS)
            throw new IOException("Comparison exceeds 30-second cooperative processing budget");
    }

    JSONObject metadata(String leftName, String rightName) throws JSONException {
        return new JSONObject()
                .put("schema", SCHEMA)
                .put("schemaVersion", VERSION)
                .put("algorithm", "aligned-byte-runs/v1")
                .put("pairId", pairId)
                .put("comparedAt", comparedAt)
                .put("elapsedMs", elapsedMs)
                .put("complete", true)
                .put("left", input(leftName, leftSize, leftSha256))
                .put("right", input(rightName, rightSize, rightSha256))
                .put("commonBytes", Math.min(leftSize, rightSize))
                .put("equalCommonBytes", equalBytes)
                .put("differentCommonBytes", differentBytes)
                .put("leftOnlyTailBytes", Math.max(0, leftSize - rightSize))
                .put("rightOnlyTailBytes", Math.max(0, rightSize - leftSize))
                .put("rightMinusLeftSizeBytes", rightSize - leftSize)
                .put("identicalBytes", leftSize == rightSize && differentBytes == 0)
                .put("rangeCount", ranges.size())
                .put("networkUsed", false)
                .put("limits", limits())
                .put(
                        "limitations",
                        new JSONArray()
                                .put(
                                        "Bytes are compared at the same physical offset. No"
                                                + " insertion/deletion alignment or moved-block"
                                                + " recognition is performed.")
                                .put(
                                        "A middle insertion can change all subsequent aligned"
                                            + " bytes; a one-sided tail is a length observation,"
                                            + " not proof of an edit operation.")
                                .put(
                                        "Ranges coalesce consecutive unequal paired bytes; only"
                                            + " their first 32 bytes are previews. Exact complete"
                                            + " ranges remain independently exportable.")
                                .put(
                                        "No executable/DEX/package/signature semantic comparison,"
                                                + " patch synthesis, fuzzy similarity, execution or"
                                                + " safety verdict is supplied.")
                                .put(
                                        "Private-file I/O and cancellation/deadlines are"
                                            + " cooperative; device/provider/power-loss behavior"
                                            + " remains unverified."));
    }

    static JSONObject input(String name, long size, String sha) throws JSONException {
        return new JSONObject().put("name", name).put("sizeBytes", size).put("sha256", sha);
    }

    static JSONObject limits() throws JSONException {
        return new JSONObject()
                .put("sourceBytes", MAX_SOURCE_BYTES)
                .put("pairBytes", 2 * MAX_SOURCE_BYTES)
                .put("differenceRanges", MAX_RANGES)
                .put("prefixBytes", PREFIX_BYTES)
                .put("processingSeconds", MAX_NANOS / 1_000_000_000L);
    }

    void writeReport(Writer output, String leftName, String rightName, BooleanSupplier stop)
            throws Exception {
        StringWriter header = new StringWriter();
        EvidenceJson.write(header, metadata(leftName, rightName), stop);
        String value = header.toString();
        output.write(value, 0, value.length() - 1);
        output.write(",\"ranges\":[");
        for (Range range : ranges) {
            EvidenceRows.check(stop);
            if (range.index > 0) output.write(',');
            EvidenceJson.write(output, range.json(), stop);
        }
        EvidenceRows.check(stop);
        output.write("]}");
    }
}
