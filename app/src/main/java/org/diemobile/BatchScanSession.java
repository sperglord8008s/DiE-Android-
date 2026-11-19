// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Private, checkpointed desktop batch. Each input and report has its own byte identity. */
final class BatchScanSession {
    static final String SCHEMA = "org.diemobile.desktop-batch";
    static final int VERSION = 2;
    static final int MAX_INPUTS = 64, MAX_SESSIONS = 8;
    static final long MAX_INPUT_BYTES = 256L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    static final long MAX_REPORT_BYTES = 16L * 1024 * 1024;
    static final long MAX_TOTAL_REPORT_BYTES = 64L * 1024 * 1024;
    private static final long MAX_STATE_BYTES = 2L * 1024 * 1024;

    interface Opener {
        InputStream open() throws Exception;
    }

    interface Analyzer {
        JSONObject analyze(InputSnapshot input, String name, Control control) throws Exception;
    }

    interface Progress {
        void changed(String message);
    }

    /** Cancellation belongs to this batch; a late click cannot cancel another engine request. */
    static final class Control implements BooleanSupplier {
        private boolean requested;
        private Engine.Cancellation active;

        synchronized void cancel() {
            requested = true;
            if (active != null) active.cancel();
        }

        @Override
        public synchronized boolean getAsBoolean() {
            return requested;
        }

        synchronized Engine.Cancellation beginScan() throws InterruptedIOException {
            EvidenceRows.check(this);
            if (active != null) throw new IllegalStateException("A batch scan is already active");
            active = new Engine.Cancellation();
            return active;
        }

        synchronized void endScan(Engine.Cancellation value) {
            if (active == value) active = null;
        }
    }

    static final class Source {
        final String name, origin;
        final Opener opener;

        Source(String name, String origin, Opener opener) {
            if (name == null
                    || name.length() > 4096
                    || origin == null
                    || origin.length() > 8192
                    || opener == null) throw new IllegalArgumentException("Invalid batch source");
            this.name = name;
            this.origin = origin;
            this.opener = opener;
        }
    }

    static final class Entry {
        final int index;
        final String name, origin;
        String status = "queued", stage = "planned", error = "";
        String snapshotName = "", sha256 = "", reportName = "", reportSha256 = "";
        long size, reportSize, elapsedMs;

        Entry(int index, String name, String origin) {
            this.index = index;
            this.name = name;
            this.origin = origin;
        }

        JSONObject json() throws JSONException {
            return new JSONObject()
                    .put("sourceIndex", index)
                    .put("displayName", name)
                    .put("acquisitionOrigin", origin)
                    .put("status", status)
                    .put("stage", stage)
                    .put("error", error)
                    .put("elapsedMs", elapsedMs)
                    .put("sizeBytes", snapshotName.isEmpty() ? JSONObject.NULL : size)
                    .put("sha256", sha256.isEmpty() ? JSONObject.NULL : sha256)
                    .put("reportSizeBytes", reportName.isEmpty() ? JSONObject.NULL : reportSize)
                    .put("reportSha256", reportSha256.isEmpty() ? JSONObject.NULL : reportSha256);
        }
    }

    final File directory;
    final String id, createdAt;
    final ScanOptions options;
    final ScanTarget.Request interpretation;
    final int schemaVersion;
    final String engineRevision, signatureRevision, customDigest;
    final List<Entry> entries;
    private String status = "planned", completedAt = "", error = "";
    private long inputBytes, reportBytes;
    private boolean canRun;
    private byte[] capturedJournal;

    private BatchScanSession(
            File directory,
            String id,
            String createdAt,
            ScanOptions options,
            ScanTarget.Request interpretation,
            int schemaVersion,
            String engineRevision,
            String signatureRevision,
            String customDigest,
            List<Entry> entries) {
        this.directory = directory;
        this.id = id;
        this.createdAt = createdAt;
        this.options = options;
        this.interpretation = interpretation;
        this.schemaVersion = schemaVersion;
        this.engineRevision = engineRevision;
        this.signatureRevision = signatureRevision;
        this.customDigest = customDigest;
        this.entries = Collections.unmodifiableList(entries);
    }

