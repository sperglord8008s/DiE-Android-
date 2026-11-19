// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.widget.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Actual Binwalk/PhotoRec, acquisitions and widgets; OS transport/providers are substituted. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28, shadows = RecoveredContentWorkbenchTest.HostTransport.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RecoveredContentWorkbenchTest {
    static final AtomicInteger scans = new AtomicInteger();

    @Implements(value = BinaryWorkerClient.class, isInAndroidSdk = false)
    public static final class HostTransport {
        @RealObject BinaryWorkerClient client;

        @Implementation
        public String scan(File input, BooleanSupplier stop) throws Exception {
            EvidenceRows.check(stop);
            scans.incrementAndGet();
            try (RandomAccessFile source = new RandomAccessFile(input, "r")) {
                Field field = FileDescriptor.class.getDeclaredField("fd");
                field.setAccessible(true);
                int descriptor = field.getInt(source.getFD());
                String engine = ReflectionHelpers.getField(client, "engine");
                String value =
                        engine.equals("photorec")
                                ? PhotoRecEngine.scanFd(descriptor)
                                : BinwalkEngine.scanFd(descriptor);
                EvidenceRows.check(stop);
                return value;
            }
        }
    }

    private Path temporary;
    private CarvingFixture fixture;
    private File original;
    private ActivityController<InvestigationActivity> toolController;
    private InvestigationActivity tool;
    private ActivityController<ContentPreviewActivity> previewController;
    private ContentPreviewActivity preview;
    private ActivityController<GallerizeActivity> galleryController;
    private GallerizeActivity gallery;

    @Before
    public void setup() throws Exception {
        assertTrue(BinwalkEngine.ERROR, BinwalkEngine.AVAILABLE);
        assertTrue(PhotoRecEngine.ERROR, PhotoRecEngine.AVAILABLE);
        scans.set(0);
        temporary = Files.createTempDirectory("glassdigestor-recovered-ui");
        fixture = new CarvingFixture();
        original = temporary.resolve("mixed.not-media").toFile();
        Files.write(original.toPath(), fixture.bytes);
    }

    @After
    public void cleanup() throws Exception {
        close(galleryController);
        close(previewController);
        close(toolController);
        if (temporary != null)
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
    }

    private void close(ActivityController<? extends Activity> controller) throws Exception {
        if (controller == null) return;
        ExecutorService worker = ReflectionHelpers.getField(controller.get(), "worker");
        controller.pause().stop().destroy();
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void settle(Activity activity) throws Exception {
        for (int i = 0; i < 8; i++) {
            ReflectionHelpers.<ExecutorService>getField(activity, "worker")
                    .submit(() -> {})
                    .get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private void open(String provider) throws Exception {
        Intent intent =
                new Intent(RuntimeEnvironment.getApplication(), InvestigationActivity.class)
                        .putExtra("provider", provider)
                        .putExtra("path", original.getAbsolutePath())
                        .putExtra("name", original.getName());
        toolController =
                Robolectric.buildActivity(InvestigationActivity.class, intent).setup().visible();
        tool = toolController.get();
        settle(tool);
        assertNotNull(status(tool), list().getAdapter());
    }

    private String status(Activity activity) {
        return ReflectionHelpers.<TextView>getField(activity, "status").getText().toString();
    }

    private ListView list() {
        return tool.findViewById(R.id.investigation_records);
    }

    private List<String> choices() {
        ListView list = ShadowAlertDialog.getLatestAlertDialog().getListView();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < list.getAdapter().getCount(); i++)
            labels.add(list.getAdapter().getItem(i).toString());
        return labels;
    }

    private void choose(String part) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView list = dialog.getListView();
        for (int i = 0; i < list.getAdapter().getCount(); i++)
            if (list.getAdapter().getItem(i).toString().contains(part)) {
                list.performItemClick(null, i, i);
                dialog.dismiss();
                return;
            }
        fail("Missing action " + part + ": " + choices());
    }

    private void openRoot(String name) throws Exception {
        ReflectionHelpers.<Button>getField(tool, "views").performClick();
        choose(name);
        settle(tool);
    }

    private int pngRow() throws Exception {
        ((EditText) tool.findViewById(R.id.investigation_filter)).setText("png");
        ReflectionHelpers.<Button>getField(tool, "search").performClick();
        settle(tool);
        for (int i = 0; i < list().getAdapter().getCount(); i++) {
            EvidenceRows.Row row = (EvidenceRows.Row) list().getAdapter().getItem(i);
            if (row.physicalOffset != null
                    && row.physicalOffset == fixture.pngAt
                    && row.sizeBytes != null) {
                assertEquals(fixture.png.length, row.sizeBytes.longValue());
                return i;
            }
        }
        fail("No bounded original PNG: " + status(tool));
        return -1;
    }

    private void actions(int index) throws Exception {
        list().performItemClick(null, index, index);
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        settle(tool);
    }

    private PreparedExport preparePreviewExport() throws Exception {
        ReflectionHelpers.<Button>getField(preview, "exportBytes").performClick();
        settle(preview);
        PreparedExport staged = ReflectionHelpers.getField(preview, "contentExport");
        assertNotNull(staged);
        var picker = Shadows.shadowOf(preview).getNextStartedActivityForResult();
        assertNotNull(picker);
        assertEquals(1, picker.requestCode);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent.getAction());
        assertTrue(picker.intent.getStringExtra(Intent.EXTRA_TITLE).endsWith(".png"));
        assertArrayEquals(fixture.png, Files.readAllBytes(staged.file.toPath()));
        return staged;
    }

    private String exportNotice() {
        return ReflectionHelpers.<TextView>getField(preview, "exportNotice").getText().toString();
    }

    @Test
    public void photoRecRecoveredPreviewExportRetainsIdentityAcrossParentAndPickerExpiry()
            throws Exception {
        recoveredPreview("photorec", false);
    }

    @Test
    public void binwalkRecoveredPreviewExportRetainsIdentityAndCleansFailedDestination()
            throws Exception {
        recoveredPreview("binwalk", true);
    }

    private void recoveredPreview(String provider, boolean failure) throws Exception {
        open(provider);
        String parentSha = Inspector.scan(original, 4).sha256;
        int index = pngRow();
        actions(index);
        choose("View exact recovered bytes");
        settle(tool);
        Intent intent = Shadows.shadowOf(tool).getNextStartedActivity();
        assertNotNull(status(tool), intent);
        assertEquals(ContentPreviewActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(parentSha, intent.getStringExtra("originalSha256"));
        assertEquals(fixture.pngAt, intent.getLongExtra("originalOffset", -1));
        File child = new File(intent.getStringExtra("path"));
        assertArrayEquals(fixture.png, Files.readAllBytes(child.toPath()));
        close(toolController);
        toolController = null;
        assertTrue(original.delete());
        assertTrue(child.isFile());
        previewController =
                Robolectric.buildActivity(ContentPreviewActivity.class, intent).setup().visible();
        preview = previewController.get();
        settle(preview);
        assertEquals(
                Inspector.toHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(fixture.png)),
                ReflectionHelpers.<InputSnapshot>getField(preview, "input").sha256);
        PreparedExport cancelled = preparePreviewExport();
        preview.onActivityResult(1, Activity.RESULT_CANCELED, null);
        settle(preview);
        assertFalse(cancelled.file.exists());
        assertTrue(exportNotice(), exportNotice().contains("cancelled"));
        if (failure) {
            PreparedExport broken = preparePreviewExport();
            Uri destination = Uri.parse("content://org.diemobile.test/recovered-failure");
            Shadows.shadowOf(preview.getContentResolver())
                    .registerOutputStream(
                            destination,
                            new OutputStream() {
                                @Override
                                public void write(int value) throws IOException {
                                    throw new IOException("fixture destination failed");
                                }
                            });
            preview.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(destination));
            settle(preview);
            assertFalse(broken.file.exists());
            assertTrue(exportNotice(), exportNotice().contains("failed"));
            assertArrayEquals(fixture.png, Files.readAllBytes(child.toPath()));
        }
        PreparedExport staged = preparePreviewExport();
        assertTrue(child.delete());
        previewController.recreate();
        preview = previewController.get();
        settle(preview);
        assertNotNull(ReflectionHelpers.getField(preview, "contentExport"));
        Uri destination = Uri.parse("content://org.diemobile.test/recovered-success");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shadows.shadowOf(preview.getContentResolver()).registerOutputStream(destination, output);
        preview.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(destination));
        settle(preview);
        assertArrayEquals(fixture.png, output.toByteArray());
        assertFalse(staged.file.exists());
        assertTrue(exportNotice(), exportNotice().contains("saved"));
    }

    @Test
    public void galleryConsumesOnlyPrivateHandoffAndRestoresWithoutParentOrRescan()
            throws Exception {
        open("photorec");
        String expected = Inspector.scan(original, 4).sha256;
        openRoot("Gallerize this captured input");
        Intent intent = Shadows.shadowOf(tool).getNextStartedActivity();
        assertNotNull(intent);
        assertTrue(intent.getBooleanExtra("ownedInputHandoff", false));
        File handoff = new File(intent.getStringExtra("path"));
        assertArrayEquals(fixture.bytes, Files.readAllBytes(handoff.toPath()));
        close(toolController);
        toolController = null;
        assertTrue(original.delete());
        galleryController =
                Robolectric.buildActivity(GallerizeActivity.class, intent).setup().visible();
        gallery = galleryController.get();
        settle(gallery);
        assertFalse(handoff.exists());
        GallerizeSession session = ReflectionHelpers.getField(gallery, "session");
        assertNotNull(status(gallery), session);
        assertEquals(expected, session.input.sha256);
        int before = scans.get();
        galleryController.recreate();
        gallery = galleryController.get();
        settle(gallery);
        assertEquals(before, scans.get());
        assertEquals(
                expected,
                ReflectionHelpers.<GallerizeSession>getField(gallery, "session").input.sha256);
        close(galleryController);
        galleryController = null;
        File foreign = temporary.resolve("analysis-input-foreign.snapshot").toFile();
        Files.write(foreign.toPath(), fixture.png);
        Intent forged =
                new Intent(RuntimeEnvironment.getApplication(), GallerizeActivity.class)
                        .putExtra("path", foreign.getAbsolutePath())
                        .putExtra("name", "forged private handoff")
                        .putExtra("sha256", Inspector.scan(foreign, 4).sha256)
                        .putExtra("sourceSize", fixture.png.length)
                        .putExtra("ownedInputHandoff", true);
        galleryController =
                Robolectric.buildActivity(GallerizeActivity.class, forged).setup().visible();
        gallery = galleryController.get();
        settle(gallery);
        assertTrue(status(gallery), status(gallery).contains("Invalid private input"));
        assertArrayEquals(fixture.png, Files.readAllBytes(foreign.toPath()));
    }

    @Test
    public void unknownExtentsStayNonExtractableAndChangedParentCannotBePreviewedOrExported()
            throws Exception {
        open("photorec");
        openRoot("Rejected / unresolved");
        assertTrue(list().getAdapter().getCount() > 0);
        EvidenceRows.Row unknown = (EvidenceRows.Row) list().getAdapter().getItem(0);
        assertNull(unknown.sizeBytes);
        actions(0);
        assertFalse(
                choices().stream()
                        .anyMatch(
                                name ->
                                        name.contains("View exact recovered")
                                                || name.contains("Export exact stored")));
        ShadowAlertDialog.getLatestAlertDialog().dismiss();
        openRoot("Bounded stored-byte");
        int index = pngRow();
        CarvingBackend backend = ReflectionHelpers.getField(tool, "backend");
        InputSnapshot source = backend.ownedByteSource();
        try (RandomAccessFile mutate = new RandomAccessFile(source.file, "rw")) {
            int value = mutate.read();
            mutate.seek(0);
            mutate.write(value ^ 1);
        }
        actions(index);
        choose("View exact recovered bytes");
        settle(tool);
        assertTrue(status(tool), status(tool).contains("SHA-256 changed"));
        assertNull(Shadows.shadowOf(tool).getNextStartedActivity());
        actions(index);
        choose("Export exact stored range");
        settle(tool);
        assertTrue(status(tool), status(tool).contains("SHA-256 changed"));
        assertNull(ReflectionHelpers.getField(tool, "prepared"));
        assertArrayEquals(fixture.bytes, Files.readAllBytes(original.toPath()));
    }
}
