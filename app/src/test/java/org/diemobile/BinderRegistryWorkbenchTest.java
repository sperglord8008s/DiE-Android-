// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Looper;
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
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.concurrent.*;

/** Real parser/private capture/viewer; fixtures substitute OS output, never execute a sample. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BinderRegistryWorkbenchTest {
    private ActivityController<InvestigationActivity> controller;
    private InvestigationActivity activity;
    private File capture;
    private byte[] raw;

    @After
    public void cleanup() throws Exception {
        if (controller != null) {
            ExecutorService worker = ReflectionHelpers.getField(activity, "worker");
            controller.pause().stop().destroy();
            assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        }
        File root = BinderServiceCapture.directory(RuntimeEnvironment.getApplication());
        if (root.exists())
            try (var files = Files.walk(root.toPath())) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
    }

    private void launch(int count, boolean diagnostic) throws Exception {
        StringBuilder output = new StringBuilder("Found " + count + " services:\n");
        for (int i = 0; i < count; i++)
            output.append(i)
                    .append("\tservice")
                    .append(i)
                    .append("媒体: [")
                    .append(i == 0 ? "" : "android.test.IFixture")
                    .append("]\n");
        if (diagnostic) output.append("Permission denial: no registry access\n");
        raw = output.toString().getBytes(StandardCharsets.UTF_8);
        JSONObject context =
                new JSONObject()
                        .put("callerUid", 10001)
                        .put("callerPid", 123)
                        .put("androidApi", 28)
                        .put("privilegedBridgeUsed", false)
                        .put("startedAt", "fixture-before-recreation");
        JSONObject envelope =
                BinderRegistry.envelope(
                        new BoundedProcessOutput.Result(
                                raw, diagnostic ? 1 : 0, true, false, false, ""),
                        context);
        File root = BinderServiceCapture.directory(RuntimeEnvironment.getApplication());
        Files.createDirectories(root.toPath());
        capture = File.createTempFile("binder-command-", ".json", root);
        StringWriter encoded = new StringWriter();
        EvidenceJson.write(encoded, envelope, () -> false);
        byte[] bytes = encoded.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(capture.toPath(), bytes);
        open(
                capture,
                Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                bytes.length);
    }

    private void open(File source, String sha, long size) throws Exception {
        controller =
                Robolectric.buildActivity(
                                InvestigationActivity.class,
                                new Intent(
                                                RuntimeEnvironment.getApplication(),
                                                InvestigationActivity.class)
                                        .putExtra("provider", "binder-registry")
                                        .putExtra("path", source.toString())
                                        .putExtra("name", "Captured Binder observations")
                                        .putExtra("sha256", sha)
                                        .putExtra("sourceSize", size))
                        .setup()
                        .visible();
        activity = controller.get();
        settle();
    }

    private Button button(String field) {
        return ReflectionHelpers.getField(activity, field);
    }

    private EvidenceRows rows() {
        return ReflectionHelpers.getField(activity, "source");
    }

    private String status() {
        return ReflectionHelpers.<TextView>getField(activity, "status").getText().toString();
    }

    private void settle() throws Exception {
        for (int i = 0; i < 8; i++) {
            ReflectionHelpers.<ExecutorService>getField(activity, "worker")
                    .submit(() -> {})
                    .get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!ReflectionHelpers.<Boolean>getField(activity, "busy")) return;
        }
        fail("Binder viewer did not settle: " + status());
    }

    private void root(String label) throws Exception {
        button("views").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView choices = dialog.getListView();
        for (int i = 0; i < choices.getAdapter().getCount(); i++)
            if (choices.getAdapter().getItem(i).toString().contains(label)) {
                choices.performItemClick(null, i, i);
                dialog.dismiss();
                settle();
                return;
            }
        fail("Missing Binder root: " + label);
    }

    private byte[] finishExport() throws Exception {
        Uri target = Uri.parse("content://org.diemobile.test/binder-export");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(target, bytes);
        activity.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(target));
        settle();
        return bytes.toByteArray();
    }

    @Test
    public void parsedRegistrationsPageSearchAndCompleteExportKeepTheirCapturedDomains()
            throws Exception {
        launch(155, false);
        assertNotNull(status(), rows());
        assertEquals(155, rows().size());
        assertTrue(rows().row(1, () -> false).description.contains("android.test.IFixture"));
        ListView list = activity.findViewById(R.id.investigation_records);
        assertEquals(50, list.getAdapter().getCount());
        button("next").performClick();
        settle();
        assertEquals(50, ((EvidenceRows.Row) list.getAdapter().getItem(0)).index);
        ((EditText) activity.findViewById(R.id.investigation_filter)).setText("service14");
        button("search").performClick();
        settle();
        button("export").performClick();
        settle();
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        JSONObject exported = new JSONObject(new String(finishExport(), StandardCharsets.UTF_8));
        assertEquals(155, exported.getJSONArray("rows").length());
        assertEquals(
                "captured command output bytes",
                exported.getJSONArray("rows").getJSONObject(0).getString("coordinateDomain"));
        root("Every captured output line");
        assertEquals(156, rows().size());
        root("Pinned AOSP command-output contract");
        PreparedExport preview = ReflectionHelpers.getField(activity, "preview");
        assertNotNull(preview);
        assertEquals(
                BinderRegistry.REFERENCE_SHA256,
                Inspector.toHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(preview.file.toPath()))));
    }

    @Test
    public void diagnosticsStaySeparateAndExactPendingBytesSurviveRecreationAndSourceExpiry()
            throws Exception {
        launch(1, true);
        assertEquals(1, rows().size());
        assertFalse(rows().row(0, () -> false).json().getBoolean("commandSucceeded"));
        root("Command diagnostics");
        assertEquals(1, rows().size());
        assertEquals("diagnostic", rows().row(0, () -> false).json().getString("kind"));
        controller.recreate();
        activity = controller.get();
        settle();
        assertTrue(capture.isFile());
        root("Export exact captured output bytes");
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        assertTrue(capture.delete());
        controller.recreate();
        activity = controller.get();
        settle();
        assertTrue(status(), status().contains("expired"));
        assertArrayEquals(raw, finishExport());
    }

    @Test
    public void productionLaunchFailureIsCapturedAsFailureWithoutInventedRegistrations()
            throws Exception {
        // This Linux host lacks Android's /system/bin/service. Exercise the real
        // production launch-failure path and digest writer, not fabricated success.
        assertFalse(new File("/system/bin/service").exists());
        BinderServiceCapture.Captured unavailable =
                BinderServiceCapture.capture(RuntimeEnvironment.getApplication(), () -> false);
        capture = unavailable.file;
        assertEquals(
                unavailable.sha256,
                Inspector.toHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(capture.toPath()))));
        open(capture, unavailable.sha256, unavailable.size);
        assertNotNull(status(), rows());
        JSONObject summary = rows().row(0, () -> false).json();
        assertFalse(summary.getBoolean("commandSucceeded"));
        assertTrue(rows().row(0, () -> false).name.contains("failed or incomplete"));
        assertTrue(rows().row(0, () -> false).description.contains("Exit="));
        assertEquals(0, summary.getInt("parsedRegistrationRows"));
        assertFalse(summary.getJSONObject("captureMetadata").getString("captureError").isEmpty());
    }
}