    static synchronized BatchScanSession create(
            File root,
            List<Source> sources,
            ScanOptions options,
            String engineRevision,
            String signatureRevision,
            String customDigest)
            throws Exception {
        return create(
                root,
                sources,
                options,
                ScanTarget.AUTOMATIC,
                engineRevision,
                signatureRevision,
                customDigest);
    }

    static synchronized BatchScanSession create(
            File root,
            List<Source> sources,
            ScanOptions options,
            ScanTarget.Request interpretation,
            String engineRevision,
            String signatureRevision,
            String customDigest)
            throws Exception {
        if (interpretation == null)
            throw new IllegalArgumentException("Missing batch interpretation");
        if (sources.isEmpty() || sources.size() > MAX_INPUTS)
            throw new IOException("Select 1–64 files; no files were queued");
        checkRevisions(engineRevision, signatureRevision, customDigest);
        if (!root.isDirectory() && !root.mkdirs())
            throw new IOException("Cannot create batch root");
        File[] retained =
                root.listFiles(file -> file.isDirectory() && file.getName().startsWith("batch-"));
        if (retained != null && retained.length >= MAX_SESSIONS)
            throw new IOException(
                    "Eight saved batches already exist; open Saved batches and delete an unneeded"
                            + " batch first");
        String id = UUID.randomUUID().toString();
        File directory = new File(root, "batch-" + id);
        if (!directory.mkdir()) throw new IOException("Cannot create private batch session");
        List<Entry> entries = new ArrayList<>();
        for (Source source : sources)
            entries.add(new Entry(entries.size(), source.name, source.origin));
        BatchScanSession result =
                new BatchScanSession(
                        directory,
                        id,
                        java.time.Instant.now().toString(),
                        options,
                        interpretation,
                        VERSION,
                        engineRevision,
                        signatureRevision,
                        customDigest,
                        entries);
        result.canRun = true;
        result.checkpoint();
        synchronized (BatchScanSession.class) {
            atomicWrite(new File(root, "latest"), id.getBytes(StandardCharsets.US_ASCII));
        }
        return result;
    }

