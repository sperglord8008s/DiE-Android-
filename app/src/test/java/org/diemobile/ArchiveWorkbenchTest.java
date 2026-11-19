// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.app.*;
import android.content.Intent;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

/** Real ZIP extraction through Android widgets/lifecycle, without executing members. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ArchiveWorkbenchTest {
    private ActivityController<ArchiveExplorerActivity> controller;
    private ArchiveExplorerActivity activity;
    private Path temporary;
    private File outer;
    private static final byte[] PAYLOAD = "DIE_NESTED_ARCHIVE_PAYLOAD\0\u00e9".getBytes(StandardCharsets.UTF_8);

    @Before public void fixtures() throws Exception {
        temporary = Files.createTempDirectory("die-archive-ui");
        LinkedHashMap<String, byte[]> innerEntries = new LinkedHashMap<>(); innerEntries.put("payload.bin", PAYLOAD);
        byte[] inner = zip(innerEntries);
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(); entries.put("folder/inner.zip", inner); entries.put("folder/invalid.zip", PAYLOAD);
        for (int i = 0; i < 70; i++) entries.put(String.format(Locale.ROOT, "folder/inner-%02d.zip", i), inner);
        for (int i = 0; i < 70; i++) entries.put(String.format(Locale.ROOT, "file-%02d.bin", i), new byte[]{(byte)i});
        outer = temporary.resolve("outer.zip").toFile(); Files.write(outer.toPath(), zip(entries));
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ArchiveExplorerActivity.class).putExtra("path", outer.getAbsolutePath()).putExtra("name", "outer.zip");
        controller = Robolectric.buildActivity(ArchiveExplorerActivity.class, intent).setup().visible(); activity = controller.get(); settle();
    }
    @After public void close() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        if (temporary != null) try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private void settle() throws Exception {
        for (int i = 0; i < 5; i++) {
            ExecutorService worker = ReflectionHelpers.getField(activity, "worker"); worker.submit(() -> {}).get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!ReflectionHelpers.<Boolean>getField(activity, "busy")) return;
        }
        fail("Archive worker did not settle");
    }
    private ListView list() { return ReflectionHelpers.getField(activity, "list"); }
    private EditText filter() { return ReflectionHelpers.getField(activity, "filter"); }
    private String status() { return ReflectionHelpers.<TextView>getField(activity, "status").getText().toString(); }
    private Button button(String field) { return ReflectionHelpers.getField(activity, field); }
    private void select(String label) {
        for (int i = 0; i < list().getAdapter().getCount(); i++) if (list().getAdapter().getItem(i).toString().contains(label)) {
            list().performItemClick(null, i, i); return;
        }
        fail("Archive row unavailable: " + label);
    }
    private void action(int button) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog); dialog.getButton(button).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private void enterNested() throws Exception {
        select("Directory: folder/"); filter().setText("inner-"); button("next").performClick();
        select("inner-69.zip"); action(AlertDialog.BUTTON_NEUTRAL); settle();
        assertTrue(status(), status().contains("level 2/16")); assertEquals(1, list().getAdapter().getCount());
    }
    private File snapshotDirectory() { return new File(activity.getCacheDir(), "archive-snapshots"); }

    @Test public void nestedBackRestoresParentFolderFilterAndPagedRoot() throws Exception {
        assertEquals(50, list().getAdapter().getCount()); button("next").performClick(); assertEquals(21, list().getAdapter().getCount());
        button("previous").performClick(); enterNested(); button("containerBack").performClick(); settle();
        assertEquals("inner-", filter().getText().toString()); assertEquals(20, list().getAdapter().getCount());
        assertTrue(status(), status().contains("page 2/2")); assertTrue(list().getAdapter().getItem(0).toString().contains("inner-50.zip"));
        assertEquals(0, Objects.requireNonNull(snapshotDirectory().list()).length);
        button("parent").performClick(); assertEquals("", filter().getText().toString());
        assertEquals(50, list().getAdapter().getCount()); assertTrue(outer.isFile());
    }

    @Test public void nestedRecreationRetainsBackPathAndTransfersOnlyTheSelectedLeaf() throws Exception {
        enterNested(); controller.recreate(); activity = controller.get(); settle();
        assertTrue(status(), status().contains("level 2/16")); assertTrue(button("containerBack").isEnabled());
        assertEquals(1, Objects.requireNonNull(snapshotDirectory().list()).length);
        button("containerBack").performClick(); settle();
        assertEquals("inner-", filter().getText().toString()); assertTrue(status(), status().contains("page 2/2"));
        select("inner-69.zip"); action(AlertDialog.BUTTON_NEUTRAL); settle();
        select("payload.bin"); action(AlertDialog.BUTTON_POSITIVE); settle();
        assertTrue(activity.isFinishing());
        Intent result = Shadows.shadowOf(activity).getResultIntent(); assertNotNull(result);
        File leaf = new File(result.getStringExtra("path"));
        assertArrayEquals(PAYLOAD, Files.readAllBytes(leaf.toPath()));
        assertEquals("outer.zip!/folder/inner-69.zip!/payload.bin", result.getStringExtra("display"));
        assertEquals(Inspector.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(PAYLOAD)), result.getStringExtra("memberSha256"));
        controller.pause().stop().destroy(); controller = null;
        assertTrue("Leaf ownership must transfer to the caller", leaf.isFile());
        assertEquals(1, Objects.requireNonNull(snapshotDirectory().list()).length); leaf.delete();
        assertTrue("Outer input must remain untouched", outer.isFile());
    }

    @Test public void invalidNestedContainerKeepsParentUsableAndDiscardsExtractedStage() throws Exception {
        select("Directory: folder/"); filter().setText("invalid"); select("invalid.zip"); action(AlertDialog.BUTTON_NEUTRAL); settle();
        assertTrue(status(), status().contains("Member extraction failed"));
        assertFalse(button("containerBack").isEnabled()); assertEquals("invalid", filter().getText().toString());
        assertEquals(1, list().getAdapter().getCount()); assertEquals(0, Objects.requireNonNull(snapshotDirectory().list()).length);
        button("parent").performClick(); assertEquals(50, list().getAdapter().getCount());
    }

    @Test public void queuedArchiveCancellationCanReloadTheInventory() throws Exception {
        ExecutorService worker = ReflectionHelpers.getField(activity, "worker"); CountDownLatch release = new CountDownLatch(1);
        worker.submit(() -> { try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        try { button("reload").performClick(); button("cancel").performClick(); } finally { release.countDown(); }
        settle(); assertTrue(status(), status().contains("cancelled")); assertNull(list().getAdapter());
        button("reload").performClick(); settle(); assertEquals(50, list().getAdapter().getCount());
    }

    @Test public void nestedDepthBudgetKeepsTheLastCompleteContainerUsable() throws Exception {
        byte[] chain = zip(Map.of("payload.bin", PAYLOAD));
        for (int i = 0; i < 17; i++) chain = zip(Map.of("next.zip", chain));
        File deep = temporary.resolve("deep.zip").toFile(); Files.write(deep.toPath(), chain);
        controller.pause().stop().destroy();
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ArchiveExplorerActivity.class).putExtra("path", deep.getAbsolutePath()).putExtra("name", "deep.zip");
        controller = Robolectric.buildActivity(ArchiveExplorerActivity.class, intent).setup().visible(); activity = controller.get(); settle();
        for (int level = 2; level <= ArchiveExplorerActivity.MAX_LEVELS; level++) {
            select("next.zip"); action(AlertDialog.BUTTON_NEUTRAL); settle();
            assertTrue(status(), status().contains("level " + level + "/16"));
        }
        assertEquals(15, Objects.requireNonNull(snapshotDirectory().list()).length);
        select("next.zip"); action(AlertDialog.BUTTON_NEUTRAL); settle();
        assertTrue(status(), status().contains("Nested navigation budget"));
        assertEquals(1, list().getAdapter().getCount()); assertEquals(15, Objects.requireNonNull(snapshotDirectory().list()).length);
        button("containerBack").performClick(); settle(); assertTrue(status(), status().contains("level 15/16"));
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buffer)) {
            for (var entry : entries.entrySet()) { out.putNextEntry(new ZipEntry(entry.getKey())); out.write(entry.getValue()); out.closeEntry(); }
        }
        return buffer.toByteArray();
    }
}
