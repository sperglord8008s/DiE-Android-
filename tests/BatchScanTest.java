// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Journal/ownership failure tests. Actual CDiE is exercised separately by Android/JNI fixtures. */
public final class BatchScanTest {
    private static final String ENGINE = "a".repeat(40),
            DB = "b".repeat(40),
            CUSTOM = "c".repeat(64);
    private static final ScanOptions OPTIONS =
            new ScanOptions(16383, 7, "compiler", "fixture-exclude");
    private static File root;
    private static int roots;

    private static File root() {
        return new File(root, "case-" + ++roots);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
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

    private static BatchScanSession.Source source(String name, byte[] bytes) {
        return new BatchScanSession.Source(
                name, "fixture:" + Arrays.hashCode(bytes), () -> new ByteArrayInputStream(bytes));
    }

    private static JSONObject report(InputSnapshot input, String name) throws Exception {
        Inspector.Result inspection = Inspector.scan(input.file, OPTIONS.minimumStringLength);
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 77; i++)
            rows.put(
                    new JSONObject()
                            .put("parent", i == 0 ? -1 : 0)
                            .put("text", "fixture detection " + i)
                            .put("category", "tool")
                            .put("signature", "Binary/fixture.sg"));
        ScanEvidence evidence =
                ScanEvidence.parse(
                        new JSONObject()
                                .put("ok", true)
                                .put("rows", rows)
                                .put("flags", OPTIONS.flags)
                                .put("type", inspection.type)
                                .toString());
        return ScanReport.create(
                name,
                inspection,
                OPTIONS,
                evidence,
                new JSONObject().put("ok", true),
                ENGINE,
                DB,
                CUSTOM,
                1,
                "fixture explanation",
                "not run",
                "");
    }

    private static BatchScanSession create(File root, List<BatchScanSession.Source> sources)
            throws Exception {
        return BatchScanSession.create(root, sources, OPTIONS, ENGINE, DB, CUSTOM);
    }

    private static JSONObject export(BatchScanSession session) throws Exception {
        StringWriter writer = new StringWriter();
        session.writeReport(writer, () -> false);
        return new JSONObject(writer.toString());
    }

    public static void main(String[] args) throws Exception {
        root = new File(args[0]);
        duplicatesFailuresAndOwnership();
        cancellationAndInterruptedJournals();
        changedBytesAndReports();
        invalidStateAndBudgets();
        providerCloseAndActiveDeletion();
        System.out.println(
                "Batch journal fixtures passed: complete plan, duplicates, owned identities, 77-row"
                    + " paging/export, UTF-16, cancellation, interruption, mutation, budgets,"
                    + " active leases and deletion.");
    }