    /** No automatic resume: provider permissions, options and engine state may have changed. */
    static BatchScanSession open(File root, String requestedId, BooleanSupplier stop)
            throws Exception {
        EvidenceRows.check(stop);
        String id = requestedId;
        if (id == null) {
            File latest = new File(root, "latest");
            if (!latest.isFile()) throw new IOException("No saved batch yet");
            if (latest.length() != 36) throw new IOException("Invalid latest batch reference");
            id = new String(Files.readAllBytes(latest.toPath()), StandardCharsets.US_ASCII);
        }
        File directory = ownedDirectory(root, id);
        byte[] journal = readBounded(new File(directory, "state.json"), MAX_STATE_BYTES, stop);
        JSONObject state = decodeJson(journal);
        int version = (int) integer(state, "schemaVersion", 1, VERSION);
        if (!SCHEMA.equals(state.getString("schema")) || !id.equals(state.getString("id")))
            throw new IOException("Invalid batch state schema");
        ScanTarget.Request interpretation =
                version == 1
                        ? ScanTarget.AUTOMATIC
                        : ScanTarget.Request.restore(
                                state.getJSONObject("interpretationRequest").toString());
        JSONObject settings = state.getJSONObject("options");
        if (interpretation.automatic()
                ? integer(settings, "typeId", 0, 0) != 0
                : !settings.has("typeId") || !settings.isNull("typeId"))
            throw new IOException("Batch interpretation/options scope differs");
        ScanOptions options =
                new ScanOptions(
                        (int) integer(settings, "flags", 0, 16383),
                        (int) integer(settings, "minimumStringLength", 3, 64),
                        settings.getString("includeTags"),
                        settings.getString("excludeTags"));
        JSONObject engine = state.getJSONObject("engine");
        String revision = engine.getString("engineRevision"),
                db = engine.getString("signatureRevision"),
                custom = engine.getString("customArchiveSha256");
        checkRevisions(revision, db, custom);
        JSONArray saved = state.getJSONArray("entries");
        if (saved.length() < 1 || saved.length() > MAX_INPUTS)
            throw new IOException("Invalid batch input count");
        List<Entry> entries = new ArrayList<>();
        long inputBytes = 0, reportBytes = 0;
        for (int i = 0; i < saved.length(); i++) {
            EvidenceRows.check(stop);
            JSONObject item = saved.getJSONObject(i);
            if (integer(item, "sourceIndex", 0, MAX_INPUTS - 1) != i)
                throw new IOException("Batch input order changed");
            String name = item.getString("displayName"),
                    origin = item.getString("acquisitionOrigin");
            if (name.length() > 4096 || origin.length() > 8192)
                throw new IOException("Batch source label exceeds budget");
            Entry entry = new Entry(i, name, origin);
            entry.status = item.getString("status");
            if (!Arrays.asList(
                            "queued",
                            "acquiring",
                            "scanning",
                            "complete",
                            "failed",
                            "cancelled",
                            "not-started")
                    .contains(entry.status)) throw new IOException("Unknown batch entry status");
            entry.stage = item.getString("stage");
            entry.error = item.getString("error");
            entry.elapsedMs = integer(item, "elapsedMs", 0, Long.MAX_VALUE);
            entry.snapshotName = item.getString("privateSnapshot");
            entry.reportName = item.getString("privateReport");
            if (!entry.snapshotName.isEmpty()) {
                entry.size = integer(item, "sizeBytes", 0, MAX_INPUT_BYTES);
                entry.sha256 = item.getString("sha256");
                if (entry.size < 0
                        || entry.size > MAX_INPUT_BYTES
                        || !entry.sha256.matches("[0-9a-f]{64}"))
                    throw new IOException("Invalid retained input identity");
                privateFile(directory, entry.snapshotName, "analysis-input-", ".snapshot");
                inputBytes += entry.size;
            }
            if (!entry.reportName.isEmpty()) {
                entry.reportSize = integer(item, "reportSizeBytes", 1, MAX_REPORT_BYTES);
                entry.reportSha256 = item.getString("reportSha256");
                if (entry.reportSize < 1
                        || entry.reportSize > MAX_REPORT_BYTES
                        || !entry.reportSha256.matches("[0-9a-f]{64}"))
                    throw new IOException("Invalid retained report identity");
                privateFile(directory, entry.reportName, "report-", ".json");
                reportBytes += entry.reportSize;
            }
            if (entry.status.equals("complete")
                    && (entry.snapshotName.isEmpty() || entry.reportName.isEmpty()))
                throw new IOException("Completed batch source has no retained evidence");
            entries.add(entry);
        }
        if (inputBytes > MAX_TOTAL_BYTES || reportBytes > MAX_TOTAL_REPORT_BYTES)
            throw new IOException("Retained batch exceeds aggregate budgets");
        BatchScanSession result =
                new BatchScanSession(
                        directory,
                        id,
                        state.getString("createdAt"),
                        options,
                        interpretation,
                        version,
                        revision,
                        db,
                        custom,
                        entries);
        result.capturedJournal = journal;
        result.status = state.getString("status");
        if (!Arrays.asList("planned", "running", "finished", "cancelled", "interrupted")
                .contains(result.status)) throw new IOException("Invalid batch journal status");
        result.completedAt = state.getString("completedAt");
        result.error = state.getString("error");
        result.inputBytes = inputBytes;
        result.reportBytes = reportBytes;
        return result;
    }

    void run(List<Source> sources, Analyzer analyzer, Control stop, Progress progress)
            throws Exception {
        try (RandomAccessFile lock = new RandomAccessFile(new File(directory, "run.lock"), "rw");
                FileLock lease = acquireLock(lock.getChannel())) {
            runLocked(new ArrayList<>(sources), analyzer, stop, progress);
        }
    }

