// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Looper;
import android.view.*;
import android.widget.*;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import org.robolectric.util.ReflectionHelpers;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Actual PhotoRec, content/byte models and widgets; OS transport/media runtime/providers are
 * substituted.
 */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28, shadows = GallerizeWorkbenchTest.HostPhotoRec.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class GallerizeWorkbenchTest {
    static final AtomicInteger nativeCalls = new AtomicInteger();

    @Implements(value = BinaryWorkerClient.class, isInAndroidSdk = false)
    public static final class HostPhotoRec {
        @Implementation
        public String scan(File input, BooleanSupplier stop) throws Exception {
            nativeCalls.incrementAndGet();
            EvidenceRows.check(stop);
            try (RandomAccessFile source = new RandomAccessFile(input, "r")) {
                Field field = FileDescriptor.class.getDeclaredField("fd");
                field.setAccessible(true);
                return PhotoRecEngine.scanFd(field.getInt(source.getFD()));
            }
        }
    }

    private ActivityController<GallerizeActivity> controller;
    private GallerizeActivity activity;
    private ActivityController<ContentPreviewActivity> previewController;
    private ContentPreviewActivity preview;
    private Path temporary;
    private File original;
    private byte[] blob;
    private String text;

    @Before
    public void setup() throws Exception {
        assertTrue(PhotoRecEngine.ERROR, PhotoRecEngine.AVAILABLE);
        nativeCalls.set(0);
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("gallerize-grid", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("workbench-presentation", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        temporary = Files.createTempDirectory("die-gallery-ui");
        original = temporary.resolve("mixed.not-media").toFile();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(MetadataFixtureFactory.png());
        output.write(MetadataFixtureFactory.jpeg());
        output.write(MetadataFixtureFactory.wav());
        output.write(
                Base64.getDecoder()
                        .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"));
        text = "Unicode content 世界 🔬 with https://example.invalid/path for the gallery.\n";
        output.write(text.getBytes(StandardCharsets.UTF_8));
        blob = output.toByteArray();
        Files.write(original.toPath(), blob);
        Intent intent =
                new Intent(RuntimeEnvironment.getApplication(), GallerizeActivity.class)
                        .putExtra("path", original.getAbsolutePath())
                        .putExtra("name", original.getName())
                        .putExtra("sha256", Inspector.scan(original, 4).sha256)
                        .putExtra("sourceSize", blob.length);
        controller = Robolectric.buildActivity(GallerizeActivity.class, intent).setup().visible();
        activity = controller.get();
        settle(activity);
    }

    private void settle(Activity target) throws Exception {
        for (int i = 0; i < 12; i++) {
            ReflectionHelpers.<ExecutorService>getField(target, "worker")
                    .submit(() -> {})
                    .get(45, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!(target instanceof GallerizeActivity)
                    || !ReflectionHelpers.<Boolean>getField(target, "busy")) {
                // Thumbnail/render callbacks can enqueue a second wave after initial acquisition.
                ReflectionHelpers.<ExecutorService>getField(target, "worker")
                        .submit(() -> {})
                        .get(45, TimeUnit.SECONDS);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                return;
            }
        }
        fail("Viewer did not settle: " + status(target));
    }

    private String status(Activity target) {
        return ReflectionHelpers.<TextView>getField(target, "status").getText().toString();
    }

    private View tagged(Activity target, String value) {
        return target.getWindow().getDecorView().findViewWithTag(value);
    }

    private void invoke(String method) {
        ReflectionHelpers.callInstanceMethod(activity, method);
    }

    private void closeGallery() throws Exception {
        if (controller != null) {
            ExecutorService worker = ReflectionHelpers.getField(activity, "worker");
            controller.pause().stop().destroy();
            assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
            controller = null;
        }
    }

    @After
    public void cleanup() throws Exception {
        if (previewController != null) {
            ExecutorService worker = ReflectionHelpers.getField(preview, "worker");
            previewController.pause().stop().destroy();
            assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        }
        closeGallery();
        if (temporary != null)
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
    }

    @Test
    public void concatenatedMediaAndTextBecomeLabeledCardsAndActualImageThumbnails()
            throws Exception {
        ContentMap map = ReflectionHelpers.getField(activity, "map");
        assertNotNull(status(activity), map);
        assertTrue(
                map.composition, map.composition.startsWith("Multiple separate stored artifacts"));
        assertTrue(
                map.cards.stream()
                        .anyMatch(c -> c.kind == ContentMap.Kind.AUDIO && c.length != null));
        assertTrue(
                map.cards.stream()
                        .anyMatch(c -> c.kind == ContentMap.Kind.TEXT && c.preview.equals(text)));
        GridView gallery = (GridView) tagged(activity, "gallerize-gallery");
        assertNotNull(gallery);
        int image = -1;
        for (int i = 0; i < gallery.getAdapter().getCount(); i++)
            if (((ContentMap.Card) gallery.getAdapter().getItem(i)).kind == ContentMap.Kind.IMAGE) {
                image = i;
                break;
            }
        assertTrue(image >= 0);
        LinearLayout tile = (LinearLayout) gallery.getAdapter().getView(image, null, gallery);
        settle(activity);
        tile = (LinearLayout) gallery.getAdapter().getView(image, null, gallery);
        ImageView thumbnail = null;
        for (int i = 0; i < tile.getChildCount(); i++)
            if (tile.getChildAt(i) instanceof ImageView) thumbnail = (ImageView) tile.getChildAt(i);
        assertNotNull(thumbnail);
        assertNotNull("A label is not an image preview", thumbnail.getDrawable());
        assertTrue(((BitmapDrawable) thumbnail.getDrawable()).getBitmap().getWidth() > 0);
        assertTrue(status(activity).contains("581 / 774"));
        assertEquals(1, nativeCalls.get());
    }

    @Test
    public void manualGridDimensionsExactCellHexAndStateRemainBoundToOriginalSource()
            throws Exception {
        ReflectionHelpers.setField(activity, "mode", "bytes");
        ReflectionHelpers.<EditText>getField(activity, "width").setText("7");
        ReflectionHelpers.<EditText>getField(activity, "height").setText("5");
        ReflectionHelpers.callInstanceMethod(
                activity, "showGrid", ReflectionHelpers.ClassParameter.from(boolean.class, false));
        settle(activity);
        ByteGrid.Result grid = ReflectionHelpers.getField(activity, "grid");
        assertEquals(7, grid.layout.width);
        assertEquals(5, grid.layout.height);
        ByteGridView view = (ByteGridView) tagged(activity, "gallerize-grid");
        assertNotNull(view);
        view.layout(0, 0, 320, 240);
        android.graphics.Bitmap raster =
                android.graphics.Bitmap.createBitmap(
                        320, 240, android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(raster));
        // Cell 13 is x=6,y=1. The 7:5 grid is centered with its aspect ratio preserved.
        float scale = 320f / 7;
        android.view.MotionEvent touch =
                android.view.MotionEvent.obtain(
                        0,
                        0,
                        android.view.MotionEvent.ACTION_UP,
                        6.5f * scale,
                        (240 - 5 * scale) / 2 + 1.5f * scale,
                        0);
        view.onTouchEvent(touch);
        touch.recycle();
        raster.recycle();
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Intent hex = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(NativeToolsActivity.class.getName(), hex.getComponent().getClassName());
        assertEquals(grid.layout.range(13).offset, hex.getLongExtra("offset", -1));
        assertEquals(grid.sha256, hex.getStringExtra("sha256"));
        String sha = grid.sha256;
        controller.recreate();
        activity = controller.get();
        settle(activity);
        grid = ReflectionHelpers.getField(activity, "grid");
        assertEquals(7, grid.layout.width);
        assertEquals(5, grid.layout.height);
        assertEquals(sha, grid.sha256);
        assertEquals("Restoration should reuse the exact native capture", 1, nativeCalls.get());
        invoke("gridSizes");
        ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null, 6, 6);
        settle(activity);
        grid = ReflectionHelpers.getField(activity, "grid");
        assertEquals(640, grid.layout.width);
        assertEquals(640, grid.layout.height);
    }

    @Test
    public void completeMapExportSurvivesFilterSourceExpiryAndPickerRecreation() throws Exception {
        ContentMap map = ReflectionHelpers.getField(activity, "map");
        String sourceSha =
                ReflectionHelpers.<GallerizeSession>getField(activity, "session").input.sha256;
        ReflectionHelpers.<Spinner>getField(activity, "groups").setSelection(3);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        invoke("export");
        settle(activity);
        PreparedExport staged = ReflectionHelpers.getField(activity, "prepared");
        assertNotNull(status(activity), staged);
        byte[] expected = Files.readAllBytes(staged.file.toPath());
        Files.delete(original.toPath());
        controller.recreate();
        activity = controller.get();
        settle(activity);
        assertEquals(1, nativeCalls.get());
        Uri destination = Uri.parse("content://org.diemobile.test/gallerize-export");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(destination, output);
        activity.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(destination));
        settle(activity);
        assertTrue(status(activity), status(activity).contains("Export saved"));
        assertArrayEquals(expected, output.toByteArray());
        JSONObject report = new JSONObject(output.toString(StandardCharsets.UTF_8));
        assertEquals(map.cards.size(), report.getJSONArray("cards").length());
        assertEquals(
                map.rawCount, report.getJSONObject("nativeEvidence").getJSONArray("rows").length());
        assertEquals(774, report.getJSONObject("nativeCatalog").getJSONArray("rows").length());
        assertEquals(sourceSha, report.getJSONObject("source").getString("sha256"));
        assertFalse(staged.file.exists());
    }

    @Test
    public void derivedUnicodePreviewOwnsExactBytesAfterGalleryClosesAndRecreates()
            throws Exception {
        ContentMap map = ReflectionHelpers.getField(activity, "map");
        ContentMap.Card card =
                map.cards.stream()
                        .filter(c -> c.kind == ContentMap.Kind.TEXT && c.preview.equals(text))
                        .findFirst()
                        .orElseThrow();
        ReflectionHelpers.callInstanceMethod(
                activity,
                "preview",
                ReflectionHelpers.ClassParameter.from(ContentMap.Card.class, card));
        settle(activity);
        Intent target = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(ContentPreviewActivity.class.getName(), target.getComponent().getClassName());
        File bytes = new File(target.getStringExtra("path"));
        assertArrayEquals(
                text.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(bytes.toPath()));
        String parentSha = target.getStringExtra("originalSha256");
        closeGallery();
        Files.delete(original.toPath());
        previewController =
                Robolectric.buildActivity(ContentPreviewActivity.class, target).setup().visible();
        preview = previewController.get();
        settle(preview);
        TextView shown = (TextView) tagged(preview, "content-text");
        assertNotNull(status(preview), shown);
        assertEquals(text, shown.getText().toString());
        assertTrue(
                ReflectionHelpers.<TextView>getField(preview, "identity")
                        .getText()
                        .toString()
                        .contains(parentSha));
        previewController.recreate();
        preview = previewController.get();
        settle(preview);
        assertEquals(text, ((TextView) tagged(preview, "content-text")).getText().toString());
        assertTrue(bytes.isFile());
        ExecutorService worker = ReflectionHelpers.getField(preview, "worker");
        previewController.pause().stop().destroy();
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        previewController = null;
        assertFalse(bytes.exists());
    }

    @Test
    public void fullImagePreviewAndManualDecoderFailureRetainExactSourceAndRecover()
            throws Exception {
        ContentMap.Card image =
                ReflectionHelpers.<ContentMap>getField(activity, "map").cards.stream()
                        .filter(
                                card ->
                                        card.kind == ContentMap.Kind.IMAGE
                                                && card.length != null
                                                && card.format.equals("png"))
                        .findFirst()
                        .orElseThrow();
        ReflectionHelpers.callInstanceMethod(
                activity,
                "preview",
                ReflectionHelpers.ClassParameter.from(ContentMap.Card.class, image));
        settle(activity);
        Intent target = Shadows.shadowOf(activity).getNextStartedActivity();
        previewController =
                Robolectric.buildActivity(ContentPreviewActivity.class, target).setup().visible();
        preview = previewController.get();
        settle(preview);
        ImageView rendered = (ImageView) tagged(preview, "content-image");
        assertNotNull(status(preview), rendered);
        assertNotNull(rendered.getDrawable());
        assertTrue(status(preview), status(preview).contains("decoded first frame"));
        InputSnapshot owned = ReflectionHelpers.getField(preview, "input");
        String expected = owned.sha256;
        ReflectionHelpers.setField(preview, "mode", "UTF-8 text");
        ReflectionHelpers.callInstanceMethod(preview, "render");
        settle(preview);
        assertTrue(status(preview), status(preview).contains("Preview unavailable"));
        assertTrue(owned.file.isFile());
        ReflectionHelpers.setField(preview, "mode", "Image");
        ReflectionHelpers.callInstanceMethod(preview, "render");
        settle(preview);
        assertNotNull(tagged(preview, "content-image"));
        assertEquals(expected, ReflectionHelpers.<InputSnapshot>getField(preview, "input").sha256);
    }

    @Test
    public void mediaIsLocalExplicitlyStartedPausedAndReportsDecoderFailure() throws Exception {
        ContentMap.Card audio =
                ReflectionHelpers.<ContentMap>getField(activity, "map").cards.stream()
                        .filter(c -> c.kind == ContentMap.Kind.AUDIO && c.length != null)
                        .findFirst()
                        .orElseThrow();
        ReflectionHelpers.callInstanceMethod(
                activity,
                "preview",
                ReflectionHelpers.ClassParameter.from(ContentMap.Card.class, audio));
        settle(activity);
        Intent target = Shadows.shadowOf(activity).getNextStartedActivity();
        ShadowMediaPlayer.setMediaInfoProvider(source -> new ShadowMediaPlayer.MediaInfo(10000, 0));
        previewController =
                Robolectric.buildActivity(ContentPreviewActivity.class, target).setup().visible();
        preview = previewController.get();
        settle(preview);
        MediaPlayer player = ReflectionHelpers.getField(preview, "player");
        assertNotNull(status(preview), player);
        ShadowMediaPlayer shadow = Shadows.shadowOf(player);
        assertNull(shadow.getSourceUri());
        assertNotNull(shadow.getDataSource());
        assertFalse(player.isPlaying());
        tagged(preview, "content-play").performClick();
        assertTrue(player.isPlaying());
        previewController.pause();
        assertFalse(player.isPlaying());
        previewController.resume();
        shadow.invokeErrorListener(1, -1004);
        assertTrue(status(preview), status(preview).contains("decoder failed"));
        assertNull(ReflectionHelpers.getField(preview, "player"));
    }

    @Test
    public void cancelledGridAndFailedDestinationCleanStagesAndAllowRecovery() throws Exception {
        ExecutorService worker = ReflectionHelpers.getField(activity, "worker");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        worker.execute(
                () -> {
                    entered.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        ReflectionHelpers.setField(activity, "mode", "entropy");
        ReflectionHelpers.callInstanceMethod(
                activity, "showGrid", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        ReflectionHelpers.<Button>getField(activity, "cancel").performClick();
        release.countDown();
        settle(activity);
        assertNull(ReflectionHelpers.getField(activity, "grid"));
        assertTrue(status(activity), status(activity).contains("cancelled"));
        ReflectionHelpers.callInstanceMethod(
                activity, "showGrid", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        settle(activity);
        assertNotNull(ReflectionHelpers.getField(activity, "grid"));
        invoke("export");
        ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null, 0, 0);
        settle(activity);
        PreparedExport staged = ReflectionHelpers.getField(activity, "prepared");
        assertNotNull(staged);
        JSONObject gridReport =
                new JSONObject(
                        new String(
                                Files.readAllBytes(staged.file.toPath()), StandardCharsets.UTF_8));
        ByteGrid.Result grid = ReflectionHelpers.getField(activity, "grid");
        assertEquals(grid.sha256, gridReport.getString("sha256"));
        assertEquals(grid.values.length, gridReport.getJSONArray("cells").length());
        assertFalse(gridReport.getBoolean("sampling"));
        Uri destination = Uri.parse("content://org.diemobile.test/failed-grid-output");
        Shadows.shadowOf(activity.getContentResolver())
                .registerOutputStream(
                        destination,
                        new OutputStream() {
                            public void write(int b) throws IOException {
                                throw new IOException("forced destination failure");
                            }
                        });
        activity.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(destination));
        settle(activity);
        assertTrue(status(activity), status(activity).contains("forced destination failure"));
        assertFalse(staged.file.exists());
        assertNotNull(ReflectionHelpers.getField(activity, "grid"));
    }
}
