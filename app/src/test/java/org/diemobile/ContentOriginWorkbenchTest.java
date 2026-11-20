// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
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

/** Actual private origin captures, viewer widgets and prepared exports; no engine stand-in. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ContentOriginWorkbenchTest {
    private Path temporary;
    private ActivityController<ContentPreviewActivity> controller;
    private ContentPreviewActivity activity;
    private ContentOrigin origin;
    private InputSnapshot subject;
    private File source;
    private Intent intent;

    @Before
    public void setup() throws Exception {
        temporary = Files.createTempDirectory("glassdigestor-origin-ui");
        source = temporary.resolve("parent.bin").toFile();
        Files.write(
                source.toPath(),
                "Visible exact selected text 世界\n".getBytes(StandardCharsets.UTF_8));
        File cache = RuntimeEnvironment.getApplication().getCacheDir();
        try (InputSnapshot parent =
                InputSnapshot.acquire(
                        source,
                        temporary.resolve("inputs").toFile(),
                        FileRange.MAX_OPERATION_BYTES,
                        () -> false)) {
            FileRange range = new FileRange(parent.size, 0, parent.size);
            subject =
                    OwnedContent.stageRange(
                            parent, range, new File(cache, "content-previews"), () -> false);
            origin =
                    ContentOrigin.create(
                            new File(cache, "content-origins"),
                            subject,
                            ContentOrigin.stored(parent, range),
                            records ->
                                    records.value(
                                            "originalFinding",
                                            new JSONObject()
                                                    .put(
                                                            "unknownField",
                                                            "FIRST-WITNESS\n"
                                                                    + "Complete 世界 evidence 😀\n"
                                                                            .repeat(10000)
                                                                    + "LAST-WITNESS")
                                                    .put("unverified", true)
                                                    .put("unpaired", "\ud800")),
                            () -> false);
        }
        intent =
                new Intent(RuntimeEnvironment.getApplication(), ContentPreviewActivity.class)
                        .putExtra("path", subject.file.getAbsolutePath())
                        .putExtra("sha256", subject.sha256)
                        .putExtra("sourceSize", subject.size)
                        .putExtra("name", "owned preview")
                        .putExtra("originalSha256", subject.sha256)
                        .putExtra("originalOffset", 0L)
                        .putExtra("contentOrigin", origin.descriptor());
    }

    @After
    public void cleanup() throws Exception {
        if (controller != null) {
            activity.finish();
            ExecutorService worker = ReflectionHelpers.getField(activity, "worker");
            controller.pause().stop().destroy();
            assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        if (origin != null) origin.close();
        if (subject != null) subject.close();
        try (var files = Files.walk(temporary)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(file);
        }
    }

    private void settle() throws Exception {
        for (int i = 0; i < 6; i++) {
            ReflectionHelpers.<ExecutorService>getField(activity, "worker")
                    .submit(() -> {})
                    .get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private void open() throws Exception {
        controller =
                Robolectric.buildActivity(ContentPreviewActivity.class, intent).setup().visible();
        activity = controller.get();
        settle();
    }

    private AlertDialog showOrigin() throws Exception {
        Button button = activity.getWindow().getDecorView().findViewWithTag("content-origin");
        assertTrue(button.isEnabled());
        button.performClick();
        settle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private PreparedExport prepare() throws Exception {
        AlertDialog dialog = showOrigin();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        PreparedExport staged = ReflectionHelpers.getField(activity, "contentExport");
        assertNotNull(staged);
        assertEquals("application/x-ndjson", staged.mime);
        var picker = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent.getAction());
        assertEquals("application/x-ndjson", picker.intent.getType());
        return staged;
    }

    @Test
    public void completeWitnessPagesAndPendingExportSurviveSourceExpiryAndRecreation()
            throws Exception {
        byte[] expected = Files.readAllBytes(origin.file.toPath());
        assertTrue(source.delete());
        open();
        AlertDialog dialog = showOrigin();
        TextView body = dialog.findViewById(android.R.id.content).findViewWithTag("text-page-body");
        assertTrue(body.getText().toString(), body.getText().toString().contains("FIRST-WITNESS"));
        Button tail = dialog.getWindow().getDecorView().findViewWithTag("text-page-tail");
        tail.performClick();
        settle();
        assertTrue(body.getText().toString().contains("LAST-WITNESS"));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        settle();
        assertTrue(origin.file.isFile());
        controller.recreate();
        activity = controller.get();
        settle();
        assertNotNull(ReflectionHelpers.getField(activity, "origin"));
        PreparedExport staged = prepare();
        assertArrayEquals(expected, Files.readAllBytes(staged.file.toPath()));
        assertTrue(origin.file.delete());
        assertTrue(subject.file.delete());
        controller.recreate();
        activity = controller.get();
        settle();
        Uri destination = Uri.parse("content://org.diemobile.test/complete-origin");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(destination, output);
        activity.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(destination));
        settle();
        assertArrayEquals(expected, output.toByteArray());
        assertFalse(staged.file.exists());
    }

    @Test
    public void closingEvidenceKeepsItsCaptureAndFinishingDeletesOnlyOwnedFiles() throws Exception {
        open();
        AlertDialog dialog = showOrigin();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        settle();
        assertTrue(origin.file.isFile());
        assertTrue(subject.file.isFile());
        activity.finish();
        ExecutorService worker = ReflectionHelpers.getField(activity, "worker");
        controller.pause().stop().destroy();
        controller = null;
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        assertFalse(origin.file.exists());
        assertFalse(subject.file.exists());
        assertTrue(source.isFile());
    }

    @Test
    public void changedWitnessIsRefusedWhileExactContentPreviewRemainsUsable() throws Exception {
        byte[] corrupt = Files.readAllBytes(origin.file.toPath());
        corrupt[corrupt.length - 2] ^= 1;
        Files.write(origin.file.toPath(), corrupt);
        open();
        assertNull(ReflectionHelpers.getField(activity, "origin"));
        Button button = activity.getWindow().getDecorView().findViewWithTag("content-origin");
        assertFalse(button.isEnabled());
        assertTrue(button.getText().toString().contains("unavailable"));
        TextView body = activity.getWindow().getDecorView().findViewWithTag("content-text");
        assertEquals(
                new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8),
                body.getText().toString());
        ReflectionHelpers.<Button>getField(activity, "exportBytes").performClick();
        settle();
        PreparedExport bytes = ReflectionHelpers.getField(activity, "contentExport");
        assertArrayEquals(
                Files.readAllBytes(source.toPath()), Files.readAllBytes(bytes.file.toPath()));
        activity.onActivityResult(1, Activity.RESULT_CANCELED, null);
        settle();
        assertFalse(bytes.file.exists());
    }

    @Test
    public void cancelledAndFailedOriginDestinationsKeepTheCaptureAndAllowRetry() throws Exception {
        open();
        PreparedExport cancelled = prepare();
        activity.onActivityResult(1, Activity.RESULT_CANCELED, null);
        settle();
        assertFalse(cancelled.file.exists());
        assertTrue(origin.file.isFile());
        PreparedExport failed = prepare();
        Uri destination = Uri.parse("content://org.diemobile.test/refused-origin");
        Shadows.shadowOf(activity.getContentResolver())
                .registerOutputStream(
                        destination,
                        new OutputStream() {
                            @Override
                            public void write(int value) throws IOException {
                                throw new IOException("fixture write failure");
                            }
                        });
        activity.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(destination));
        settle();
        assertFalse(failed.file.exists());
        assertTrue(origin.file.isFile());
        assertTrue(
                ReflectionHelpers.<TextView>getField(activity, "exportNotice")
                        .getText()
                        .toString()
                        .contains("partial data"));
        assertNotNull(prepare());
    }
}