    private static void duplicatesFailuresAndOwnership() throws Exception {
        String name = "same\u0000\ud800\udfff照片.bin";
        byte[] first = "First captured input".getBytes(StandardCharsets.UTF_8),
                second = "Other bytes".getBytes(StandardCharsets.UTF_8);
        List<BatchScanSession.Source> sources =
                List.of(
                        source(name, first),
                        source(name, second),
                        new BatchScanSession.Source(
                                "unavailable",
                                "fixture:missing",
                                () -> {
                                    throw new IOException("Access denied fixture");
                                }),
                        source("native-failure", new byte[0]));
        File directory = root();
        BatchScanSession session = create(directory, sources);
        session.run(
                sources,
                (input, display, stop) -> {
                    JSONObject result = report(input, display);
                    if (display.equals("native-failure"))
                        result.put(
                                "scan",
                                new JSONObject()
                                        .put("ok", false)
                                        .put("error", "fixture unsupported"));
                    return result;
                },
                new BatchScanSession.Control(),
                text -> {});
        require(
                session.entries.get(0).status.equals("complete")
                        && session.entries.get(1).status.equals("complete"),
                "Valid sources not completed");
        require(
                session.entries.get(2).status.equals("failed")
                        && session.entries.get(3).status.equals("failed"),
                "Failed scopes disappeared");
        require(
                !session.entries.get(0).sha256.equals(session.entries.get(1).sha256),
                "Duplicate names conflated input bytes");
        BatchScanSession loaded = BatchScanSession.open(directory, null, () -> false);
        require(loaded.options.sameAs(OPTIONS), "Captured options changed");
        JSONObject result = export(loaded);
        require(result.getJSONArray("entries").length() == 4, "Complete plan missing from export");
        require(
                result.getJSONArray("entries")
                        .getJSONObject(0)
                        .getString("displayName")
                        .equals(name),
                "Source UTF-16 changed");
        require(
                result.getJSONArray("entries").getJSONObject(2).isNull("report"),
                "Failed acquisition got an invented report");
        require(
                result.getJSONArray("entries")
                        .getJSONObject(3)
                        .getJSONObject("report")
                        .getJSONObject("scan")
                        .getString("error")
                        .equals("fixture unsupported"),
                "Native failure report was discarded");
        try (BatchScanBackend backend =
                new BatchScanBackend(directory, new File(directory, "views"), session.id)) {
            EvidenceRows inputs = backend.open(backend.initialRequest(() -> false), () -> false);
            require(
                    inputs.size() == 4 && inputs.row(0, () -> false).physicalOffset == null,
                    "Aggregate rows invented physical offsets");
            InvestigationBackend.Link tree =
                    backend.related("", inputs, inputs.row(0, () -> false), () -> false).stream()
                            .filter(link -> link.label.equals("Original native detection tree"))
                            .findFirst()
                            .orElseThrow();
            EvidenceRows detections = backend.open(tree.request, () -> false);
            require(
                    detections.size() == 77
                            && detections
                                            .select("", () -> false)
                                            .page(1, () -> false)
                                            .rows
                                            .get(0)
                                            .index
                                    == 50,
                    "Detection paging lost native ordinals");
            require(
                    detections.select("fixture detection 76", () -> false).count == 1,
                    "Full native tree search shortened");
            StringWriter output = new StringWriter();
            detections.writeReport(
                    output, backend.provenance(), "fixture detection 76", 0, () -> false);
            require(
                    new JSONObject(output.toString()).getJSONArray("rows").length() == 77,
                    "Filtered export omitted rows");
            InvestigationBackend.Link bytes =
                    backend.related("", inputs, inputs.row(1, () -> false), () -> false).stream()
                            .filter(link -> link.label.startsWith("Exact owned input"))
                            .findFirst()
                            .orElseThrow();
            EvidenceRows input = backend.open(bytes.request, () -> false);
            require(
                    input.row(0, () -> false).sizeBytes == second.length,
                    "Wrong duplicate input extent");
            require(
                    Arrays.equals(Files.readAllBytes(backend.byteSource().toPath()), second),
                    "Byte source belongs to another input");
            require(
                    backend.provenance().getJSONObject("byteSource").getInt("sourceIndex") == 1,
                    "Selected-byte provenance wrong");
        }
        // Reports remain useful after acquired bytes expire; byte operations fail explicitly.
        Files.delete(loaded.input(loaded.entries.get(0), () -> false).toPath());
        require(
                export(loaded).getJSONArray("entries").length() == 4,
                "Report export depended on expired source");
        rejects(
                () -> loaded.input(loaded.entries.get(0), () -> false),
                "Expired bytes were accepted");
        loaded.delete();
        require(
                !session.directory.exists() && !new File(directory, "latest").exists(),
                "Owned batch deletion incomplete");
    }

