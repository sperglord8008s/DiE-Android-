// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
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

/**
 * Real catalog files/widgets and one actual CDiE request; only document streams are substituted.
 */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ScanProfilesWorkbenchTest {
    private ActivityController<ScanProfilesActivity> controller;
    private ScanProfilesActivity activity;
    private ScanProfileStore store;
    private File directory;
    private ScanOptions originalOptions;

    @Before
    public void setup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        directory = new File(context.getFilesDir(), "scan-profiles");
        remove(directory.toPath());
        store = new ScanProfileStore(directory);
        originalOptions =
                new ScanOptions(
                        ScanOptions.DEFAULT_FLAGS | ScanOptions.RESOURCES | ScanOptions.CACHE,
                        9,
                        "compiler",
                        "fixture-excluded");
        ScanPreferences.save(context, originalOptions);
        controller = Robolectric.buildActivity(ScanProfilesActivity.class).setup().visible();
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
        remove(directory.toPath());
    }

    private static void remove(Path path) throws Exception {
        if (Files.exists(path))
            try (var files = Files.walk(path)) {
                for (Path item : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(item);
            }
    }

    private Button button(String name) {
        return ReflectionHelpers.getField(activity, name);
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
        fail("Profile work did not settle: " + status());
    }

    private static void edits(View view, List<EditText> fields) {
        if (view instanceof EditText) fields.add((EditText) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) edits(group.getChildAt(i), fields);
        }
    }

    private static Button findButton(View view, String label) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(label))
            return (Button) view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                Button found = findButton(((ViewGroup) view).getChildAt(i), label);
                if (found != null) return found;
            }
        return null;
    }

    private void fill(String name, String description) {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        List<EditText> fields = new ArrayList<>();
        edits(dialog.getWindow().getDecorView(), fields);
        assertEquals(2, fields.size());
        fields.get(0).setText(name);
        fields.get(1).setText(description);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    }

    private void save(String name) throws Exception {
        button("save").performClick();
        fill(name, "Captured fixture settings");
        settle();
        assertEquals(status(), 1, store.load(() -> false).profiles.size());
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
        fail("Missing profile action: " + label);
    }

    private void profile(int index, String action) {
        ListView list = ReflectionHelpers.getField(activity, "list");
        list.performItemClick(null, index, index);
        choose(action);
    }

    private PreparedExport prepare() throws Exception {
        button("exportButton").performClick();
        settle();
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        PreparedExport prepared = ReflectionHelpers.getField(activity, "prepared");
        assertNotNull(prepared);
        return prepared;
    }

    private byte[] finishExport(OutputStream output) throws Exception {
        Uri uri = Uri.parse("content://org.diemobile.test/profile-export");
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(uri, output);
        activity.onActivityResult(2, Activity.RESULT_OK, new Intent().setData(uri));
        settle();
        return output instanceof ByteArrayOutputStream
                ? ((ByteArrayOutputStream) output).toByteArray()
                : new byte[0];
    }

    @Test
    public void savedProfileUpdatesSharedSettingsAndAnActualDesktopRequest() throws Exception {
        save("Expert 研究");
        ScanProfiles catalog = store.load(() -> false);
        assertEquals(1, catalog.profiles.size());
        assertTrue(catalog.profiles.get(0).options.sameAs(originalOptions));
        ScanPreferences.save(activity, new ScanOptions(0, 3, "changed", ""));
        profile(0, "Apply captured settings");
        assertTrue(ScanPreferences.load(activity).sameAs(originalOptions));
        assertTrue(activity.isFinishing());
        assertTrue("Actual host CDiE JNI required", Engine.AVAILABLE);
        EngineDatabase database = EngineDatabase.prepare(activity);
        ScanOptions applied = ScanPreferences.load(activity);
        File input = new File(System.getProperty("die.fixture.dir"), "elf-fixture");
        assertTrue(input.isFile());
        JSONObject report =
                new JSONObject(
                        Engine.desktop(
                                input.getAbsolutePath(),
                                database.main.getAbsolutePath(),
                                database.custom.getAbsolutePath(),
                                1,
                                applied.flags,
                                0,
                                applied.includeTags,
                                applied.excludeTags,
                                "",
                                ""));
        assertTrue(report.toString(), report.getBoolean("ok"));
        assertEquals(applied.flags, report.getInt("flags"));
        assertTrue(report.getString("type").contains("ELF"));
        assertTrue(report.getInt("signatureCount") > 0);
    }

    @Test
    public void staleDialogCannotOverwriteANewerCatalogAndReloadRestoresTheView() throws Exception {
        save("Original");
        ScanProfiles captured = store.load(() -> false);
        profile(0, "Rename / describe");
        store.save(
                captured.sha256,
                null,
                "Concurrent",
                "",
                new ScanOptions(0, 3, "", ""),
                () -> false);
        fill("Stale rename", "This must not replace newer bytes");
        settle();
        assertTrue(status(), status().contains("catalog changed"));
        ScanProfiles latest = store.load(() -> false);
        assertEquals(2, latest.profiles.size());
        assertEquals("Original", latest.profiles.get(0).name);
        button("reloadButton").performClick();
        settle();
        assertEquals(
                2, ReflectionHelpers.<ScanProfiles>getField(activity, "catalog").profiles.size());
        profile(0, "Rename / describe");
        fill("Renamed", "Retains captured settings");
        settle();
        assertEquals("Renamed", store.load(() -> false).profiles.get(0).name);
        ScanOptions newer = new ScanOptions(0, 6, "new-filter", "");
        ScanPreferences.save(activity, newer);
        profile(0, "Replace settings with current");
        fill("Updated current", "Captured new settings");
        settle();
        assertTrue(store.load(() -> false).profiles.get(0).options.sameAs(newer));
        profile(1, "Delete profile");
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        settle();
        assertEquals(1, store.load(() -> false).profiles.size());
        assertTrue(ScanPreferences.load(activity).sameAs(newer));
    }

    @Test
    public void completeExportKeepsItsCapturedCatalogAcrossReplacementAndRecreation()
            throws Exception {
        save("Captured");
        PreparedExport prepared = prepare();
        byte[] exact = Files.readAllBytes(prepared.file.toPath());
        store.replace(store.load(() -> false).sha256, ScanProfiles.empty(), () -> false);
        controller.recreate();
        activity = controller.get();
        settle();
        assertArrayEquals(exact, finishExport(new ByteArrayOutputStream()));
        assertTrue(status(), status().contains("exported"));
        assertFalse(prepared.file.exists());
        assertEquals(
                0, ReflectionHelpers.<ScanProfiles>getField(activity, "catalog").profiles.size());
        assertTrue(button("save").isEnabled());
    }

    @Test
    public void restoredImportPickerValidatesBeforeReplacementAndRetainsCapturedBytes()
            throws Exception {
        save("Local");
        String originalRevision = store.load(() -> false).sha256;
        button("importButton").performClick();
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        controller.recreate();
        activity = controller.get();
        settle();
        Uri invalid = Uri.parse("content://org.diemobile.test/bad-profile");
        Shadows.shadowOf(activity.getContentResolver())
                .registerInputStream(
                        invalid,
                        new ByteArrayInputStream(
                                "{\"profiles\":{},\"profiles\":{}}"
                                        .getBytes(StandardCharsets.UTF_8)));
        activity.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(invalid));
        settle();
        assertTrue(status(), status().contains("prior catalog retained"));
        assertEquals(originalRevision, store.load(() -> false).sha256);
        ScanProfiles incoming =
                ScanProfiles.empty()
                        .save(null, "Imported", "", new ScanOptions(0, 3, "", ""), () -> false);
        String exact =
                incoming.original.replace("\"profiles\":{", "\"unknownRoot\":1e400,\"profiles\":{");
        button("importButton").performClick();
        Shadows.shadowOf(activity).getNextStartedActivityForResult();
        Uri valid = Uri.parse("content://org.diemobile.test/good-profile");
        Shadows.shadowOf(activity.getContentResolver())
                .registerInputStream(
                        valid, new ByteArrayInputStream(exact.getBytes(StandardCharsets.UTF_8)));
        activity.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(valid));
        settle();
        AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals(originalRevision, store.load(() -> false).sha256);
        confirmation.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        assertTrue("Inspecting captured data dismissed the approval", confirmation.isShowing());
        Shadows.shadowOf(activity.getContentResolver())
                .registerInputStream(
                        valid, new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertArrayEquals(
                exact.getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(new File(directory, "catalog.json").toPath()));
        assertTrue(
                "Catalog import unexpectedly applied request settings",
                ScanPreferences.load(activity).sameAs(originalOptions));
    }

    @Test
    public void mainEntryClosesItsStaleEditorAndReloadsTheAppliedProfile() throws Exception {
        save("Main profile");
        ScanPreferences.save(activity, new ScanOptions(0, 3, "old", ""));
        ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).setup().visible();
        MainActivity screen = main.get();
        try {
            ReflectionHelpers.callInstanceMethod(screen, "optionsDialog");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog options = ShadowAlertDialog.getLatestAlertDialog();
            Button entry =
                    findButton(options.getWindow().getDecorView(), "Saved scan option profiles");
            assertNotNull(entry);
            entry.performClick();
            assertFalse(options.isShowing());
            assertEquals(
                    ScanProfilesActivity.class.getName(),
                    Shadows.shadowOf(screen)
                            .getNextStartedActivity()
                            .getComponent()
                            .getClassName());
            profile(0, "Apply captured settings");
            main.pause().stop().start().resume().visible();
            assertEquals(
                    "9", ReflectionHelpers.<EditText>getField(screen, "min").getText().toString());
            assertTrue(ScanPreferences.load(screen).sameAs(originalOptions));
        } finally {
            ExecutorService worker = ReflectionHelpers.getField(screen, "worker");
            main.pause().stop().destroy();
            assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    public void cancelledAndFailedDestinationsDiscardOnlyThePreparedExport() throws Exception {
        save("Retained");
        String revision = store.load(() -> false).sha256;
        PreparedExport first = prepare();
        activity.onActivityResult(2, Activity.RESULT_CANCELED, null);
        settle();
        assertFalse(first.file.exists());
        PreparedExport second = prepare();
        finishExport(
                new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        throw new IOException("Fixture destination failure");
                    }
                });
        assertTrue(status(), status().contains("partial data"));
        assertFalse(second.file.exists());
        assertEquals(revision, store.load(() -> false).sha256);
        assertTrue(button("save").isEnabled());
    }
}
