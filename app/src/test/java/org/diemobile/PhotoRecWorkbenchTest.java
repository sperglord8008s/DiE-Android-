// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Real PhotoRec JNI/widgets; only isolated OS transport and output streams are substituted. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28, shadows = PhotoRecWorkbenchTest.HostWorkerTransport.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PhotoRecWorkbenchTest {
    @Implements(value = BinaryWorkerClient.class, isInAndroidSdk = false)
    public static final class HostWorkerTransport {
        @Implementation
        public String scan(File input, BooleanSupplier stop) throws Exception {
            EvidenceRows.check(stop);
            try (RandomAccessFile source = new RandomAccessFile(input, "r")) {
                Field field = FileDescriptor.class.getDeclaredField("fd");
                field.setAccessible(true);
                String report = PhotoRecEngine.scanFd(field.getInt(source.getFD()));
                EvidenceRows.check(stop);
                return report;
            }
        }
    }

    private ActivityController<InvestigationActivity> controller;
    private InvestigationActivity activity;
    private Path temporary;
    private File input;
    private CarvingFixture fixture;

    @Before
    public void create() throws Exception {
        assertTrue(
                "Actual upstream PhotoRec JNI required: " + PhotoRecEngine.ERROR,
                PhotoRecEngine.AVAILABLE);
        temporary = Files.createTempDirectory("die-photorec-ui");
        input = temporary.resolve("embedded-media.bin").toFile();
        fixture = new CarvingFixture();
        Files.write(input.toPath(), fixture.bytes);
        Intent intent =
                new Intent(RuntimeEnvironment.getApplication(), InvestigationActivity.class)
                        .putExtra("provider", "photorec")
                        .putExtra("path", input.getAbsolutePath())
                        .putExtra("name", input.getName());
        controller =
                Robolectric.buildActivity(InvestigationActivity.class, intent).setup().visible();
        activity = controller.get();
        settle();
    }

    @After
    public void cleanup() throws Exception {
        if (controller != null) {
            ExecutorService worker = ReflectionHelpers.getField(activity, "worker");
            controller.pause().stop().destroy();
            assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        }
        if (temporary != null)
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
    }

    private Button button(String field) {
        return ReflectionHelpers.getField(activity, field);
    }

    private ListView list() {
        return activity.findViewById(R.id.investigation_records);
    }

    private EvidenceRows.Row row(int i) {
        return (EvidenceRows.Row) list().getAdapter().getItem(i);
    }

    private String status() {
        return ((TextView) activity.findViewById(R.id.investigation_status)).getText().toString();
    }

    private void settle() throws Exception {
        for (int i = 0; i < 8; i++) {
            ReflectionHelpers.<ExecutorService>getField(activity, "worker")
                    .submit(() -> {})
                    .get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!ReflectionHelpers.<Boolean>getField(activity, "busy")) return;
        }
        fail("PhotoRec viewer did not settle: " + status());
    }

    private void search(String query) throws Exception {
        ((EditText) activity.findViewById(R.id.investigation_filter)).setText(query);
        button("search").performClick();
        settle();
    }

    private void choose(String label) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView items = dialog.getListView();
        for (int i = 0; i < items.getAdapter().getCount(); i++)
            if (items.getAdapter().getItem(i).toString().contains(label)) {
                items.performItemClick(null, i, i);
                dialog.dismiss();
                return;
            }
        fail("Missing carving action: " + label);
    }

    private void root(String label) throws Exception {
        button("views").performClick();
        choose(label);
        settle();
    }

    private void action(int selected, String label) throws Exception {
        list().performItemClick(null, selected, selected);
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        settle();
        choose(label);
        settle();
    }

    private byte[] finishExport() throws Exception {
        Uri uri = Uri.parse("content://org.diemobile.test/carving-export");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(uri, output);
        activity.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(uri));
        settle();
        assertTrue(status(), status().contains("Export saved"));
        return output.toByteArray();
    }

    @Test
    public void boundedRangesRetainWholeSourceOffsetsAndUnknownChecksStayVisible()
            throws Exception {
        assertTrue(status(), list().getAdapter().getCount() >= 5);
        search("jpg");
        assertEquals(1, list().getAdapter().getCount());
        assertEquals(fixture.jpegAt, row(0).physicalOffset.longValue());
        assertEquals(fixture.jpeg.length, row(0).sizeBytes.longValue());
        assertFalse(row(0).json().getBoolean("fileCheckExecuted"));
        assertTrue(
                ReflectionHelpers.<TextView>getField(activity, "context")
                        .getText()
                        .toString()
                        .contains("581 / 774"));
        root("Rejected / unresolved");
        assertTrue(list().getAdapter().getCount() > 0);
        for (int i = 0; i < list().getAdapter().getCount(); i++) assertNull(row(i).sizeBytes);
        search("header-rejected");
        assertTrue(list().getAdapter().getCount() > 0);
        assertTrue(row(0).json().has("rejectedRecoveryStateIndex"));
        action(0, "Export complete selected record JSON");
        JSONObject selected =
                new JSONObject(new String(finishExport(), StandardCharsets.UTF_8))
                        .getJSONArray("rows")
                        .getJSONObject(0);
        assertTrue(selected.get("headerCalculatedSizeBytes") instanceof String);
        assertEquals(14, selected.getJSONArray("rejectedRecoveryState").length());
        assertFalse(selected.getBoolean("dataCheckExecuted"));
        root("All signature candidates");
        search("dcm");
        assertEquals(1, list().getAdapter().getCount());
        assertEquals(fixture.dcmAt, row(0).physicalOffset.longValue());
        assertNull(row(0).sizeBytes);
        assertTrue(row(0).json().getBoolean("headerAccepted"));
    }

    @Test
    public void exactCarveSurvivesPickerDelaySourceExpiryAndRecreation() throws Exception {
        search("jpg");
        action(0, "Export exact stored range");
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        assertTrue(input.delete());
        controller.recreate();
        activity = controller.get();
        settle();
        assertArrayEquals(fixture.jpeg, finishExport());
    }

    @Test
    public void fullCatalogPagesAndExportsAllPatternsWithExplicitExclusions() throws Exception {
        root("Complete signature / magic catalog");
        assertEquals(774, ReflectionHelpers.<EvidenceRows>getField(activity, "source").size());
        assertEquals(50, list().getAdapter().getCount());
        button("next").performClick();
        settle();
        assertEquals(50, row(0).index);
        controller.recreate();
        activity = controller.get();
        settle();
        assertEquals(50, row(0).index);
        search("cannot match this pattern");
        assertEquals(0, list().getAdapter().getCount());
        button("export").performClick();
        settle();
        JSONObject exported = new JSONObject(new String(finishExport(), StandardCharsets.UTF_8));
        assertEquals(774, exported.getJSONArray("rows").length());
        int excluded = 0;
        for (int i = 0; i < 774; i++)
            if (!exported.getJSONArray("rows").getJSONObject(i).getBoolean("searched")) excluded++;
        assertEquals(193, excluded);
    }

    @Test
    public void completeCompactCaptureSurvivesSourceExpiryAndPickerRecreation() throws Exception {
        root("complete compact capture JSON");
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        settle();
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        assertTrue(input.delete());
        controller.recreate();
        activity = controller.get();
        settle();
        JSONObject capture = new JSONObject(new String(finishExport(), StandardCharsets.UTF_8));
        assertEquals("org.diemobile.photorec-capture", capture.getString("schema"));
        String expected =
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(fixture.bytes));
        assertEquals(
                expected,
                capture.getJSONObject("provenance").getJSONObject("input").getString("sha256"));
        JSONObject engine =
                capture.getJSONObject("engineReportMetadata")
                        .put("rows", capture.getJSONArray("rows"))
                        .put("patterns", capture.getJSONArray("patterns"))
                        .put(
                                "rejectedRecoveryStates",
                                capture.getJSONArray("rejectedRecoveryStates"));
        assertEquals(2, engine.getInt("schemaVersion"));
        assertEquals(774, engine.getJSONArray("patterns").length());
        PhotoRecReport decoded = new PhotoRecReport(engine);
        boolean nested = false;
        for (int i = 0; i < engine.getInt("candidateCount"); i++) {
            JSONObject row = decoded.row(i);
            if (row.getString("name").equals("png")
                    && row.getLong("physicalOffset") == fixture.nestedPngAt) {
                assertEquals(fixture.png.length, row.getLong("reportedSizeBytes"));
                nested = true;
            }
        }
        assertTrue("Nested evidence missing from complete compact capture", nested);
    }
}
