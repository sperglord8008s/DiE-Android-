// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.app.*;
import android.content.Intent;
import android.content.pm.*;
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
import java.util.*;
import java.util.concurrent.*;

/**
 * Actual pinned catalog and widget/ownership flows; only OS inventory/output destinations differ.
 */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PackageKnowledgeWorkbenchTest {
    private ActivityController<InvestigationActivity> controller;
    private InvestigationActivity activity;
    private byte[] original;

    @Before
    public void setup() throws Exception {
        try (InputStream in =
                RuntimeEnvironment.getApplication().getAssets().open("knowledge/uad.json")) {
            original = in.readAllBytes();
        }
    }

    @After
    public void cleanup() throws Exception {
        if (controller != null) close(controller);
    }

    private void close(ActivityController<? extends Activity> value) throws Exception {
        ExecutorService worker = ReflectionHelpers.getField(value.get(), "worker");
        value.pause().stop().destroy();
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void settle(Activity target) throws Exception {
        for (int i = 0; i < 6; i++) {
            ReflectionHelpers.<ExecutorService>getField(target, "worker")
                    .submit(() -> {})
                    .get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private void settle() throws Exception {
        settle(activity);
    }

    private void open(String id) throws Exception {
        Intent intent =
                new Intent(RuntimeEnvironment.getApplication(), InvestigationActivity.class)
                        .putExtra("provider", "package-knowledge")
                        .putExtra("path", "/nonexistent/catalog-launch")
                        .putExtra("name", "Package knowledge fixture");
        if (id != null) intent.putExtra("knowledgePackageId", id);
        open(intent);
    }

    private void open(Intent intent) throws Exception {
        controller =
                Robolectric.buildActivity(InvestigationActivity.class, intent).setup().visible();
        activity = controller.get();
        settle();
        assertFalse(status(), ReflectionHelpers.<Boolean>getField(activity, "busy"));
        assertNotNull(status(), ReflectionHelpers.getField(activity, "source"));
    }

    private String status() {
        return ReflectionHelpers.<TextView>getField(activity, "status").getText().toString();
    }

    private ListView list() {
        return activity.findViewById(R.id.investigation_records);
    }

    private EvidenceRows.Row row(int index) {
        return (EvidenceRows.Row) list().getAdapter().getItem(index);
    }

    private void button(String name) throws Exception {
        ReflectionHelpers.<Button>getField(activity, name).performClick();
        settle();
    }

    private void choose(String label) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView choices = dialog.getListView();
        for (int i = 0; i < choices.getAdapter().getCount(); i++)
            if (choices.getAdapter().getItem(i).toString().contains(label)) {
                choices.performItemClick(null, i, i);
                dialog.dismiss();
                return;
            }
        fail("Missing action " + label);
    }

    private void root(String label) throws Exception {
        button("views");
        choose(label);
        settle();
    }

    private void action(int index, String label) throws Exception {
        list().performItemClick(null, index, index);
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        settle();
        choose(label);
        settle();
    }

    private byte[] finishExport() throws Exception {
        Uri destination = Uri.parse("content://org.diemobile.test/package-knowledge-output");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(destination, output);
        activity.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(destination));
        settle();
        assertTrue(status(), status().contains("Export saved"));
        return output.toByteArray();
    }

    @Test
    public void completeCatalogPagesAndCategoriesKeepDuplicateSourceOccurrences() throws Exception {
        open((String) null);
        EvidenceRows source = ReflectionHelpers.getField(activity, "source");
        assertEquals(5402, source.size());
        assertEquals(5399, source.metadata().getInt("uniquePackageIds"));
        assertEquals(3, source.metadata().getInt("duplicatePackageIds"));
        assertEquals(50, list().getAdapter().getCount());
        int first = row(0).json().getInt("sourceRecordIndex");
        button("next");
        assertEquals(first + 50, row(0).json().getInt("sourceRecordIndex"));
        root("Upstream list: Google");
        assertEquals(184, ReflectionHelpers.<EvidenceRows>getField(activity, "source").size());
        root("Every community package record");
        ((EditText) activity.findViewById(R.id.investigation_filter))
                .setText("com.aura.oobe.motorola");
        button("search");
        int exact = 0;
        Set<Integer> indices = new HashSet<>();
        for (int i = 0; i < list().getAdapter().getCount(); i++)
            if (row(i).json().getString("packageId").equals("com.aura.oobe.motorola")) {
                exact++;
                indices.add(row(i).json().getInt("sourceRecordIndex"));
                assertEquals(2, row(i).json().getInt("exactIdRecordCount"));
            }
        assertEquals(2, exact);
        assertEquals(2, indices.size());
    }

    @Test
    public void duplicateOverviewAndRenderedRecordSelectTheActualOriginalOccurrence()
            throws Exception {
        open("com.aura.oobe.motorola");
        assertEquals(12, list().getAdapter().getCount());
        List<Integer> descriptions = new ArrayList<>();
        Set<Integer> originals = new HashSet<>();
        for (int i = 0; i < list().getAdapter().getCount(); i++)
            if (row(i).json().optString("sourceField").equals("description")) {
                descriptions.add(i);
                originals.add(row(i).json().getInt("sourceRecordIndex"));
            }
        assertEquals(2, descriptions.size());
        assertEquals(2, originals.size());
        int chosen = descriptions.get(0);
        int expectedIndex = row(chosen).json().getInt("sourceRecordIndex");
        String expected = row(chosen).json().getString("valuePreview");
        action(chosen, "Full original record / source identity");
        AlertDialog viewer = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(viewer.getWindow().getDecorView().findViewWithTag("text-page-body"));
        viewer.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        JSONObject result = new JSONObject(new String(finishExport(), StandardCharsets.UTF_8));
        assertEquals("com.aura.oobe.motorola", result.getString("packageId"));
        assertEquals(expected, result.getJSONObject("originalRecord").getString("description"));
        PackageKnowledge parsed =
                PackageKnowledge.read(new ByteArrayInputStream(original), () -> false);
        assertEquals(expected, parsed.entries.get(expectedIndex).description);
        assertEquals(2, parsed.byId.get("com.aura.oobe.motorola").size());
    }

    @Test
    public void completeOriginalCatalogExportSurvivesOwnedSourceExpiryAndPickerRecreation()
            throws Exception {
        open("org.lineageos.jelly");
        assertTrue(row(0).name.startsWith("What does"));
        assertTrue(row(0).description.startsWith("LineageOS Browser"));
        File captured =
                ReflectionHelpers.<PackageKnowledgeBackend>getField(activity, "backend")
                        .byteSource();
        controller.recreate();
        activity = controller.get();
        settle();
        assertEquals(
                captured,
                ReflectionHelpers.<PackageKnowledgeBackend>getField(activity, "backend")
                        .byteSource());
        root("Export exact original UAD-NG catalog bytes");
        PreparedExport staged = ReflectionHelpers.getField(activity, "prepared");
        assertNotNull(staged);
        assertArrayEquals(original, Files.readAllBytes(staged.file.toPath()));
        assertTrue(captured.delete());
        controller.recreate();
        activity = controller.get();
        settle();
        assertTrue(status(), status().contains("Investigation failed"));
        assertArrayEquals(original, finishExport());
        assertFalse(staged.file.exists());
    }

    @Test
    public void absentExactIdStaysUnknownAndRelationshipLinksUseDeclaredDirections()
            throws Exception {
        open("ORG.LINEAGEOS.JELLY");
        assertEquals(1, list().getAdapter().getCount());
        assertFalse(row(0).json().getBoolean("exactMatch"));
        close(controller);
        controller = null;
        open("com.huawei.health");
        root("Declared dependencies");
        boolean found = false;
        for (int i = 0; i < list().getAdapter().getCount(); i++) {
            JSONObject fields = row(i).json();
            if (fields.optString("targetPackageId").equals("com.huawei.hwid")
                    && fields.getString("declaredField").equals("dependencies")) {
                assertEquals("com.huawei.health", fields.getString("dependencyFrom"));
                assertEquals("com.huawei.hwid", fields.getString("dependencyTo"));
                action(i, "Follow exact referenced ID");
                found = true;
                break;
            }
        }
        assertTrue(found);
        assertTrue(
                ReflectionHelpers.<String>getField(activity, "metadata")
                        .contains("com.huawei.hwid"));
        button("back");
        assertEquals(
                "package-knowledge-relationships",
                ReflectionHelpers.<EvidenceRows>getField(activity, "source").kind);
    }

    @Test
    public void mainCatalogAndInstalledPackageLinkRequireNoInspectedProgramExecution()
            throws Exception {
        ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).setup().visible();
        try {
            ReflectionHelpers.callInstanceMethod(main.get(), "openPackageKnowledge");
            Intent launch = Shadows.shadowOf(main.get()).getNextStartedActivity();
            assertEquals("package-knowledge", launch.getStringExtra("provider"));
            assertFalse(launch.hasExtra("knowledgePackageId"));
        } finally {
            close(main);
        }
        PackageInfo info = new PackageInfo();
        info.packageName = "org.lineageos.jelly";
        info.versionName = "fixture";
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = info.packageName;
        app.sourceDir = "/not-readable/base.apk";
        app.uid = 12345;
        app.nonLocalizedLabel = "Fixture package";
        app.minSdkVersion = 26;
        app.targetSdkVersion = 35;
        info.applicationInfo = app;
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .installPackage(info);
        ActivityController<SystemExplorerActivity> inventory =
                Robolectric.buildActivity(SystemExplorerActivity.class).setup().visible();
        try {
            SystemExplorerActivity screen = inventory.get();
            settle(screen);
            ReflectionHelpers.callInstanceMethod(
                    screen,
                    "packageDetails",
                    ReflectionHelpers.ClassParameter.from(String.class, info.packageName));
            settle(screen);
            ListView entries = ReflectionHelpers.getField(screen, "list");
            boolean found = false;
            for (int i = 0; i < entries.getAdapter().getCount(); i++)
                if (entries.getAdapter()
                        .getItem(i)
                        .toString()
                        .contains("What does this package do?")) {
                    entries.performItemClick(null, i, i);
                    found = true;
                    break;
                }
            assertTrue(found);
            Intent launch = Shadows.shadowOf(screen).getNextStartedActivity();
            assertEquals("package-knowledge", launch.getStringExtra("provider"));
            assertEquals(info.packageName, launch.getStringExtra("knowledgePackageId"));
            open(launch);
            assertTrue(row(0).description.startsWith("LineageOS Browser"));
        } finally {
            close(inventory);
        }
    }

    @Test
    public void apkSummaryRetainsBothCommunityClaimsWhenPlatformSuppliesADuplicateId()
            throws Exception {
        File reference =
                new File(
                        RuntimeEnvironment.getApplication().getCacheDir(),
                        "metadata-only-reference.apk");
        PackageInfo info = new PackageInfo();
        info.packageName = "com.aura.oobe.motorola";
        info.versionName = "fixture";
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = info.packageName;
        app.minSdkVersion = 26;
        app.targetSdkVersion = 35;
        info.applicationInfo = app;
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .setPackageArchiveInfo(reference.getAbsolutePath(), info);
        KnowledgeReport.Output report = new KnowledgeReport.Output();
        KnowledgeReport.pkg(RuntimeEnvironment.getApplication(), reference, report, null);
        String text = report.apk.toString();
        assertTrue(text, text.contains("2 original community record(s)"));
        assertTrue(text, text.contains("Aura from Unity") && text.contains("MotoApps"));
        assertTrue(
                text,
                text.contains("Community list: \"Oem\"")
                        && text.contains("Community list: \"Carrier\""));
        assertTrue(text, text.contains(PackageKnowledge.PINNED_SHA256));
        assertTrue(text, text.contains("not identify a vendor"));
    }
}
