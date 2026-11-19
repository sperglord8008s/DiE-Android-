// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Independent naive byte oracle, real owned files, bounded failures and transactional replacement.
 */
public final class BinaryComparisonTest {
    private static File directory;
    private static int sources;

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void rejects(Action action, String message) throws Exception {
        try {
            action.run();
        } catch (IOException | JSONException | IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static InputSnapshot snapshot(byte[] bytes) throws Exception {
        return InputSnapshot.acquire(
                new ByteArrayInputStream(bytes),
                new File(directory, "snapshots-" + ++sources),
                BinaryComparison.MAX_SOURCE_BYTES,
                () -> false);
    }

    public static void main(String[] args) throws Exception {
        directory = new File(args[0]);
        oracleCases();
        denseAndBoundaryCases();
        cancelledChangedAndOversized();
        backendOwnershipAndReplacement();
        System.out.println(
                "Binary comparison passed: independent aligned-byte oracle,"
                    + " empty/identical/tails/insertions, 64 KiB seams, 100,000 ranges/full export,"
                    + " mutation/cancellation/budgets, exact source sides, transactional"
                    + " replacement, stale scopes and retained pair restoration.");
    }

    private static void oracleCases() throws Exception {
        compare(new byte[0], new byte[0]);
        compare(new byte[0], new byte[] {1, 2});
        compare(new byte[] {1, 2}, new byte[0]);
        compare(
                "ABCDEF".getBytes(StandardCharsets.US_ASCII),
                "ABXCDEF".getBytes(StandardCharsets.US_ASCII));
        Random random = new Random(671901);
        for (int i = 0; i < 80; i++) {
            byte[] left = new byte[random.nextInt(4096)], right;
            random.nextBytes(left);
            if ((i & 1) == 0) {
                right = Arrays.copyOf(left, random.nextInt(6000));
                for (int j = 0; j < right.length; j++)
                    if (random.nextInt(17) == 0) right[j] ^= (byte) random.nextInt(256);
            } else {
                right = new byte[random.nextInt(4096)];
                random.nextBytes(right);
            }
            compare(left, right);
            compare(right, left);
        }
    }

    private static void compare(byte[] left, byte[] right) throws Exception {
        try (InputSnapshot a = snapshot(left);
                InputSnapshot b = snapshot(right)) {
            BinaryComparison actual = BinaryComparison.compare(a, b, () -> false);
            List<long[]> expected = new ArrayList<>();
            int common = Math.min(left.length, right.length), same = 0;
            for (int i = 0; i < common; ) {
                if (left[i] == right[i]) {
                    same++;
                    i++;
                    continue;
                }
                int start = i++;
                while (i < common && left[i] != right[i]) i++;
                expected.add(new long[] {start, i - start, i - start});
            }
            if (left.length != right.length)
                expected.add(new long[] {common, left.length - common, right.length - common});
            require(
                    actual.equalBytes == same && actual.differentBytes == common - same,
                    "Paired byte counts differ from independent oracle");
            require(
                    actual.ranges.size() == expected.size(),
                    "Exact range count differs from oracle");
            for (int i = 0; i < expected.size(); i++) {
                long[] expectedRange = expected.get(i);
                BinaryComparison.Range found = actual.ranges.get(i);
                require(
                        found.index == i
                                && found.offset == expectedRange[0]
                                && found.leftLength == expectedRange[1]
                                && found.rightLength == expectedRange[2],
                        "Differing byte boundaries changed");
                byte[] prefixLeft =
                        Arrays.copyOfRange(
                                left,
                                (int) found.offset,
                                (int)
                                        (found.offset
                                                + Math.min(
                                                        BinaryComparison.PREFIX_BYTES,
                                                        found.leftLength)));
                byte[] prefixRight =
                        Arrays.copyOfRange(
                                right,
                                (int) found.offset,
                                (int)
                                        (found.offset
                                                + Math.min(
                                                        BinaryComparison.PREFIX_BYTES,
                                                        found.rightLength)));
                require(
                        found.leftPrefixHex.equals(Inspector.toHex(prefixLeft))
                                && found.rightPrefixHex.equals(Inspector.toHex(prefixRight)),
                        "Prefix bytes came from wrong side/range");
                String kind =
                        expectedRange[1] == 0
                                ? "right-only-tail"
                                : expectedRange[2] == 0 ? "left-only-tail" : "different-byte-run";
                require(found.kind.equals(kind), "One-sided tail was labeled as an inferred edit");
            }
            JSONObject metadata = actual.metadata("left\ud800\u0000", "right\udfff照片");
            require(
                    metadata.getBoolean("identicalBytes") == Arrays.equals(left, right),
                    "Identical status not based on every byte/length");
            require(
                    metadata.getLong("rightMinusLeftSizeBytes") == right.length - left.length,
                    "Signed size delta changed");
            require(
                    actual.leftSha256.equals(a.sha256) && actual.rightSha256.equals(b.sha256),
                    "Whole-input identities changed");
            StringWriter output = new StringWriter();
            actual.writeReport(output, "left\ud800\u0000", "right\udfff照片", () -> false);
            JSONObject exported = new JSONObject(output.toString());
            require(
                    exported.getJSONArray("ranges").length() == expected.size(),
                    "Complete export shortened range scope");
            require(
                    exported.getJSONObject("left").getString("name").equals("left\ud800\u0000"),
                    "Left UTF-16 changed");
            require(
                    exported.getJSONObject("right").getString("name").equals("right\udfff照片"),
                    "Right UTF-16 changed");
        }
    }

    private static void denseAndBoundaryCases() throws Exception {
        byte[] left = new byte[3 * 65536 + 17], right = left.clone();
        Arrays.fill(right, 65530, 131080, (byte) 1);
        compare(left, right);
        compare(Arrays.copyOf(left, 65537), right);
        compare(right, Arrays.copyOf(left, 65536));
        byte[] dense = new byte[BinaryComparison.MAX_RANGES * 2];
        for (int i = 0; i < dense.length; i += 2) dense[i] = 1;
        try (InputSnapshot a = snapshot(new byte[dense.length]);
                InputSnapshot b = snapshot(dense)) {
            BinaryComparison result = BinaryComparison.compare(a, b, () -> false);
            require(
                    result.ranges.size() == BinaryComparison.MAX_RANGES
                            && result.ranges.get(99999).offset == 199998,
                    "Dense original ordinals shortened");
            final long[] characters = {0};
            Writer counter =
                    new Writer() {
                        public void write(char[] chars, int offset, int count) {
                            characters[0] += count;
                        }

                        public void flush() {}

                        public void close() {}
                    };
            result.writeReport(counter, "left", "right", () -> false);
            require(characters[0] > 20_000_000, "Dense complete export was a preview");
        }
        byte[] over = Arrays.copyOf(dense, dense.length + 2);
        over[over.length - 2] = 1;
        try (InputSnapshot a = snapshot(new byte[over.length]);
                InputSnapshot b = snapshot(over)) {
            rejects(
                    () -> BinaryComparison.compare(a, b, () -> false),
                    "100,001 ranges accepted as complete");
        }
    }

    private static void cancelledChangedAndOversized() throws Exception {
        try (InputSnapshot a = snapshot(new byte[131072]);
                InputSnapshot b = snapshot(new byte[131072])) {
            AtomicInteger checks = new AtomicInteger();
            rejects(
                    () -> BinaryComparison.compare(a, b, () -> checks.incrementAndGet() > 8),
                    "Comparison cancellation ignored");
            require(
                    BinaryComparison.compare(a, b, () -> false).ranges.isEmpty(),
                    "Cancellation poisoned a later comparison");
            Files.write(b.file.toPath(), new byte[131072]);
            try (RandomAccessFile file = new RandomAccessFile(b.file, "rw")) {
                file.seek(65536);
                file.write(1);
            }
            rejects(
                    () -> BinaryComparison.compare(a, b, () -> false),
                    "Same-length source mutation accepted");
        }
        try (InputSnapshot a = snapshot(new byte[] {1});
                InputSnapshot b = snapshot(new byte[] {1})) {
            try (RandomAccessFile file = new RandomAccessFile(b.file, "rw")) {
                file.setLength(BinaryComparison.MAX_SOURCE_BYTES + 1);
            }
            rejects(
                    () -> BinaryComparison.compare(a, b, () -> false),
                    "Changed/oversized source accepted");
        }
    }

    private static void backendOwnershipAndReplacement() throws Exception {
        File input = new File(directory, "original.bin");
        byte[] original = new byte[512];
        for (int i = 0; i < original.length; i++) original[i] = (byte) i;
        Files.write(input.toPath(), original);
        File cache = new File(directory, "backend");
        String retained;
        String oldRangeRequest;
        File leftOwned, rightOwned;
        try (ComparisonBackend backend =
                new ComparisonBackend(
                        input, cache, "left\ud800\u0000", null, original.length, null)) {
            EvidenceRows pending = backend.open(backend.initialRequest(() -> false), () -> false);
            require(
                    !pending.row(0, () -> false).json().getBoolean("complete"),
                    "Unselected pair claimed comparison coverage");
            byte[] other = original.clone();
            other[37] ^= 1;
            other[38] ^= 1;
            backend.acceptInput(
                    "right", new ByteArrayInputStream(other), "right\udfff照片", () -> false);
            String pair = backend.provenance().getString("pairId");
            InvestigationBackend.Link ranges =
                    backend.roots("", () -> false).stream()
                            .filter(link -> link.label.startsWith("Every differing"))
                            .findFirst()
                            .orElseThrow();
            EvidenceRows rows = backend.open(ranges.request, () -> false);
            require(
                    rows.size() == 1 && rows.row(0, () -> false).physicalOffset == null,
                    "Aggregate difference row invented one-source offset");
            List<InvestigationBackend.Link> links =
                    backend.related(ranges.request, rows, rows.row(0, () -> false), () -> false);
            oldRangeRequest = links.get(1).request;
            EvidenceRows left = backend.open(links.get(0).request, () -> false);
            leftOwned = backend.byteSource();
            require(
                    left.row(0, () -> false).json().getJSONObject("input").getLong("sizeBytes")
                            == original.length,
                    "Range extent overwrote the whole-input identity size");
            require(
                    left.row(0, () -> false).physicalOffset == 37
                            && left.row(0, () -> false).sizeBytes == 2,
                    "Left difference extent changed");
            require(
                    Arrays.equals(Files.readAllBytes(leftOwned.toPath()), original),
                    "Wrong left source bytes");
            EvidenceRows right = backend.open(links.get(1).request, () -> false);
            rightOwned = backend.byteSource();
            require(
                    right.row(0, () -> false).physicalOffset == 37
                            && Arrays.equals(Files.readAllBytes(rightOwned.toPath()), other),
                    "Wrong right source/range");
            require(
                    backend.provenance().getString("byteSourceSide").equals("right"),
                    "Byte source side mislabeled");
            rejects(
                    () ->
                            backend.acceptInput(
                                    "right",
                                    new ByteArrayInputStream(new byte[] {1}),
                                    "cancelled",
                                    () -> true),
                    "Cancelled replacement accepted");
            require(
                    backend.provenance().getString("pairId").equals(pair),
                    "Cancellation replaced prior pair");
            // A differing-size right source is legal; force a failure through an actual provider
            // read.
            rejects(
                    () ->
                            backend.acceptInput(
                                    "right",
                                    new InputStream() {
                                        @Override
                                        public int read() throws IOException {
                                            throw new IOException("Fixture provider failure");
                                        }
                                    },
                                    "failed",
                                    () -> false),
                    "Failed provider replaced the pair");
            require(
                    backend.provenance().getString("pairId").equals(pair) && rightOwned.isFile(),
                    "Failure discarded prior input");
            File stateFile =
                    new File(
                            cache,
                            new JSONObject(backend.retentionState()).getString("session")
                                    + "/source-state.json");
            Path savedState = stateFile.toPath().resolveSibling("saved-state.fixture");
            Files.move(stateFile.toPath(), savedState);
            Files.createDirectory(stateFile.toPath());
            try {
                rejects(
                        () ->
                                backend.acceptInput(
                                        "right",
                                        new ByteArrayInputStream(original),
                                        "publication failure",
                                        () -> false),
                        "Failed atomic source-state publication changed the pair");
                require(
                        backend.provenance().getString("pairId").equals(pair)
                                && rightOwned.isFile(),
                        "Publication failure discarded prior pair bytes");
            } finally {
                Files.delete(stateFile.toPath());
                Files.move(savedState, stateFile.toPath());
            }
            backend.acceptInput(
                    "right",
                    new ByteArrayInputStream(original),
                    "identical replacement",
                    () -> false);
            require(
                    !rightOwned.exists(),
                    "Previous right input leaked after successful replacement");
            EvidenceRows expired = backend.open(oldRangeRequest, () -> false);
            require(
                    expired.kind.equals("comparison-expired-scope")
                            && expired.row(0, () -> false).physicalOffset == null,
                    "Old range ordinal was applied to replacement bytes");
            retained = backend.retentionState();
            byte[] retainedBytes = Files.readAllBytes(stateFile.toPath());
            JSONObject broken = new JSONObject(new String(retainedBytes, StandardCharsets.UTF_8));
            broken.getJSONObject("right").put("sha256", "0".repeat(64));
            try (Writer writer =
                    Files.newBufferedWriter(stateFile.toPath(), StandardCharsets.UTF_8)) {
                EvidenceJson.write(writer, broken, () -> false);
            }
            try (ComparisonBackend diagnostic =
                    new ComparisonBackend(
                            input, cache, "unused", null, original.length, retained)) {
                rejects(
                        () -> diagnostic.initialRequest(() -> false),
                        "Changed retained right input identity accepted");
            }
            require(
                    leftOwned.isFile() && stateFile.isFile(),
                    "Failed restoration destroyed retained evidence");
            Files.write(stateFile.toPath(), retainedBytes);
            backend.close(true);
            restored(retained, cache, input, original);
        }
        require(!leftOwned.exists(), "Final close leaked the owned left input");
    }

    private static void restored(String retained, File cache, File original, byte[] expected)
            throws Exception {
        require(original.delete(), "Original provider file not removed");
        try (ComparisonBackend restored =
                new ComparisonBackend(
                        original, cache, "unused changed label", null, expected.length, retained)) {
            EvidenceRows summary = restored.open(restored.initialRequest(() -> false), () -> false);
            require(
                    summary.row(0, () -> false).json().getBoolean("identicalBytes"),
                    "Retained pair was not restored independently of parent");
            require(
                    restored.provenance()
                            .getJSONObject("left")
                            .getString("name")
                            .equals("left\ud800\u0000"),
                    "Retained source label changed");
            StringWriter output = new StringWriter();
            restored.writeText("complete", output, () -> false);
            require(
                    new JSONObject(output.toString()).getJSONArray("ranges").length() == 0,
                    "Identical replacement still carried old ranges");
            rejects(
                    () ->
                            new ComparisonBackend(
                                            original,
                                            cache,
                                            "bad",
                                            null,
                                            -1,
                                            "{\"session\":\"../escape\"}")
                                    .initialRequest(() -> false),
                    "Retained path escaped source root");
        }
    }
}
