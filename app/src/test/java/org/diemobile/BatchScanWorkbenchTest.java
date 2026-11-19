// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual CDiE/Inspector/private files/widgets; only external document streams are substituted. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BatchScanWorkbenchTest {
    private ActivityController<BatchScanActivity> controller;
    private BatchScanActivity activity;
    private ActivityController<InvestigationActivity> comparisonController;
    private InvestigationActivity investigation;
    private File root;
    private byte[] elf;
    private final ScanOptions captured =
            new ScanOptions(
                    ScanOptions.DEFAULT_FLAGS | ScanOptions.RESOURCES | ScanOptions.CACHE,
                    9,
                    "",
                    "");

    @Before
    public void setup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        root = new File(context.getFilesDir(), "desktop-batches");
        remove(root.toPath());
        ScanPreferences.save(context, captured);
        elf =
                Files.readAllBytes(
                        new File(System.getProperty("die.fixture.dir"), "elf-fixture").toPath());
        controller = Robolectric.buildActivity(BatchScanActivity.class).setup().visible();
        activity = controller.get();
    }

    @After
    public void cleanup() throws Exception {
        if (comparisonController != null) {
            ExecutorService worker = ReflectionHelpers.getField(investigation, "worker");
            comparisonController.pause().stop().destroy();
            assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        }
        if (controller != null) {
            Object state = state();
            controller.pause().stop().destroy();
            assertTrue(
                    ReflectionHelpers.<ExecutorService>getField(state, "worker")
                            .awaitTermination(30, TimeUnit.SECONDS));
        }
        remove(root.toPath());
    }

    private static void remove(Path path) throws Exception {
        if (Files.exists(path))
            try (var paths = Files.walk(path)) {
                for (Path item : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(item);
            }
    }

    private Object state() {
        return ReflectionHelpers.getField(activity, "state");
    }

    private Button button(String field) {
        return ReflectionHelpers.getField(activity, field);
    }

    private String status() {
        return ReflectionHelpers.<TextView>getField(activity, "status").getText().toString();
    }

    private void settle() throws Exception {
        for (int i = 0; i < 10; i++) {
            ReflectionHelpers.<ExecutorService>getField(state(), "worker")
                    .submit(() -> {})
                    .get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!ReflectionHelpers.<Boolean>getField(state(), "busy")) return;
        }
        fail("Batch work did not settle: " + status());
    }

    private Uri input(String suffix, byte[] bytes) {
        Uri uri = Uri.parse("content://org.diemobile.test/" + suffix);
        Shadows.shadowOf(activity.getContentResolver())
                .registerInputStream(uri, new ByteArrayInputStream(bytes));
        return uri;
    }

    private void start(Uri... inputs) {
        button("choose").performClick();
        var picker = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(picker);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.getAction());
        assertTrue(picker.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        ClipData clip =
                new ClipData(
                        "fixture inputs",
                        new String[] {"application/octet-stream"},
                        new ClipData.Item(inputs[0]));
        for (int i = 1; i < inputs.length; i++) clip.addItem(new ClipData.Item(inputs[i]));
        Intent returned = new Intent();
        returned.setClipData(clip);
        activity.onActivityResult(1, Activity.RESULT_OK, returned);
    }

    private BatchScanSession session() throws Exception {
        return BatchScanSession.open(root, ReflectionHelpers.getField(state(), "id"), () -> false);
    }

    private void requireInterpretation(String name) {
        button("interpretation").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText field =
                dialog.findViewById(android.R.id.content)
                        .findViewWithTag("batch-interpretation-name");
        assertNotNull(field);
        field.setText(name);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
    }

    private void automaticInterpretation() {
        button("interpretation").performClick();
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_NEUTRAL)
                .performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void explicitBatchPassCapturesEachNativeCatalogAndPendingExport() throws Exception {
        requireInterpretation("Binary");
        controller.recreate();
        activity = controller.get();
        settle();
        assertEquals(
                "Binary",
                ReflectionHelpers.<ScanTarget.Request>getField(state(), "draftInterpretation")
                        .formatName);
        start(input("explicit-elf.bin", elf), input("explicit-raw.bin", new byte[] {1, 2, 3, 4}));
        settle();
        BatchScanSession session = session();
        assertEquals(2, session.schemaVersion);
        assertTrue(session.metadata().getJSONObject("options").isNull("typeId"));
        for (BatchScanSession.Entry entry : session.entries) {
            assertEquals(entry.error, "complete", entry.status);
            JSONObject report = session.report(entry, () -> false);
            int selected = report.getJSONObject("options").getInt("typeId");
            assertNotEquals(0, selected);
            assertEquals(selected, report.getJSONObject("scan").getInt("typeId"));
            assertEquals(selected, report.getJSONObject("facts").getInt("typeId"));
            assertEquals(
                    "Binary",
                    report.getJSONObject("scanTarget")
                            .getJSONObject("request")
                            .getString("formatName"));
            assertEquals(
                    entry.sha256,
                    report.getJSONObject("scanTarget").getJSONObject("source").getString("sha256"));
        }
        PreparedExport pending = prepare();
        byte[] expected = Files.readAllBytes(pending.file.toPath());
        ReflectionHelpers.setField(state(), "draftInterpretation", ScanTarget.AUTOMATIC);
        controller.recreate();
        activity = controller.get();
        settle();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        destination(output);
        assertArrayEquals(expected, output.toByteArray());
        JSONObject exported = new JSONObject(output.toString("UTF-8"));
        assertEquals(
                "Binary", exported.getJSONObject("interpretationRequest").getString("formatName"));
        assertEquals(2, exported.getJSONArray("entries").length());
        start(input("subsequent-auto.bin", elf));
        settle();
        assertTrue(session().interpretation.automatic());
        assertEquals(
                0,
                session()
                        .report(session().entry(0), () -> false)
                        .getJSONObject("scan")
                        .getInt("typeId"));
    }

    @Test
    public void unavailableBatchNameKeepsEveryFailedReportAndRecoversAutomaticallyWhenChosen()
            throws Exception {
        requireInterpretation("missing-compatible-format");
        start(input("unresolved-elf.bin", elf), input("unresolved-raw.bin", new byte[] {1, 2, 3}));
        settle();
        BatchScanSession failed = session();
        for (BatchScanSession.Entry entry : failed.entries) {
            assertEquals("failed", entry.status);
            JSONObject report = failed.report(entry, () -> false);
            assertFalse(report.getJSONObject("scan").getBoolean("ok"));
            assertTrue(report.getJSONObject("options").isNull("typeId"));
            assertTrue(
                    report.getJSONObject("scanTarget")
                            .getString("resolutionError")
                            .contains("not available"));
            assertTrue(failed.input(entry, () -> false).isFile());
        }
        automaticInterpretation();
        start(input("recovered-auto.bin", elf));
        settle();
        assertEquals("complete", session().entry(0).status);
        assertEquals(
                0,
                session()
                        .report(session().entry(0), () -> false)
                        .getJSONObject("scan")
                        .getInt("typeId"));
        assertEquals(
                "missing-compatible-format",
                failed.report(failed.entry(0), () -> false)
                        .getJSONObject("scanTarget")
                        .getJSONObject("request")
                        .getString("formatName"));
    }

    private void restoreWithoutRetainedState(Bundle saved) throws Exception {
        ExecutorService previous = ReflectionHelpers.getField(state(), "worker");
        controller.pause().stop().destroy();
        assertTrue(previous.awaitTermination(30, TimeUnit.SECONDS));
        controller =
                Robolectric.buildActivity(BatchScanActivity.class)
                        .create(saved)
                        .start()
                        .resume()
                        .visible();
        activity = controller.get();
        settle();
    }

    @Test
    public void batchNameRestoresFromSavedStateAndInvalidStateRequiresAVisibleChoice()
            throws Exception {
        requireInterpretation("Binary");
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved);
        restoreWithoutRetainedState(saved);
        assertEquals(
                "Binary",
                ReflectionHelpers.<ScanTarget.Request>getField(state(), "draftInterpretation")
                        .formatName);
        saved = new Bundle();
        controller.saveInstanceState(saved);
        saved.putString(
                "interpretation", "{\"selectionMode\":\"automatic\",\"formatName\":\"Binary\"}");
        restoreWithoutRetainedState(saved);
        assertFalse(button("choose").isEnabled());
        assertTrue(status(), status().contains("invalid"));
        automaticInterpretation();
        assertTrue(button("choose").isEnabled());
        assertTrue(
                ReflectionHelpers.<ScanTarget.Request>getField(state(), "draftInterpretation")
                        .automatic());
    }

    private PreparedExport prepare() throws Exception {
        button("export").performClick();
        settle();
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        PreparedExport result = ReflectionHelpers.getField(state(), "prepared");
        assertNotNull(result);
        assertTrue(result.file.isFile());
        return result;
    }

    private void destination(OutputStream output) throws Exception {
        Uri uri = Uri.parse("content://org.diemobile.test/batch-export");
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(uri, output);
        activity.onActivityResult(2, Activity.RESULT_OK, new Intent().setData(uri));
        settle();
    }

    @Test
    public void multipleActualNativeScansCaptureOptionsIdentitiesAndFailedDocuments()
            throws Exception {
        Uri first = input("left/same.bin", elf),
                second =
                        input(
                                "right/same.bin",
                                "raw unknown fixture bytes".getBytes(StandardCharsets.UTF_8));
        Uri missing = Uri.parse("content://org.diemobile.test/missing.bin");
        Shadows.shadowOf(activity.getContentResolver())
                .registerInputStream(
                        missing,
                        new InputStream() {
                            @Override
                            public int read() throws IOException {
                                throw new IOException("Fixture provider denied read");
                            }
                        });
        start(first, second, missing);
        ScanPreferences.save(activity, new ScanOptions(0, 3, "later", "later"));
        settle();
        BatchScanSession session = session();
        assertEquals(3, session.entries.size());
        assertTrue(session.options.sameAs(captured));
        assertEquals("complete", session.entries.get(0).status);
        assertEquals("complete", session.entries.get(1).status);
        assertEquals("failed", session.entries.get(2).status);
        assertEquals("same.bin", session.entries.get(0).name);
        assertEquals("same.bin", session.entries.get(1).name);
        assertNotEquals(session.entries.get(0).sha256, session.entries.get(1).sha256);
        JSONObject report = session.report(session.entries.get(0), () -> false);
        assertTrue(report.toString(), report.getJSONObject("scan").getBoolean("ok"));
        assertTrue(report.getJSONObject("scan").getString("type").contains("ELF"));
        assertTrue(report.getJSONObject("scan").getInt("signatureCount") > 0);
        assertEquals(captured.flags, report.getJSONObject("scan").getInt("flags"));
        assertEquals(9, report.getJSONObject("options").getInt("minimumStringLength"));
        assertEquals(
                session.engineRevision,
                report.getJSONObject("provenance").getString("engineRevision"));
        // Provider replacement cannot change the owned bytes or captured report.
        input("left/same.bin", new byte[] {9, 9});
        assertArrayEquals(
                elf,
                Files.readAllBytes(session.input(session.entries.get(0), () -> false).toPath()));
        button("results").performClick();
        Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals("desktop-batch", intent.getStringExtra("provider"));
        ActivityController<InvestigationActivity> viewer =
                Robolectric.buildActivity(InvestigationActivity.class, intent).setup().visible();
        try {
            InvestigationActivity investigation = viewer.get();
            for (int i = 0; i < 5; i++) {
                ReflectionHelpers.<ExecutorService>getField(investigation, "worker")
                        .submit(() -> {})
                        .get(30, TimeUnit.SECONDS);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                if (!ReflectionHelpers.<Boolean>getField(investigation, "busy")) break;
            }
            EvidenceRows rows = ReflectionHelpers.getField(investigation, "source");
            assertNotNull(rows);
            assertEquals(3, rows.size());
            assertNull(rows.row(0, () -> false).physicalOffset);
        } finally {
            ExecutorService worker = ReflectionHelpers.getField(viewer.get(), "worker");
            viewer.pause().stop().destroy();
            assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    public void queuedNativeCancellationSettlesEveryScopeAndDoesNotPoisonNextBatch()
            throws Exception {
        synchronized (Engine.class) {
            start(input("cancelled.bin", elf), input("never-started.bin", elf));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Object active = null;
            while (System.nanoTime() < deadline) {
                BatchScanSession.Control control =
                        ReflectionHelpers.getField(state(), "cancellation");
                synchronized (control) {
                    active = ReflectionHelpers.getField(control, "active");
                }
                if (active != null) break;
                Thread.sleep(5);
            }
            assertNotNull("The actual native request never reached its serialized queue", active);
            button("cancel").performClick();
        }
        settle();
        BatchScanSession cancelled = session();
        assertEquals("cancelled", cancelled.metadata().getString("status"));
        assertEquals("cancelled", cancelled.entries.get(0).status);
        assertEquals("not-started", cancelled.entries.get(1).status);
        assertTrue(cancelled.entries.get(0).reportName.isEmpty());
        StringWriter partial = new StringWriter();
        cancelled.writeReport(partial, () -> false);
        assertEquals(2, new JSONObject(partial.toString()).getJSONArray("entries").length());
        start(input("next.bin", elf));
        settle();
        BatchScanSession next = session();
        assertEquals("complete", next.entries.get(0).status);
        assertTrue(
                next.report(next.entries.get(0), () -> false)
                        .getJSONObject("scan")
                        .getBoolean("ok"));
        // Cancellation after the old request finished belongs only to its old batch.
        cancelled.input(cancelled.entries.get(0), () -> false);
    }

    @Test
    public void stagedWholeBatchSurvivesRecreationSourceDeletionAndNewerJournal() throws Exception {
        start(input("original.bin", elf));
        settle();
        BatchScanSession original = session();
        PreparedExport prepared = prepare();
        byte[] capturedBytes = Files.readAllBytes(prepared.file.toPath());
        original.delete();
        List<BatchScanSession.Source> newer =
                List.of(
                        new BatchScanSession.Source(
                                "later",
                                "fixture:later",
                                () -> new ByteArrayInputStream(new byte[] {1})));
        BatchScanSession.create(
                root,
                newer,
                new ScanOptions(0, 3, "", ""),
                original.engineRevision,
                original.signatureRevision,
                original.customDigest);
        ScanPreferences.save(activity, new ScanOptions(0, 3, "changed", ""));
        controller.recreate();
        activity = controller.get();
        settle();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        destination(output);
        assertArrayEquals(capturedBytes, output.toByteArray());
        JSONObject report = new JSONObject(output.toString(StandardCharsets.UTF_8));
        assertEquals(original.id, report.getString("id"));
        assertEquals(
                original.entries.get(0).sha256,
                report.getJSONArray("entries").getJSONObject(0).getString("sha256"));
        assertEquals(captured.flags, report.getJSONObject("options").getInt("flags"));
        assertFalse(prepared.file.exists());
        assertTrue(status(), status().contains("exported"));
    }

    @Test
    public void cancelledAndFailedDestinationsDiscardStageAndRetainBatch() throws Exception {
        start(input("retained.bin", elf));
        settle();
        BatchScanSession original = session();
        PreparedExport cancelled = prepare();
        activity.onActivityResult(2, Activity.RESULT_CANCELED, null);
        settle();
        assertFalse(cancelled.file.exists());
        PreparedExport failed = prepare();
        destination(
                new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        throw new IOException("Fixture destination failure");
                    }
                });
        assertFalse(failed.file.exists());
        assertTrue(status(), status().contains("partial"));
        assertArrayEquals(
                elf,
                Files.readAllBytes(original.input(original.entries.get(0), () -> false).toPath()));
        assertTrue(button("export").isEnabled());
        button("saved").performClick();
        settle();
        AlertDialog choices = ShadowAlertDialog.getLatestAlertDialog();
        choices.getListView().performItemClick(null, 0, 0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog actions = ShadowAlertDialog.getLatestAlertDialog();
        actions.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        settle();
        assertFalse(original.directory.exists());
        assertFalse(button("results").isEnabled());
    }

    @Test
    public void overLimitSelectionDoesNotSilentlyScanPrefix() throws Exception {
        Uri uri = input("too-many.bin", elf);
        Uri[] inputs = new Uri[65];
        Arrays.fill(inputs, uri);
        start(inputs);
        settle();
        assertTrue(status(), status().contains("no batch started"));
        assertFalse(root.exists());
        assertTrue(button("choose").isEnabled());
    }

    @Test
    public void runningBatchRetainsItsOwnerAcrossConfigurationWithoutRestarting() throws Exception {
        Object originalOwner = state();
        synchronized (Engine.class) {
            start(input("retained-owner.bin", elf), input("second-owned.bin", elf));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Object active = null;
            while (System.nanoTime() < deadline) {
                BatchScanSession.Control control =
                        ReflectionHelpers.getField(state(), "cancellation");
                synchronized (control) {
                    active = ReflectionHelpers.getField(control, "active");
                }
                if (active != null) break;
                Thread.sleep(5);
            }
            assertNotNull(active);
            controller.recreate();
            activity = controller.get();
            assertSame(originalOwner, state());
            assertTrue(button("cancel").isEnabled());
        }
        settle();
        BatchScanSession session = session();
        assertEquals(2, session.entries.size());
        assertTrue(session.entries.stream().allMatch(entry -> entry.status.equals("complete")));
        assertEquals(
                1,
                root.listFiles(file -> file.isDirectory() && file.getName().startsWith("batch-"))
                        .length);
    }

    @Test
    public void damagedJournalCanBeDeletedWithoutInventingOrParsingItsEvidence() throws Exception {
        start(input("damaged-journal.bin", elf));
        settle();
        BatchScanSession session = session();
        Files.write(new File(session.directory, "state.json").toPath(), new byte[] {(byte) 0xff});
        button("saved").performClick();
        settle();
        AlertDialog choices = ShadowAlertDialog.getLatestAlertDialog();
        choices.getListView().performItemClick(null, 0, 0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog deletion = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(Shadows.shadowOf(deletion).getTitle().toString().contains("Unavailable"));
        deletion.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertFalse(session.directory.exists());
        assertFalse(button("results").isEnabled());
    }

    private void viewerSettle() throws Exception {
        for (int i = 0; i < 10; i++) {
            ReflectionHelpers.<ExecutorService>getField(investigation, "worker")
                    .submit(() -> {})
                    .get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!ReflectionHelpers.<Boolean>getField(investigation, "busy")) return;
        }
        fail("Comparison viewer did not settle");
    }

    private EvidenceRows viewerRows() {
        return ReflectionHelpers.getField(investigation, "source");
    }

    private Button viewerButton(String field) {
        return ReflectionHelpers.getField(investigation, field);
    }

    private void viewerChoose(String label) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView choices = dialog.getListView();
        for (int i = 0; i < choices.getAdapter().getCount(); i++)
            if (choices.getAdapter().getItem(i).toString().equals(label)) {
                choices.performItemClick(null, i, i);
                return;
            }
        fail("Missing native comparison action: " + label);
    }

    private void viewerRoot(String label) throws Exception {
        viewerButton("views").performClick();
        viewerChoose(label);
        viewerSettle();
    }

    private void viewerAction(int position, String label) throws Exception {
        ReflectionHelpers.<ListView>getField(investigation, "records")
                .performItemClick(null, position, position);
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        viewerSettle();
        viewerChoose(label);
        viewerSettle();
    }

    private BatchScanSession openNativeComparison() throws Exception {
        start(input("first.bin", elf), input("second.bin", elf));
        settle();
        BatchScanSession captured = session();
        button("results").performClick();
        Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
        comparisonController =
                Robolectric.buildActivity(InvestigationActivity.class, intent).setup().visible();
        investigation = comparisonController.get();
        viewerSettle();
        viewerAction(0, "Compare native report with #1 second.bin");
        return captured;
    }

    @Test
    public void actualNativeTreesCompareAndJumpToOriginalSourceOrdinals() throws Exception {
        BatchScanSession captured = openNativeComparison();
        JSONObject summary = viewerRows().row(0, () -> false).json();
        assertTrue(summary.toString(), summary.getBoolean("comparisonPerformed"));
        assertTrue(summary.toString(), summary.getInt("matched") > 0);
        assertEquals(0, summary.getInt("leftOnly"));
        assertEquals(0, summary.getInt("rightOnly"));
        assertTrue(summary.getJSONObject("conditions").getBoolean("sameCapturedInputIdentity"));
        assertFalse(summary.getJSONObject("conditions").getBoolean("configurationDiffers"));
        assertEquals(
                captured.entries.get(1).reportSha256,
                summary.getJSONObject("capturedReportIdentity")
                        .getJSONObject("right")
                        .getString("reportSha256"));
        viewerRoot("Native comparison • matched");
        JSONObject pair = viewerRows().row(0, () -> false).json();
        assertNull(viewerRows().row(0, () -> false).physicalOffset);
        int originalIndex = pair.getInt("rightRowIndex");
        viewerAction(0, "Original right native row #" + originalIndex);
        JSONObject original = viewerRows().row(originalIndex, () -> false).json();
        assertEquals(1, original.getInt("sourceIndex"));
        assertEquals(
                captured.report(captured.entries.get(1), () -> false)
                        .getJSONObject("scan")
                        .getJSONArray("rows")
                        .getJSONObject(originalIndex)
                        .toString(),
                original.getJSONObject("nativeRow").toString());
        viewerAction(0, "Exact owned input / hex / range export");
        JSONObject origin =
                new JSONObject(ReflectionHelpers.<String>getField(investigation, "provenance"));
        assertEquals(1, origin.getJSONObject("byteSource").getInt("sourceIndex"));
        assertArrayEquals(
                elf,
                Files.readAllBytes(
                        ReflectionHelpers.<File>getField(investigation, "bytes").toPath()));
        viewerButton("back").performClick();
        viewerSettle();
        viewerButton("back").performClick();
        viewerSettle();
        comparisonController.recreate();
        investigation = comparisonController.get();
        viewerSettle();
        assertEquals(summary.getInt("matched"), viewerRows().size());
    }

    @Test
    public void completeNativeComparisonExportOutlivesFilterSessionAndRecreation()
            throws Exception {
        BatchScanSession captured = openNativeComparison();
        int matched = viewerRows().row(0, () -> false).json().getInt("matched");
        viewerRoot("Native comparison • all");
        ReflectionHelpers.<EditText>getField(investigation, "filter")
                .setText("no-native-record-matches-this-fixture");
        viewerButton("search").performClick();
        viewerSettle();
        assertEquals(
                0, ReflectionHelpers.<EvidenceRows.Page>getField(investigation, "shown").matches);
        viewerRoot("Preview / export complete native-tree comparison JSON");
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        var picker = Shadows.shadowOf(investigation).getNextStartedActivityForResult();
        assertNotNull(picker);
        assertEquals("application/json", picker.intent.getType());
        PreparedExport prepared = ReflectionHelpers.getField(investigation, "prepared");
        byte[] bytes = Files.readAllBytes(prepared.file.toPath());
        captured.delete();
        ScanPreferences.save(activity, new ScanOptions(0, 3, "later", ""));
        comparisonController.recreate();
        investigation = comparisonController.get();
        viewerSettle();
        Uri destination = Uri.parse("content://org.diemobile.test/native-comparison-export");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shadows.shadowOf(investigation.getContentResolver())
                .registerOutputStream(destination, output);
        investigation.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(destination));
        viewerSettle();
        assertArrayEquals(bytes, output.toByteArray());
        JSONObject exported = new JSONObject(output.toString(StandardCharsets.UTF_8));
        assertEquals(matched, exported.getJSONArray("records").length());
        assertEquals(
                captured.id, exported.getJSONObject("capturedReportIdentity").getString("batchId"));
        assertEquals(
                captured.options.flags,
                exported.getJSONObject("left").getJSONObject("options").getInt("flags"));
        assertFalse(prepared.file.exists());
    }

    @Test
    public void changedStoredReportFailsRecreationWithoutInventingAnEmptyComparison()
            throws Exception {
        BatchScanSession captured = openNativeComparison();
        int matched = viewerRows().row(0, () -> false).json().getInt("matched");
        File report = new File(captured.directory, captured.entries.get(1).reportName);
        byte[] original = Files.readAllBytes(report.toPath()), changed = original.clone();
        changed[changed.length - 1] ^= 1;
        Files.write(report.toPath(), changed);
        comparisonController.recreate();
        investigation = comparisonController.get();
        viewerSettle();
        assertNull(viewerRows());
        assertTrue(
                ReflectionHelpers.<TextView>getField(investigation, "status")
                        .getText()
                        .toString()
                        .contains("SHA-256 changed"));
        Files.write(report.toPath(), original);
        viewerButton("reload").performClick();
        viewerSettle();
        assertTrue(viewerRows().row(0, () -> false).json().getBoolean("comparisonPerformed"));
        assertEquals(matched, viewerRows().row(0, () -> false).json().getInt("matched"));
    }
}