    private static void cancellationAndInterruptedJournals() throws Exception {
        List<BatchScanSession.Source> sources =
                List.of(
                        source("first", new byte[] {1}),
                        source("second", new byte[] {2}),
                        source("third", new byte[] {3}));
        File directory = root();
        BatchScanSession session = create(directory, sources);
        BatchScanSession.Control control = new BatchScanSession.Control();
        session.run(
                sources,
                (input, name, stop) -> {
                    stop.cancel();
                    EvidenceRows.check(stop);
                    return report(input, name);
                },
                control,
                text -> {});
        BatchScanSession loaded = BatchScanSession.open(directory, session.id, () -> false);
        require(
                loaded.metadata().getString("status").equals("cancelled"),
                "Cancellation not persisted");
        require(
                loaded.entries.get(0).status.equals("cancelled")
                        && loaded.entries.get(1).status.equals("not-started"),
                "Cancelled/pending scopes conflated");
        require(
                export(loaded).getJSONArray("entries").length() == 3,
                "Cancellation shortened plan export");
        rejects(
                () ->
                        loaded.run(
                                sources,
                                (input, name, stop) -> report(input, name),
                                new BatchScanSession.Control(),
                                text -> {}),
                "Retained batch resumed");
        BatchScanSession beforeStart = create(directory, sources);
        BatchScanSession.Control cancelled = new BatchScanSession.Control();
        cancelled.cancel();
        AtomicInteger calls = new AtomicInteger();
        beforeStart.run(
                sources,
                (input, name, stop) -> {
                    calls.incrementAndGet();
                    return report(input, name);
                },
                cancelled,
                text -> {});
        require(
                calls.get() == 0
                        && beforeStart.entries.stream()
                                .allMatch(entry -> entry.status.equals("not-started")),
                "Pre-cancel still acquired inputs");
        BatchScanSession incomplete = create(directory, sources);
        File state = new File(incomplete.directory, "state.json");
        JSONObject journal = BatchScanSession.readJson(state, 2 * 1024 * 1024, () -> false);
        journal.put("status", "running")
                .getJSONArray("entries")
                .getJSONObject(0)
                .put("status", "acquiring");
        Files.writeString(state.toPath(), journal.toString());
        BatchScanSession restored = BatchScanSession.open(directory, incomplete.id, () -> false);
        require(
                restored.metadata().getString("status").equals("running")
                        && restored.entries.size() == 3,
                "Incomplete journal lost plan or claimed completion");
        rejects(
                () ->
                        restored.run(
                                sources,
                                (input, name, stop) -> report(input, name),
                                new BatchScanSession.Control(),
                                text -> {}),
                "Interrupted job resumed");
        rejects(() -> exportCancelled(restored), "Export cancellation ignored");
    }

    private static void exportCancelled(BatchScanSession session) throws Exception {
        session.writeReport(new StringWriter(), () -> true);
    }

    private static void changedBytesAndReports() throws Exception {
        List<BatchScanSession.Source> sources =
                List.of(
                        source("changed-input", new byte[] {1, 2}),
                        source("wrong-options", new byte[] {3}),
                        source("good", new byte[] {4}));
        File directory = root();
        BatchScanSession session = create(directory, sources);
        session.run(
                sources,
                (input, name, stop) -> {
                    JSONObject result = report(input, name);
                    if (name.equals("changed-input"))
                        Files.write(input.file.toPath(), new byte[] {9, 9});
                    if (name.equals("wrong-options"))
                        result.getJSONObject("options").put("flags", 0);
                    return result;
                },
                new BatchScanSession.Control(),
                text -> {});
        require(
                session.entries.get(0).status.equals("failed")
                        && session.entries.get(0).error.contains("SHA-256"),
                "Same-length input mutation accepted");
        require(
                session.entries.get(1).status.equals("failed")
                        && session.entries.get(1).error.contains("identity changed"),
                "Mislabeled options accepted");
        require(
                session.entries.get(2).status.equals("complete"),
                "Failed input prevented later analysis");
        BatchScanSession.Entry good = session.entries.get(2);
        File report = new File(session.directory, good.reportName);
        byte[] bytes = Files.readAllBytes(report.toPath());
        bytes[bytes.length - 2] ^= 1;
        Files.write(report.toPath(), bytes);
        rejects(() -> session.report(good, () -> false), "Same-length report mutation accepted");
        rejects(() -> export(session), "Corrupt report silently omitted from export");
        Path outside = Files.createTempFile(root.toPath(), "outside-", ".json");
        JSONObject journal =
                BatchScanSession.readJson(
                        new File(session.directory, "state.json"), 2 * 1024 * 1024, () -> false);
        journal.getJSONArray("entries").getJSONObject(2).put("privateReport", outside.toString());
        Files.writeString(new File(session.directory, "state.json").toPath(), journal.toString());
        rejects(
                () -> BatchScanSession.open(directory, session.id, () -> false),
                "Private state escaped owned directory");
        require(Files.exists(outside), "Path validation changed unrelated file");
    }

