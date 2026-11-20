// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.widget.LinearLayout;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Widget/descriptor/identity contracts; actual codec execution is a separate Android test. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28, shadows = MediaFrameWorkbenchTest.Retriever.class)
public final class MediaFrameWorkbenchTest {
    @Implements(MediaMetadataRetriever.class)
    public static final class Retriever {
        static int opened, released, frames;
        static long offset, length;
        static boolean frameFailure;
        static boolean oversized;

        @Implementation
        protected void setDataSource(FileDescriptor fd, long start, long size) {
            assertTrue(fd.valid());
            opened++;
            offset = start;
            length = size;
        }

        @Implementation
        protected String extractMetadata(int key) {
            switch (key) {
                case MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO:
                    return "yes";
                case MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH:
                    return "640";
                case MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT:
                    return "360";
                case MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION:
                    return "90";
                case MediaMetadataRetriever.METADATA_KEY_MIMETYPE:
                    return "video/mp4";
                case MediaMetadataRetriever.METADATA_KEY_DURATION:
                    return "1001";
                case MediaMetadataRetriever.METADATA_KEY_BITRATE:
                    return "18446744073709551615";
                default:
                    return null;
            }
        }

        @Implementation(minSdk = 27)
        protected Bitmap getScaledFrameAtTime(long time, int option, int width, int height) {
            assertEquals(0, time);
            assertEquals(MediaMetadataRetriever.OPTION_CLOSEST_SYNC, option);
            assertEquals(64, width);
            assertEquals(64, height);
            frames++;
            if (frameFailure) throw new IllegalStateException("Fixture codec refusal");
            return Bitmap.createBitmap(oversized ? 65 : 64, 36, Bitmap.Config.ARGB_8888);
        }

        @Implementation
        protected Bitmap getFrameAtTime(long time, int option) {
            throw new AssertionError("Unbounded frame decoding must never be used");
        }

        @Implementation
        protected void release() {
            released++;
        }
    }

    private Path directory;
    private InputSnapshot input;

    @Before
    public void setup() throws Exception {
        Retriever.opened = Retriever.released = Retriever.frames = 0;
        Retriever.frameFailure = false;
        Retriever.oversized = false;
        directory = Files.createTempDirectory("die-media-frame");
        Path source = directory.resolve("blob.bin");
        byte[] bytes = new byte[1024];
        new Random(725).nextBytes(bytes);
        Files.write(source, bytes);
        input = InputSnapshot.acquire(source.toFile(), directory.toFile(), 1024, () -> false);
    }

    @After
    public void cleanup() throws Exception {
        if (input != null) input.close();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    @Test
    public void exactDescriptorIntervalRetainsRawUnknownValuesAndBoundedFrame() throws Exception {
        MediaFrame.Result result =
                MediaFrame.read(input, new FileRange(input.size, 37, 500), 64, () -> false);
        try {
            assertEquals(37, Retriever.offset);
            assertEquals(500, Retriever.length);
            assertEquals(1, Retriever.opened);
            assertEquals(1, Retriever.released);
            assertEquals(64, result.bitmap.getWidth());
            assertEquals(36, result.bitmap.getHeight());
            JSONObject report = result.report();
            assertEquals(input.sha256, report.getString("sourceSha256"));
            assertEquals(
                    "18446744073709551615", report.getJSONObject("metadata").getString("bitrate"));
            assertTrue(report.getJSONObject("metadata").isNull("hasAudio"));
            assertTrue(report.isNull("actualTimestampUs"));
            assertTrue(result.description().contains("640 × 360"));
            assertTrue(result.description().contains("1001 ms"));
            report.getJSONObject("metadata").put("durationMs", "changed");
            assertEquals("1001", result.report().getJSONObject("metadata").getString("durationMs"));
            input.checkIdentity(() -> false);
        } finally {
            result.bitmap.recycle();
        }
    }

    @Test
    public void failedCodecRetainsMetadataAndReleasesDescriptorEngine() throws Exception {
        Retriever.frameFailure = true;
        MediaFrame.Result result =
                MediaFrame.read(input, new FileRange(input.size, 37, 500), 64, () -> false);
        assertNull(result.bitmap);
        assertEquals("1001", result.metadata.get("durationMs"));
        assertTrue(result.report().getString("frameError").contains("Fixture codec refusal"));
        assertTrue(result.description().contains("metadata remains available"));
        assertEquals(1, Retriever.released);
        input.checkIdentity(() -> false);
        Retriever.frameFailure = false;
        Retriever.oversized = true;
        MediaFrame.Result oversized =
                MediaFrame.read(input, new FileRange(input.size, 37, 500), 64, () -> false);
        assertNull(oversized.bitmap);
        assertEquals("1001", oversized.metadata.get("durationMs"));
        assertTrue(oversized.report().getString("frameError").contains("pixel budget"));
        assertEquals(2, Retriever.released);
    }

    @Test
    @Config(sdk = 26, shadows = Retriever.class)
    public void api26RetainsMetadataWithoutUnboundedFrameFallback() throws Exception {
        MediaFrame.Result result =
                MediaFrame.read(input, new FileRange(input.size, 37, 500), 64, () -> false);
        assertNull(result.bitmap);
        assertEquals(0, Retriever.frames);
        assertEquals(1, Retriever.released);
        assertTrue(result.frameStatus.contains("API 27+"));
        assertEquals("640", result.metadata.get("videoWidth"));
    }

    @Test
    public void cancelledChangedEmptyAndForeignScopesNeverReachDecoder() throws Exception {
        refusal(() -> MediaFrame.read(input, new FileRange(input.size, 0, 10), 64, () -> true));
        refusal(() -> MediaFrame.read(input, new FileRange(input.size, 0, 0), 64, () -> false));
        refusal(
                () ->
                        MediaFrame.read(
                                input, new FileRange(input.size + 1, 0, 10), 64, () -> false));
        refusal(() -> MediaFrame.read(input, new FileRange(input.size, 0, 10), 513, () -> false));
        try (RandomAccessFile changed = new RandomAccessFile(input.file, "rw")) {
            changed.write(99);
        }
        refusal(() -> MediaFrame.read(input, new FileRange(input.size, 0, 10), 64, () -> false));
        assertEquals(0, Retriever.opened);
    }

    @Test
    public void unknownVideoExtentNeverSchedulesAThumbnailOrInventsAFrame() {
        AtomicInteger requests = new AtomicInteger();
        ContentMap.Card card =
                new ContentMap.Card(
                        ContentMap.Kind.VIDEO, "Observed video", "mp4", 37, null, "", 5);
        ContentGalleryAdapter adapter =
                new ContentGalleryAdapter(
                        RuntimeEnvironment.getApplication(),
                        Collections.singletonList(card),
                        input,
                        command -> requests.incrementAndGet());
        try {
            adapter.getView(0, null, new LinearLayout(RuntimeEnvironment.getApplication()));
            assertEquals(0, requests.get());
            assertNull(card.length);
        } finally {
            adapter.close();
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void refusal(Action action) throws Exception {
        try {
            action.run();
            fail("Invalid preview reached the decoder");
        } catch (IOException expected) {
            /* Refusal must precede the native platform call. */
        }
    }
}