    private void runLocked(List<Source> sources, Analyzer analyzer, Control stop, Progress progress)
            throws Exception {
        if (!canRun || !status.equals("planned") || sources.size() != entries.size())
            throw new IOException("Batch cannot be resumed or reused");
        for (int i = 0; i < sources.size(); i++)
            if (!entries.get(i).name.equals(sources.get(i).name)
                    || !entries.get(i).origin.equals(sources.get(i).origin))
                throw new IOException("Batch source plan changed");
        canRun = false;
        status = "running";
        checkpoint();
        try {
            for (Entry entry : entries) {
                if (stop.getAsBoolean()) break;
                long started = System.nanoTime();
                entry.status = "acquiring";
                entry.stage = "input-acquisition";
                checkpoint();
                progress.changed(
                        "["
                                + (entry.index + 1)
                                + "/"
                                + entries.size()
                                + "] Acquiring "
                                + entry.name);
                try {
                    InputSnapshot snapshot = null;
                    try (InputStream input = sources.get(entry.index).opener.open()) {
                        if (input == null)
                            throw new IOException("Provider returned no input stream");
                        snapshot =
                                InputSnapshot.acquire(
                                        input,
                                        directory,
                                        Math.min(MAX_INPUT_BYTES, MAX_TOTAL_BYTES - inputBytes),
                                        stop);
                    } catch (Exception failure) {
                        if (snapshot != null) snapshot.close();
                        throw failure;
                    }
                    // Ownership moves to the session; failed scans still retain acquired bytes.
                    entry.snapshotName = snapshot.file.getName();
                    entry.size = snapshot.size;
                    entry.sha256 = snapshot.sha256;
                    inputBytes += snapshot.size;
                    entry.status = "scanning";
                    entry.stage = "desktop-analysis";
                    checkpoint();
                    progress.changed(
                            "["
                                    + (entry.index + 1)
                                    + "/"
                                    + entries.size()
                                    + "] Scanning "
                                    + entry.name);
                    JSONObject report = analyzer.analyze(snapshot, entry.name, stop);
                    EvidenceRows.check(stop);
                    snapshot.checkIdentity(stop);
                    checkReportIdentity(entry, report);
                    entry.stage = "report-storage";
                    storeReport(entry, report, stop);
                    JSONObject scan = report.getJSONObject("scan");
                    entry.status = scan.getBoolean("ok") ? "complete" : "failed";
                    if (!scan.getBoolean("ok"))
                        entry.error = scan.optString("error", "Native scan failed");
                    entry.stage = "finished";
                } catch (Exception | LinkageError | StackOverflowError failure) {
                    entry.status = stop.getAsBoolean() ? "cancelled" : "failed";
                    entry.error = boundedError(failure);
                }
                entry.elapsedMs = (System.nanoTime() - started) / 1_000_000;
                checkpoint();
            }
            status = stop.getAsBoolean() ? "cancelled" : "finished";
        } catch (Exception | LinkageError failure) {
            status = "interrupted";
            error = boundedError(failure);
            throw failure;
        } finally {
            for (Entry entry : entries)
                if (entry.status.equals("queued")) {
                    entry.status = "not-started";
                    entry.error = "Batch stopped before this input was acquired";
                } else if (entry.status.equals("acquiring") || entry.status.equals("scanning")) {
                    entry.status = stop.getAsBoolean() ? "cancelled" : "failed";
                    entry.error = "Batch interrupted before this phase completed: " + error;
                }
            completedAt = java.time.Instant.now().toString();
            checkpoint();
        }
    }

