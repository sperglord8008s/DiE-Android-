// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;
import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

/** Real smali engines through Android navigation and staged document output. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DexWorkbenchTest {
    private ActivityController<InvestigationActivity> controller;
    private InvestigationActivity activity;
    private Path temporary;
    private File input;

    @Before public void openFixture() throws Exception {
        temporary = Files.createTempDirectory("die-dex-ui"); input = temporary.resolve("rich.dex").toFile(); Files.write(input.toPath(), DexFixtureFactory.rich()); open(input);
    }
    private void open(File file) throws Exception {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), InvestigationActivity.class).putExtra("path", file.getAbsolutePath()).putExtra("name", file.getName()).putExtra("provider", "dex");
        controller = Robolectric.buildActivity(InvestigationActivity.class, intent).setup().visible(); activity = controller.get(); settle();
    }
    @After public void close() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        if (temporary != null) try (var paths = Files.walk(temporary)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    private void settle() throws Exception {
        for (int i = 0; i < 8; i++) {
            ReflectionHelpers.<ExecutorService>getField(activity, "worker").submit(() -> {}).get(30, TimeUnit.SECONDS); Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!ReflectionHelpers.<Boolean>getField(activity, "busy")) return;
        }
        fail("DEX worker did not settle: " + status());
    }
    private ListView records() { return activity.findViewById(R.id.investigation_records); }
    private EditText filter() { return activity.findViewById(R.id.investigation_filter); }
    private String status() { return ((TextView)activity.findViewById(R.id.investigation_status)).getText().toString(); }
    private Button button(String field) { return ReflectionHelpers.getField(activity, field); }
    private EvidenceRows.Row row(int position) { return (EvidenceRows.Row)records().getAdapter().getItem(position); }
    private int position(String name) {
        for (int i = 0; i < records().getAdapter().getCount(); i++) if (row(i).name.contains(name)) return i;
        throw new AssertionError("Missing displayed row " + name + ": " + status());
    }
    private void choose(String label) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog); ListView choices = dialog.getListView(); assertNotNull(choices);
        for (int i = 0; i < choices.getAdapter().getCount(); i++) if (choices.getAdapter().getItem(i).toString().contains(label)) { choices.performItemClick(null,i,i); dialog.dismiss(); return; }
        fail("Missing action " + label);
    }
    private void action(String rowName, String label) throws Exception {
        int index = position(rowName); records().performItemClick(null,index,index);
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); settle(); choose(label); settle();
    }
    private void root(String label) throws Exception { button("views").performClick(); choose(label); settle(); }
    private void method() throws Exception {
        filter().setText("研究"); button("search").performClick(); settle(); action("研究", "Declared methods");
    }
    private byte[] finishExport() throws Exception {
        Uri uri = Uri.parse("content://org.diemobile.test/dex-export"); ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(uri,out); activity.onActivityResult(1,Activity.RESULT_OK,new Intent().setData(uri)); settle();
        assertTrue(status(), status().contains("Export saved")); return out.toByteArray();
    }

    @Test public void pagingSearchDraftAndRecreatedBackPreserveOriginalLocation() throws Exception {
        assertTrue(status(), status().contains("126 matches")); assertEquals(50,records().getAdapter().getCount());
        button("next").performClick(); settle(); assertEquals(50,row(0).index);
        filter().setText("研究"); assertEquals(50,records().getAdapter().getCount()); button("search").performClick(); settle(); assertEquals(1,records().getAdapter().getCount());
        filter().setText("edited but unapplied"); action("研究", "Declared methods");
        controller.recreate(); activity = controller.get(); settle(); button("back").performClick(); settle();
        assertEquals("edited but unapplied",filter().getText().toString()); assertEquals(1,records().getAdapter().getCount()); assertTrue(row(0).name.contains("研究"));
        assertTrue(status(),status().contains("edited search not applied"));
    }

    @Test public void bytecodePayloadSwitchCallerAndDebugRelationshipsUseEngineIndices() throws Exception {
        method(); action("->inspect()V","Method bytecode"); EvidenceRows.Row call = row(position("invoke-static"));
        action("array-payload","All array payload"); assertTrue(status(),status().contains("80 matches")); button("next").performClick(); settle(); assertEquals(50,row(0).index);
        button("back").performClick(); settle(); action("packed-switch-payload","All switch"); assertEquals(2,records().getAdapter().getCount());
        action("7","Target bytecode"); assertEquals("dex-code",ReflectionHelpers.<EvidenceRows>getField(activity,"source").kind);
        action("invoke-static","encoded uses"); assertEquals(1,records().getAdapter().getCount()); assertEquals(call.index,row(0).json().getInt("instructionIndex"));
        action("inspect()V","Method bytecode"); assertTrue(row(position("invoke-static")).name.contains("invoke-static"));
        action("invoke-static","Method debug events"); assertTrue(records().getAdapter().getCount() >= 6);
        action("line 7","Associated bytecode"); assertEquals("dex-code",ReflectionHelpers.<EvidenceRows>getField(activity,"source").kind);
    }

    @Test public void filteredExportCapturesAllRowsAndPageSizeBeforeRecreation() throws Exception {
        Spinner count = ReflectionHelpers.getField(activity,"pageSize"); count.setSelection(1); Shadows.shadowOf(Looper.getMainLooper()).idle(); settle(); assertEquals(10,records().getAdapter().getCount());
        filter().setText("研究"); button("search").performClick(); settle(); button("export").performClick(); settle();
        var started = Shadows.shadowOf(activity).getNextStartedActivityForResult(); assertEquals(Intent.ACTION_CREATE_DOCUMENT,started.intent.getAction());
        Files.write(input.toPath(),new byte[]{1,2,3}); controller.recreate(); activity = controller.get(); settle();
        JSONObject report = new JSONObject(new String(finishExport(),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(126,report.getJSONArray("rows").length()); assertEquals("研究",report.getJSONObject("view").getString("filter")); assertEquals(10,report.getJSONObject("view").getInt("pageSize"));
        assertEquals(64,report.getJSONObject("provenance").getJSONObject("input").getString("sha256").length());
    }

    @Test public void smaliUsesUpstreamRendererAndCompletePreparedText() throws Exception {
        method(); action("->inspect()V","View / export method smali"); AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing()); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        String smali = new String(finishExport(),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(smali.contains("baksmali")); for (String token : Arrays.asList(".method", ".array-data", ".packed-switch", ".catchall", "invoke-static")) assertTrue(token,smali.contains(token));
    }
    @Test public void classifiedDexStringsRetainPoolValuesAndEncodedCallerLinks() throws Exception {
        root("Strings: URLs"); int index = position(DexFixtureFactory.ENDPOINT); JSONObject value = row(index).json();
        assertEquals(DexFixtureFactory.ENDPOINT,value.getString("value")); assertTrue(value.getJSONArray("classificationMatches").length() > 0);
        assertTrue(value.getLong("physicalTextOffset") > row(index).physicalOffset);
        action(DexFixtureFactory.ENDPOINT,"encoded uses"); assertEquals(1,records().getAdapter().getCount());
        action("inspect()V","Method bytecode"); assertEquals("dex-code",ReflectionHelpers.<EvidenceRows>getField(activity,"source").kind);
    }

    @Test public void apkMemberRecreationRetainsInputViewsAndCleansPrivateSnapshot() throws Exception {
        controller.pause().stop().destroy(); File apk = temporary.resolve("app.apk").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(apk))) { out.putNextEntry(new ZipEntry("classes.dex")); out.write(Files.readAllBytes(input.toPath())); out.closeEntry(); }
        open(apk); assertEquals(1,records().getAdapter().getCount()); assertNull(row(0).physicalOffset); action("classes.dex","Inspect this DEX");
        File selected = ReflectionHelpers.getField(activity,"bytes"); assertTrue(selected.isFile()); assertNotEquals(apk,selected);
        controller.recreate(); activity = controller.get(); settle(); root("Choose a DEX member"); assertEquals("dex-inputs",ReflectionHelpers.<EvidenceRows>getField(activity,"source").kind);
        assertEquals(apk,ReflectionHelpers.getField(activity,"bytes")); assertTrue(ReflectionHelpers.<String>getField(activity,"provenance").contains("ZIP"));
        ExecutorService worker = ReflectionHelpers.getField(activity,"worker");
        File owned = ReflectionHelpers.getField(ReflectionHelpers.getField(activity,"backend"),"selected");
        controller.pause().stop().destroy(); controller = null; assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS)); assertFalse(owned.exists()); assertTrue(apk.isFile());
    }

    @Test public void queuedCancellationCanReloadAndPhoneLayoutLeavesResultSpace() throws Exception {
        CountDownLatch release = new CountDownLatch(1); ReflectionHelpers.<ExecutorService>getField(activity,"worker").submit(() -> { try { release.await(30,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        try { button("reload").performClick(); button("cancel").performClick(); } finally { release.countDown(); }
        settle(); assertTrue(status(),status().toLowerCase(Locale.ROOT).contains("cancelled")); assertNull(records().getAdapter()); button("reload").performClick(); settle(); assertEquals(50,records().getAdapter().getCount());
        View decor = activity.getWindow().getDecorView(); int width = Math.round(360 * activity.getResources().getDisplayMetrics().density), height = Math.round(640 * activity.getResources().getDisplayMetrics().density);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); decor.layout(0,0,width,height);
        assertTrue("Result list should retain 160dp at 360x640",records().getHeight() >= Math.round(160 * activity.getResources().getDisplayMetrics().density));
    }
}
