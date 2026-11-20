// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.*;
import android.widget.*;

import org.json.JSONObject;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Real snapshots, UTF-8 IO, widgets and exports; generated report text is an independent fixture.
 */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TextPagingWorkbenchTest {
    private Path temporary;
    private ActivityController<ContentPreviewActivity> previewController;
    private ContentPreviewActivity preview;
    private ActivityController<InvestigationActivity> reportController;
    private InvestigationActivity report;
    private String largeText;
    private byte[] bytes;

    @Before
    public void setup() throws Exception {
        temporary = Files.createTempDirectory("glassdigestor-text-ui");
        // The initial 4096-byte recognition sample ends halfway through this emoji.
        largeText =
                "a".repeat(4094)
                        + "😀\n"
                        + "世界 long evidence line 😀\r\n".repeat(70000)
                        + "FINAL-BEYOND-ONE-MIB-終😀";
        bytes = largeText.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length > 1024 * 1024);
    }

    @After
    public void cleanup() throws Exception {
        if (previewController != null) {
            preview.finish();
            close(previewController);
        }
        if (reportController != null) {
            report.finish();
            close(reportController);
        }
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    private void close(ActivityController<? extends Activity> controller) throws Exception {
        ExecutorService worker = ReflectionHelpers.getField(controller.get(), "worker");
        controller.pause().stop().destroy();
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void settle(Activity activity) throws Exception {
        for (int i = 0; i < 8; i++) {
            ReflectionHelpers.<ExecutorService>getField(activity, "worker")
                    .submit(() -> {})
                    .get(45, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private View tagged(Activity activity, String tag) {
        return activity.getWindow().getDecorView().findViewWithTag(tag);
    }

    private String status(Activity activity) {
        return ReflectionHelpers.<TextView>getField(activity, "status").getText().toString();
    }

    private String body() {
        return ((TextView) tagged(preview, "content-text")).getText().toString();
    }

    private void click(String tag) throws Exception {
        Button button = (Button) tagged(preview, tag);
        assertNotNull(tag, button);
        assertTrue(tag, button.isEnabled());
        button.performClick();
        settle(preview);
    }

    private void openPreview() throws Exception {
        File original = temporary.resolve("large-mislabeled.bin").toFile();
        Files.write(original.toPath(), bytes);
        InputSnapshot input =
                InputSnapshot.acquire(
                        original,
                        new File(
                                RuntimeEnvironment.getApplication().getCacheDir(),
                                "content-previews"),
                        FileRange.MAX_OPERATION_BYTES,
                        () -> false);
        Intent intent =
                new Intent(RuntimeEnvironment.getApplication(), ContentPreviewActivity.class)
                        .putExtra("path", input.file.getAbsolutePath())
                        .putExtra("sha256", input.sha256)
                        .putExtra("sourceSize", input.size)
                        .putExtra("name", "large-mislabeled.bin")
                        .putExtra("originalSha256", input.sha256)
                        .putExtra("originalOffset", 0L);
        previewController =
                Robolectric.buildActivity(ContentPreviewActivity.class, intent).setup().visible();
        preview = previewController.get();
        settle(preview);
    }

    private void mode(int index) throws Exception {
        ReflectionHelpers.callInstanceMethod(preview, "chooseMode");
        ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null, index, index);
        settle(preview);
    }

    @Test
    public void unicodePagesTailAndBackSurviveRecreationWithoutDroppingSampleSeam()
            throws Exception {
        openPreview();
        assertTrue(status(preview), status(preview).startsWith("UTF-8"));
        assertTrue(body().startsWith("a".repeat(4094) + "😀"));
        String first = body();
        assertTrue(first.length() <= Utf8Pages.CHARACTERS);
        click("content-text-next");
        String second = body();
        assertTrue(largeText.startsWith(first + second));
        long offset = first.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(
                offset, ReflectionHelpers.<Utf8Pages.Cursor>getField(preview, "textCursor").offset);
        previewController.recreate();
        preview = previewController.get();
        settle(preview);
        assertEquals(second, body());
        click("content-text-back");
        assertEquals(first, body());
        click("content-text-tail");
        assertTrue(body(), body().endsWith("FINAL-BEYOND-ONE-MIB-終😀"));
        Utf8Pages.Page tail = ReflectionHelpers.getField(preview, "textPage");
        assertTrue(tail.offset > 1024 * 1024);
        assertArrayEquals(
                Arrays.copyOfRange(bytes, (int) tail.offset, bytes.length),
                body().getBytes(StandardCharsets.UTF_8));
        assertFalse(((Button) tagged(preview, "content-text-next")).isEnabled());
        String retained = body();
        click("content-text-jump");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ((EditText)
                        dialog.findViewById(android.R.id.content)
                                .findViewWithTag("content-byte-offset"))
                .setText("4095");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle(preview);
        assertTrue(status(preview), status(preview).contains("Invalid UTF-8 at byte 4095"));
        assertEquals(retained, body());
        assertEquals(
                tail.offset,
                ReflectionHelpers.<Utf8Pages.Cursor>getField(preview, "textCursor").offset);
    }

    @Test
    public void hexPagesUseActualOffsetsAndRetainTailPositionAcrossRecreation() throws Exception {
        openPreview();
        mode(5);
        assertTrue(body().startsWith("00000000  "));
        click("content-hex-next");
        assertTrue(body().startsWith("00001000  "));
        click("content-hex-tail");
        long start = bytes.length - 4096;
        assertTrue(body(), body().startsWith(String.format(Locale.ROOT, "%08X  ", start)));
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        for (String line : body().split("\n"))
            for (String value : line.substring(line.indexOf("  ") + 2).trim().split(" +"))
                decoded.write(Integer.parseInt(value, 16));
        assertArrayEquals(
                Arrays.copyOfRange(bytes, (int) start, bytes.length), decoded.toByteArray());
        String tail = body();
        previewController.recreate();
        preview = previewController.get();
        settle(preview);
        assertEquals(tail, body());
        assertEquals(start, ReflectionHelpers.<Long>getField(preview, "hexOffset").longValue());
    }

    private AtomicInteger openReport() throws Exception {
        return openReport(true);
    }

    private AtomicInteger openReport(boolean waitForPage) throws Exception {
        File original = temporary.resolve("small-source.txt").toFile();
        Files.write(original.toPath(), "small source".getBytes(StandardCharsets.UTF_8));
        reportController =
                Robolectric.buildActivity(
                                InvestigationActivity.class,
                                new Intent(
                                                RuntimeEnvironment.getApplication(),
                                                InvestigationActivity.class)
                                        .putExtra("provider", "strings")
                                        .putExtra("path", original.getAbsolutePath())
                                        .putExtra("name", "small-source.txt"))
                        .setup()
                        .visible();
        report = reportController.get();
        settle(report);
        InvestigationBackend originalBackend = ReflectionHelpers.getField(report, "backend");
        AtomicInteger renders = new AtomicInteger();
        InvestigationBackend fixture =
                new InvestigationBackend() {
                    String initialRequest(BooleanSupplier stop) throws Exception {
                        return originalBackend.initialRequest(stop);
                    }

                    EvidenceRows open(String request, BooleanSupplier stop) throws Exception {
                        return originalBackend.open(request, stop);
                    }

                    List<Link> roots(String request, BooleanSupplier stop) throws Exception {
                        return originalBackend.roots(request, stop);
                    }

                    List<Link> related(
                            String request,
                            EvidenceRows rows,
                            EvidenceRows.Row row,
                            BooleanSupplier stop)
                            throws Exception {
                        return originalBackend.related(request, rows, row, stop);
                    }

                    JSONObject provenance() throws Exception {
                        return originalBackend.provenance();
                    }

                    File byteSource() {
                        return originalBackend.byteSource();
                    }

                    String sourceName() {
                        return originalBackend.sourceName();
                    }

                    String help(String request) {
                        return originalBackend.help(request);
                    }

                    void writeText(String request, Writer output, BooleanSupplier stop)
                            throws Exception {
                        EvidenceRows.check(stop);
                        renders.incrementAndGet();
                        output.write(largeText);
                    }

                    public void close() throws Exception {
                        originalBackend.close();
                    }
                };
        ReflectionHelpers.setField(report, "backend", fixture);
        ReflectionHelpers.callInstanceMethod(
                report,
                "renderText",
                ReflectionHelpers.ClassParameter.from(
                        InvestigationBackend.Link.class,
                        InvestigationBackend.Link.text(
                                "Complete independent fixture", "fixture", "large-evidence.txt")));
        if (waitForPage) settle(report);
        return renders;
    }

    private View dialogTag(String tag) {
        return ShadowAlertDialog.getLatestAlertDialog()
                .getWindow()
                .getDecorView()
                .findViewWithTag(tag);
    }

    private void dialogClick(String tag) throws Exception {
        Button button = (Button) dialogTag(tag);
        assertNotNull(tag, button);
        assertTrue(tag, button.isEnabled());
        button.performClick();
        settle(report);
    }

    @Test
    public void stagedReportPagesAndCompleteExportSurviveOriginalSourceExpiry() throws Exception {
        AtomicInteger renders = openReport();
        PreparedExport staged = ReflectionHelpers.getField(report, "preview");
        assertNotNull(staged);
        assertArrayEquals(bytes, Files.readAllBytes(staged.file.toPath()));
        String first = ((TextView) dialogTag("text-page-body")).getText().toString();
        assertTrue(largeText.startsWith(first));
        ReflectionHelpers.<InvestigationBackend>getField(report, "backend").byteSource().delete();
        dialogClick("text-page-tail");
        assertTrue(
                ((TextView) dialogTag("text-page-body"))
                        .getText()
                        .toString()
                        .endsWith("FINAL-BEYOND-ONE-MIB-終😀"));
        dialogClick("text-page-back");
        assertEquals(first, ((TextView) dialogTag("text-page-body")).getText().toString());
        assertEquals(1, renders.get());
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        settle(report);
        assertSame(staged, ReflectionHelpers.getField(report, "prepared"));
        assertTrue(staged.file.isFile());
        Uri destination = Uri.parse("content://org.diemobile.test/paged-text-output");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shadows.shadowOf(report.getContentResolver()).registerOutputStream(destination, output);
        report.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(destination));
        settle(report);
        assertArrayEquals(bytes, output.toByteArray());
        assertFalse(staged.file.exists());
        assertEquals(1, renders.get());
    }

    @Test
    public void closingPagedReportDiscardsOnlyItsStage() throws Exception {
        openReport();
        PreparedExport staged = ReflectionHelpers.getField(report, "preview");
        File source =
                ReflectionHelpers.<InvestigationBackend>getField(report, "backend").byteSource();
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_NEGATIVE)
                .performClick();
        settle(report);
        assertFalse(staged.file.exists());
        assertTrue(source.isFile());
        assertNull(ReflectionHelpers.getField(report, "preview"));
    }

    @Test
    public void initialTextReadKeepsWorkBusyAndClosingCancelsTheQueuedPage() throws Exception {
        openReport(false);
        ExecutorService worker = ReflectionHelpers.getField(report, "worker");
        worker.submit(() -> {})
                .get(45, TimeUnit.SECONDS); // Complete staging before opening its dialog.
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        worker.execute(
                () -> {
                    entered.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper())
                    .idle(); // First page is now queued behind the controlled worker.
            assertTrue(ReflectionHelpers.<Boolean>getField(report, "busy"));
            PreparedExport staged = ReflectionHelpers.getField(report, "preview");
            assertNotNull(staged);
            assertTrue(staged.file.isFile());
            assertEquals("", ((TextView) dialogTag("text-page-body")).getText().toString());
            ShadowAlertDialog.getLatestAlertDialog()
                    .getButton(AlertDialog.BUTTON_NEGATIVE)
                    .performClick();
            Shadows.shadowOf(Looper.getMainLooper())
                    .idle(); // Android dispatches OnDismiss through its handler.
            assertFalse(ReflectionHelpers.<Boolean>getField(report, "busy"));
            assertFalse(staged.file.exists());
        } finally {
            release.countDown();
        }
        settle(report);
        assertNull(ReflectionHelpers.getField(report, "preview"));
    }
}