    JSONObject metadata() throws JSONException {
        JSONObject counts = new JSONObject();
        for (Entry entry : entries) counts.put(entry.status, counts.optInt(entry.status) + 1);
        JSONObject metadata =
                new JSONObject()
                        .put("schema", SCHEMA)
                        .put("schemaVersion", schemaVersion)
                        .put("id", id)
                        .put("createdAt", createdAt)
                        .put("completedAt", completedAt)
                        .put("status", status)
                        .put("error", error)
                        .put("plannedInputs", entries.size())
                        .put("outcomes", counts)
                        .put("inputBytes", inputBytes)
                        .put("reportBytes", reportBytes)
                        .put(
                                "options",
                                optionsJson(options)
                                        .put(
                                                "typeId",
                                                interpretation.automatic() ? 0 : JSONObject.NULL))
                        .put(
                                "engine",
                                new JSONObject()
                                        .put("engineRevision", engineRevision)
                                        .put("signatureRevision", signatureRevision)
                                        .put("customArchiveSha256", customDigest))
                        .put("networkUsed", false)
                        .put(
                                "limits",
                                new JSONObject()
                                        .put("inputs", MAX_INPUTS)
                                        .put("inputBytes", MAX_INPUT_BYTES)
                                        .put("totalInputBytes", MAX_TOTAL_BYTES)
                                        .put("reportBytes", MAX_REPORT_BYTES)
                                        .put("totalReportBytes", MAX_TOTAL_REPORT_BYTES))
                        .put(
                                "limitations",
                                new JSONArray()
                                        .put(
                                                "Finished means the plan settled; inspect every"
                                                    + " per-file outcome and native diagnostic.")
                                        .put(
                                                "A running/planned journal reopened after process"
                                                    + " death is incomplete and is never"
                                                    + " automatically resumed.")
                                        .put(
                                                "Independent document acquisition is sequential,"
                                                    + " not an atomic snapshot of a directory or"
                                                    + " package set.")
                                        .put(
                                                "CDiE and generic inspection run in the app process"
                                                    + " with cooperative cancellation;"
                                                    + " hostile-parser isolation and hard deadlines"
                                                    + " remain pending.")
                                        .put(
                                                "Only desktop signature scanning, native facts and"
                                                    + " bounded generic previews run here;"
                                                    + " Android-specific tools are separate.")
                                        .put(
                                                "No Qt heuristic/format parity, device/provider"
                                                    + " verification, safety or execution verdict"
                                                    + " is implied."));
        if (schemaVersion >= 2) metadata.put("interpretationRequest", interpretation.toJson());
        return metadata;
    }

    static JSONObject optionsJson(ScanOptions options) throws JSONException {
        JSONArray enabled = new JSONArray();
        for (int i = 0; i < ScanOptions.LABELS.length; i++)
            if ((options.flags & (1 << i)) != 0) enabled.put(ScanOptions.LABELS[i]);
        return new JSONObject()
                .put("contract", ScanProfiles.OPTIONS_CONTRACT)
                .put("flags", options.flags)
                .put("enabled", enabled)
                .put("minimumStringLength", options.minimumStringLength)
                .put("includeTags", options.includeTags)
                .put("excludeTags", options.excludeTags)
                .put("typeId", 0);
    }

    Entry entry(int index) throws IOException {
        if (index < 0 || index >= entries.size())
            throw new IOException("Batch source index outside captured plan");
        return entries.get(index);
    }

    File input(Entry entry, BooleanSupplier stop) throws Exception {
        if (entry.snapshotName.isEmpty()) throw new IOException("This input was not acquired");
        File file = privateFile(directory, entry.snapshotName, "analysis-input-", ".snapshot");
        InputSnapshot.restore(file, directory, entry.size, entry.sha256, stop);
        return file;
    }

    JSONObject report(Entry entry, BooleanSupplier stop) throws Exception {
        if (entry.reportName.isEmpty())
            throw new IOException("No stored report for this input; inspect its error and stage");
        File file = privateFile(directory, entry.reportName, "report-", ".json");
        if (!file.isFile() || file.length() != entry.reportSize)
            throw new IOException("Stored batch report expired or changed length");
        byte[] bytes = readBounded(file, MAX_REPORT_BYTES, stop);
        if (!entry.reportSha256.equals(digest(bytes)))
            throw new IOException("Stored batch report SHA-256 changed");
        JSONObject report = decodeJson(bytes);
        checkReportIdentity(entry, report);
        return report;
    }

    void writeReport(Writer output, BooleanSupplier stop) throws Exception {
        JSONObject metadata = metadata();
        StringWriter header = new StringWriter();
        EvidenceJson.write(header, metadata, stop);
        String prefix = header.toString();
        output.write(prefix, 0, prefix.length() - 1);
        output.write(",\"entries\":[");
        for (Entry entry : entries) {
            EvidenceRows.check(stop);
            if (entry.index > 0) output.write(',');
            JSONObject row = entry.json();
            // Expired or corrupted stored evidence must fail export, never disappear from it.
            row.put("report", entry.reportName.isEmpty() ? JSONObject.NULL : report(entry, stop));
            EvidenceJson.write(output, row, stop);
        }
        EvidenceRows.check(stop);
        output.write("]}");
    }