    private static void invalidStateAndBudgets() throws Exception {
        List<BatchScanSession.Source> one = List.of(source("one", new byte[] {1}));
        File directory = root();
        rejects(() -> create(directory, List.of()), "Empty plan accepted");
        rejects(() -> create(directory, Collections.nCopies(65, one.get(0))), "65 inputs accepted");
        rejects(
                () -> BatchScanSession.create(directory, one, OPTIONS, "wrong", DB, CUSTOM),
                "Unidentified engine accepted");
        for (int i = 0; i < BatchScanSession.MAX_SESSIONS; i++) create(directory, one);
        rejects(() -> create(directory, one), "Unbounded saved-session count accepted");
        File other = root();
        BatchScanSession session = create(other, one);
        session.run(
                one,
                (input, name, stop) -> {
                    JSONObject result = report(input, name);
                    result.put("oversized", "x".repeat((int) BatchScanSession.MAX_REPORT_BYTES));
                    return result;
                },
                new BatchScanSession.Control(),
                text -> {});
        require(
                session.entries.get(0).status.equals("failed")
                        && session.entries.get(0).reportName.isEmpty(),
                "Oversized report stored as success");
        require(
                Arrays.stream(session.directory.list())
                        .noneMatch(name -> name.startsWith("report-")),
                "Failed report left an unowned stage");
        List<BatchScanSession.Source> overflow = List.of(source("whole", new byte[] {1}));
        BatchScanSession aggregate = create(other, overflow);
        var bytes = BatchScanSession.class.getDeclaredField("inputBytes");
        bytes.setAccessible(true);
        bytes.setLong(aggregate, BatchScanSession.MAX_TOTAL_BYTES);
        AtomicInteger analyzed = new AtomicInteger();
        aggregate.run(
                overflow,
                (input, name, stop) -> {
                    analyzed.incrementAndGet();
                    return report(input, name);
                },
                new BatchScanSession.Control(),
                text -> {});
        require(
                analyzed.get() == 0 && aggregate.entries.get(0).error.contains("0-byte"),
                "Aggregate input budget bypassed");
        require(
                Arrays.stream(aggregate.directory.list())
                        .noneMatch(name -> name.endsWith(".snapshot")),
                "Failed acquisition left an owned snapshot");
    }

    private static void providerCloseAndActiveDeletion() throws Exception {
        BatchScanSession.Source closing =
                new BatchScanSession.Source(
                        "close-failure",
                        "fixture:close",
                        () ->
                                new ByteArrayInputStream(new byte[] {1}) {
                                    @Override
                                    public void close() throws IOException {
                                        throw new IOException("Provider close failed");
                                    }
                                });
        File directory = root();
        BatchScanSession failed = create(directory, List.of(closing));
        failed.run(
                List.of(closing),
                (input, name, stop) -> report(input, name),
                new BatchScanSession.Control(),
                text -> {});
        require(
                failed.entries.get(0).status.equals("failed")
                        && Arrays.stream(failed.directory.list())
                                .noneMatch(name -> name.endsWith(".snapshot")),
                "Provider close failure leaked acquired bytes");
        List<BatchScanSession.Source> sources = List.of(source("active", new byte[] {1}));
        BatchScanSession active = create(directory, sources);
        CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> job =
                worker.submit(
                        () -> {
                            try {
                                active.run(
                                        sources,
                                        (input, name, stop) -> {
                                            entered.countDown();
                                            require(
                                                    released.await(10, TimeUnit.SECONDS),
                                                    "Active test timed out");
                                            return report(input, name);
                                        },
                                        new BatchScanSession.Control(),
                                        text -> {});
                            } catch (Exception failure) {
                                throw new RuntimeException(failure);
                            }
                        });
        try {
            require(entered.await(10, TimeUnit.SECONDS), "Active request not reached");
            rejects(active::delete, "Active batch deleted");
            require(
                    new File(directory, "latest").isFile(),
                    "Rejected deletion removed latest reference");
        } finally {
            released.countDown();
            job.get(10, TimeUnit.SECONDS);
            worker.shutdownNow();
        }
        active.delete();
        require(failed.directory.isDirectory(), "Deletion removed another batch");
    }
}
