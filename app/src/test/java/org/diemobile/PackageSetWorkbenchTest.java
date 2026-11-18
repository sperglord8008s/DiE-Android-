// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.app.*;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Looper;
import android.widget.*;

import com.android.apksig.ApkSigner;

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
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * Real AAPT2 manifests, actual signatures, acquisition and widgets; OS worker/provider transport
 * substituted.
 */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28, shadows = ResourceWorkbenchTest.HostWorkerTransport.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PackageSetWorkbenchTest {
    private static Path keyDirectory;
    private static KeyStore keys;
    private Path temporary;
    private ArrayList<String> paths;
    private ActivityController<InvestigationActivity> controller;
    private InvestigationActivity activity;

    @BeforeClass
    public static void keys() throws Exception {
        keyDirectory = Files.createTempDirectory("die-set-keys");
        ApkSigningFixture.generateKeys(keyDirectory.resolve("keys.p12"));
        keys = ApkSigningFixture.keys(keyDirectory.resolve("keys.p12"));
    }

    @AfterClass
    public static void removeKeys() throws Exception {
        remove(keyDirectory);
    }

    private static void remove(Path root) throws Exception {
        if (root != null)
            try (var entries = Files.walk(root)) {
                for (Path path : entries.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
    }

    @Before
    public void fixture() throws Exception {
        temporary = Files.createTempDirectory("die-package-set-ui");
        paths = new ArrayList<>();
        for (String name : List.of("base", "core", "notes", "language", "density"))
            paths.add(sign(name, name.equals("density") ? "rotated" : "fixture").getAbsolutePath());
        open();
    }

    private File sign(String name, String alias) throws Exception {
        File original =
                Path.of(System.getProperty("die.fixture.dir"), "packages", name + ".apk").toFile();
        assertTrue("Generate AAPT2 package references first", original.isFile());
        File output =
                temporary
                        .resolve(name.equals("core") ? "looks-like-base.apk" : name + "-signed.apk")
                        .toFile();
        new ApkSigner.Builder(List.of(ApkSigningFixture.signer(keys, alias)))
                .setInputApk(original)
                .setOutputApk(output)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .build()
                .sign();
        return output;
    }

    private void open() throws Exception {
        Intent intent =
                new Intent(RuntimeEnvironment.getApplication(), InvestigationActivity.class)
                        .putExtra("provider", "package-set")
                        .putExtra("path", paths.get(0))
                        .putExtra("name", "captured package fixture")
                        .putStringArrayListExtra("packagePaths", paths)
                        .putExtra("inventoryPackage", "org.diemobile.split.reference");
        controller =
                Robolectric.buildActivity(InvestigationActivity.class, intent).setup().visible();
        activity = controller.get();
        settle();
    }

    private void destroy() throws Exception {
        if (controller == null) return;
        ExecutorService worker = ReflectionHelpers.getField(activity, "worker");
        controller.pause().stop().destroy();
        controller = null;
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
    }

    @After
    public void cleanup() throws Exception {
        destroy();
        remove(temporary);
    }

    private ListView list() {
        return activity.findViewById(R.id.investigation_records);
    }

    private EvidenceRows.Row row(int i) {
        return (EvidenceRows.Row) list().getAdapter().getItem(i);
    }

    private JSONObject record(int i) throws Exception {
        return row(i).json().getJSONObject("record");
    }

    private Button button(String name) {
        return ReflectionHelpers.getField(activity, name);
    }

    private String status() {
        return ((TextView) activity.findViewById(R.id.investigation_status)).getText().toString();
    }

    private void settle() throws Exception {
        for (int i = 0; i < 10; i++) {
            ReflectionHelpers.<ExecutorService>getField(activity, "worker")
                    .submit(() -> {})
                    .get(60, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!ReflectionHelpers.<Boolean>getField(activity, "busy")) return;
        }
        fail("Package operation did not settle: " + status());
    }

    private void choose(String text) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView choices = dialog.getListView();
        for (int i = 0; i < choices.getAdapter().getCount(); i++)
            if (choices.getAdapter().getItem(i).toString().contains(text)) {
                choices.performItemClick(null, i, i);
                dialog.dismiss();
                return;
            }
        fail("Missing package action " + text);
    }

    private void root(String text) throws Exception {
        button("views").performClick();
        choose(text);
        settle();
    }

    private void action(int index, String text) throws Exception {
        list().performItemClick(null, index, index);
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        settle();
        choose(text);
        settle();
    }

    private void search(String text) throws Exception {
        ((EditText) activity.findViewById(R.id.investigation_filter)).setText(text);
        button("search").performClick();
        settle();
    }

    private void recreate() throws Exception {
        controller.recreate();
        activity = controller.get();
        settle();
    }

    private byte[] export() throws Exception {
        Uri uri = Uri.parse("content://org.diemobile.test/package-output");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(uri, output);
        activity.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(uri));
        settle();
        assertTrue(status(), status().contains("Export saved"));
        return output.toByteArray();
    }

    @Test
    public void systemInventoryHandsOffEveryExposedPathWithoutInstallingOrExecutingTheApks()
            throws Exception {
        destroy();
        PackageInfo info = new PackageInfo();
        info.packageName = "org.diemobile.split.reference";
        info.versionName = "fixture";
        ApplicationInfo application = new ApplicationInfo();
        application.packageName = info.packageName;
        application.sourceDir = paths.get(0);
        application.publicSourceDir = paths.get(0);
        application.splitSourceDirs = paths.subList(1, paths.size()).toArray(new String[0]);
        application.uid = 12345;
        application.minSdkVersion = 26;
        application.targetSdkVersion = 35;
        application.nonLocalizedLabel = "Package fixture";
        info.applicationInfo = application;
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .installPackage(info);
        ActivityController<SystemExplorerActivity> inventory =
                Robolectric.buildActivity(SystemExplorerActivity.class).setup().visible();
        SystemExplorerActivity explorer = inventory.get();
        ExecutorService worker = ReflectionHelpers.getField(explorer, "worker");
        try {
            worker.submit(() -> {}).get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ReflectionHelpers.callInstanceMethod(
                    explorer,
                    "packageDetails",
                    ReflectionHelpers.ClassParameter.from(String.class, info.packageName));
            worker.submit(() -> {}).get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ListView list = ReflectionHelpers.getField(explorer, "list");
            boolean selected = false;
            for (int i = 0; i < list.getAdapter().getCount(); i++)
                if (list.getAdapter()
                        .getItem(i)
                        .toString()
                        .contains("Investigate captured base + split APK set")) {
                    list.performItemClick(null, i, i);
                    selected = true;
                    break;
                }
            assertTrue(selected);
            Intent intent = Shadows.shadowOf(explorer).getNextStartedActivity();
            assertEquals("package-set", intent.getStringExtra("provider"));
            assertEquals(paths, intent.getStringArrayListExtra("packagePaths"));
            controller =
                    Robolectric.buildActivity(InvestigationActivity.class, intent)
                            .setup()
                            .visible();
            activity = controller.get();
            settle();
            assertEquals(5, record(0).getInt("decodedManifests"));
            assertEquals(
                    info.packageName,
                    new JSONObject(ReflectionHelpers.<String>getField(activity, "provenance"))
                            .getString("observedInventoryPackageName"));
        } finally {
            inventory.pause().stop().destroy();
            assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    public void actualSplitParentsTypesAndSignerMismatchNavigateTheCorrectCapturedSources()
            throws Exception {
        assertEquals(5, record(0).getInt("decodedManifests"));
        assertEquals(5, record(0).getInt("verifiedEmbeddedApks"));
        assertTrue(record(0).getBoolean("declarationAcquisitionComplete"));
        root("Package set • dependencies");
        search("conditional AOSP relationship");
        assertEquals(1, list().getAdapter().getCount());
        assertEquals(2, record(0).getInt("sourceIndex"));
        assertEquals(1, record(0).getJSONArray("targetSourceIndices").getInt(0));
        assertNull(row(0).physicalOffset);
        action(0, "Inspect captured APK source 1");
        assertEquals("feature.core", record(1).getString("splitName"));
        action(1, "Original package / split");
        search("versionCodeMajor");
        assertEquals(1, list().getAdapter().getCount());
        assertEquals(2L, record(0).getLong("value"));
        root("Package set • split-types");
        assertEquals(2, list().getAdapter().getCount());
        Set<String> provided = new HashSet<>();
        for (int i = 0; i < 2; i++) {
            assertEquals(1, record(i).getJSONArray("providerDeclarations").length());
            provided.add(record(i).getString("normalizedToken"));
        }
        assertEquals(Set.of("language", "density"), provided);
        root("Package set • signing-comparisons");
        search("density-signed.apk");
        assertEquals(1, list().getAdapter().getCount());
        assertFalse(record(0).getBoolean("exactVerifiedCertificateDerSetsEqual"));
        assertTrue(record(0).getBoolean("versionBitsEqual"));
        action(0, "This APK signing • certificates");
        assertEquals(1, list().getAdapter().getCount());
        action(0, "Export exact certificate DER");
        assertArrayEquals(ApkSigningFixture.certificate(keys, "rotated").getEncoded(), export());
    }

    @Test
    public void ownedApksAndManifestBytesSurviveOriginalDeletionRecreationAndPickerDelay()
            throws Exception {
        root("Captured APK sources");
        String expected = record(2).getString("sha256");
        byte[] apk = Files.readAllBytes(Path.of(paths.get(2)));
        byte[] manifest;
        try (ZipFile zip = new ZipFile(paths.get(2));
                InputStream input = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))) {
            manifest = input.readAllBytes();
        }
        for (String path : paths) assertTrue(new File(path).delete());
        recreate();
        assertEquals(expected, record(2).getString("sha256"));
        action(2, "Exact acquired APK bytes");
        assertEquals(Long.valueOf(0), row(0).physicalOffset);
        assertEquals(expected, row(0).json().getString("sha256"));
        action(0, "Export exact stored range");
        recreate();
        assertArrayEquals(apk, export());
        action(0, "Exact expanded manifest bytes");
        assertEquals(
                Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(manifest)),
                row(0).json().getString("sha256"));
        action(0, "Export exact stored range");
        recreate();
        assertArrayEquals(manifest, export());
    }

    @Test
    public void completeSetExportIgnoresFilterAndPreservesAllSourceAndCertificateIdentities()
            throws Exception {
        root("All collected package-set evidence");
        int count = ReflectionHelpers.<EvidenceRows>getField(activity, "source").size();
        assertTrue(count > 100);
        search("looks-like-base.apk");
        assertTrue(list().getAdapter().getCount() > 0);
        button("export").performClick();
        settle();
        for (String path : paths) assertTrue(new File(path).delete());
        recreate();
        JSONObject report = new JSONObject(new String(export(), StandardCharsets.UTF_8));
        assertEquals(count, report.getJSONArray("rows").length());
        assertEquals(5, report.getJSONObject("provenance").getJSONArray("inputs").length());
        boolean certificate = false, config = false;
        for (int i = 0; i < count; i++) {
            JSONObject row = report.getJSONArray("rows").getJSONObject(i);
            if (row.getString("recordTable").equals("signing-certificates")) {
                certificate = true;
                assertTrue(row.getJSONObject("record").getString("derBase64").length() > 1000);
            }
            if (row.getString("recordTable").equals("dependencies")
                    && row.getJSONObject("record").getString("kind").equals("configuration-target"))
                config = true;
            assertTrue(row.isNull("physicalOffset"));
        }
        assertTrue(certificate && config);
        assertFalse(report.toString().contains("sessionDirectory"));
    }

    @Test
    public void duplicateAndMissingScopesStayVisibleAndRetainedFailuresDoNotGrowAcrossRecreation()
            throws Exception {
        destroy();
        paths.add(sign("duplicate-notes", "fixture").getAbsolutePath());
        paths.add(sign("missing", "fixture").getAbsolutePath());
        paths.add(temporary.resolve("unavailable.apk").toString());
        open();
        assertFalse(record(0).getBoolean("declarationAcquisitionComplete"));
        assertEquals(8, record(0).getInt("explicitInputs"));
        root("Package set • groups");
        assertEquals(1, record(0).getJSONArray("duplicateSplitIdentities").length());
        root("Package set • dependencies");
        search("feature.absent");
        assertEquals(1, list().getAdapter().getCount());
        assertEquals(0, record(0).getJSONArray("targetSourceIndices").length());
        assertFalse(record(0).getBoolean("declarationScopeComplete"));
        root("Captured APK sources");
        String originalError = record(7).getJSONArray("errors").toString();
        assertFalse(record(7).getBoolean("acquired"));
        for (String path : paths) Files.deleteIfExists(Path.of(path));
        recreate();
        recreate();
        assertEquals(originalError, record(7).getJSONArray("errors").toString());
        assertTrue(record(7).getBoolean("retainedPriorAcquisitionFailure"));
        root("Package set • groups");
        assertEquals(
                2,
                record(0)
                        .getJSONArray("duplicateSplitIdentities")
                        .getJSONObject(0)
                        .getJSONArray("sourceIndices")
                        .length());
    }
}