    byte[] journalBytes() throws IOException {
        if (capturedJournal == null)
            throw new IOException("Only an opened journal has captured source bytes");
        return capturedJournal.clone();
    }

    void delete() throws Exception {
        delete(directory.getParentFile(), id);
    }

    static synchronized void delete(File root, String id) throws Exception {
        File directory = ownedDirectory(root, id);
        File latest = new File(root, "latest");
        try (RandomAccessFile lock = new RandomAccessFile(new File(directory, "run.lock"), "rw");
                FileLock lease = acquireLock(lock.getChannel());
                var files = Files.walk(directory.toPath())) {
            if (latest.isFile()
                    && latest.length() == 36
                    && id.equals(
                            new String(
                                    Files.readAllBytes(latest.toPath()),
                                    StandardCharsets.US_ASCII))) Files.delete(latest.toPath());
            for (Path file : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new))
                Files.delete(file);
        }
    }

    private static File ownedDirectory(File root, String id) throws IOException {
        try {
            if (!UUID.fromString(id).toString().equals(id))
                throw new IOException("Invalid batch ID");
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid batch ID", failure);
        }
        File directory = new File(root, "batch-" + id);
        if (!directory.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())
                || !directory.getName().equals(directory.getCanonicalFile().getName()))
            throw new IOException("Invalid private batch directory");
        return directory;
    }

    private static long integer(JSONObject object, String name, long minimum, long maximum)
            throws Exception {
        Object raw = object.get(name);
        if (!(raw instanceof Number) || raw.toString().length() > 128)
            throw new IOException("Invalid integer batch field: " + name);
        try {
            long value = new BigDecimal(raw.toString()).longValueExact();
            if (value < minimum || value > maximum)
                throw new IOException("Batch integer outside range: " + name);
            return value;
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IOException("Invalid exact integer batch field: " + name, failure);
        }
    }

    private void checkpoint() throws Exception {
        JSONObject state = metadata();
        JSONArray values = new JSONArray();
        for (Entry entry : entries)
            values.put(
                    entry.json()
                            .put("privateSnapshot", entry.snapshotName)
                            .put("privateReport", entry.reportName));
        state.put("entries", values);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            EvidenceJson.write(writer, state, () -> false);
        }
        if (bytes.size() > MAX_STATE_BYTES) throw new IOException("Batch journal exceeds 2 MiB");
        atomicWrite(new File(directory, "state.json"), bytes.toByteArray());
    }

    private void storeReport(Entry entry, JSONObject report, BooleanSupplier stop)
            throws Exception {
        File staged = File.createTempFile("report-", ".json", directory);
        boolean adopted = false;
        long maximum = Math.min(MAX_REPORT_BYTES, MAX_TOTAL_REPORT_BYTES - reportBytes);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileOutputStream file = new FileOutputStream(staged)) {
                OutputStream bounded =
                        new FilterOutputStream(file) {
                            long written;

                            @Override
                            public void write(int value) throws IOException {
                                write(new byte[] {(byte) value}, 0, 1);
                            }

                            @Override
                            public void write(byte[] bytes, int offset, int count)
                                    throws IOException {
                                EvidenceRows.check(stop);
                                if (count > maximum - written)
                                    throw new IOException(
                                            "Batch report exceeds per-file / aggregate storage"
                                                    + " budget");
                                out.write(bytes, offset, count);
                                digest.update(bytes, offset, count);
                                written += count;
                            }
                        };
                Writer writer =
                        new OutputStreamWriter(bounded, StandardCharsets.UTF_8.newEncoder());
                EvidenceJson.write(writer, report, stop);
                writer.flush();
                file.getFD().sync();
            }
            EvidenceRows.check(stop);
            entry.reportName = staged.getName();
            entry.reportSize = staged.length();
            entry.reportSha256 = Inspector.toHex(digest.digest());
            reportBytes += entry.reportSize;
            adopted = true;
        } finally {
            if (!adopted) staged.delete();
        }
    }

    private void checkReportIdentity(Entry entry, JSONObject report) throws Exception {
        JSONObject source = report.getJSONObject("source");
        if (!"org.diemobile.scan-report".equals(report.getString("schema"))
                || report.getInt("schemaVersion") != 1
                || !entry.name.equals(source.getString("name"))
                || source.getLong("sizeBytes") != entry.size
                || !entry.sha256.equals(source.getString("sha256")))
            throw new IOException("Batch report source identity differs from acquired bytes");
        JSONObject captured = report.getJSONObject("options"),
                provenance = report.getJSONObject("provenance");
        if (captured.getInt("flags") != options.flags
                || captured.getInt("minimumStringLength") != options.minimumStringLength
                || !captured.getString("includeTags").equals(options.includeTags)
                || !captured.getString("excludeTags").equals(options.excludeTags)
                || !provenance.getString("engineRevision").equals(engineRevision)
                || !provenance.getString("signatureRevision").equals(signatureRevision)
                || !provenance
                        .getJSONObject("customDatabase")
                        .getString("archiveSha256")
                        .equals(customDigest))
            throw new IOException("Batch report options or engine/database identity changed");
        if (schemaVersion >= 2 || report.has("scanTarget")) {
            ScanTarget.Capture target = ScanTarget.Capture.read(report);
            if (!target.request.sameAs(interpretation)
                    || !target.engineRevision.equals(engineRevision))
                throw new IOException("Batch report interpretation differs from captured plan");
        } else if (integer(captured, "typeId", 0, 0) != 0) {
            throw new IOException("Legacy batch report must use Automatic");
        }
        ScanEvidence.parse(report.getJSONObject("scan").toString());
    }

    private static void checkRevisions(String engine, String db, String custom) throws IOException {
        if (engine == null
                || !engine.matches("[0-9a-f]{40}")
                || db == null
                || !db.matches("[0-9a-f]{40}")
                || custom == null
                || !custom.isEmpty() && !custom.matches("[0-9a-f]{64}"))
            throw new IOException("Invalid batch engine/database identity");
    }

    private static File privateFile(File directory, String name, String prefix, String suffix)
            throws IOException {
        File file = new File(directory, name);
        if (!name.startsWith(prefix)
                || !name.endsWith(suffix)
                || !file.getName().equals(name)
                || !file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))
            throw new IOException("Invalid private batch evidence path");
        return file;
    }

    static JSONObject readJson(File file, long maximum, BooleanSupplier stop) throws Exception {
        return decodeJson(readBounded(file, maximum, stop));
    }

    private static byte[] readBounded(File file, long maximum, BooleanSupplier stop)
            throws Exception {
        if (!file.isFile() || file.length() < 1 || file.length() > maximum)
            throw new IOException("Private batch evidence unavailable / exceeds budget");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                EvidenceRows.check(stop);
                if (count > maximum - bytes.size())
                    throw new IOException("Batch evidence grew beyond budget");
                bytes.write(buffer, 0, count);
            }
        }
        EvidenceRows.check(stop);
        return bytes.toByteArray();
    }

    private static JSONObject decodeJson(byte[] bytes) throws Exception {
        return new JSONObject(
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
    }

    private static String digest(byte[] bytes) throws Exception {
        return Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void atomicWrite(File target, byte[] bytes) throws IOException {
        File staged = File.createTempFile("batch-write-", ".stage", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(staged)) {
                output.write(bytes);
                output.getFD().sync();
            }
            Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            staged.delete();
        }
    }

    private static FileLock acquireLock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("This batch is active in another operation");
            return lock;
        } catch (OverlappingFileLockException failure) {
            throw new IOException("This batch is active in another operation", failure);
        }
    }

    private static String boundedError(Throwable failure) {
        String message = failure.toString();
        return message.length() > 16384
                ? message.substring(0, 16384) + " [error text exceeds 16 Ki-characters]"
                : message;
    }
}
