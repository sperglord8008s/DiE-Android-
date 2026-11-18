// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;
import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
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

/** Real engine/data/ZIP acquisition and widgets; only destination streams are substituted. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DexDefinitionsWorkbenchTest {
    private Path temporary;
    private File input;
    private byte[] ownerB;
    private ActivityController<InvestigationActivity> controller;
    private InvestigationActivity activity;
    @Before public void fixture() throws Exception {
        temporary=Files.createTempDirectory("die-multidex-ui");input=temporary.resolve("definitions.apk").toFile();ownerB=DexFixtureFactory.definitionOwner("Owner-B.smali");
        try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(input))) {
            LinkedHashMap<String,byte[]> members=new LinkedHashMap<>();members.put("readme.txt",new byte[]{1});members.put("classes.dex",DexFixtureFactory.definitionConsumer());
            members.put("classes2.dex",DexFixtureFactory.definitionOwner("Owner-A.smali"));members.put("assets/extra.DEX",ownerB);members.put("assets/many.dex",DexFixtureFactory.rich());
            for(Map.Entry<String,byte[]> member:members.entrySet()) { output.putNextEntry(new ZipEntry(member.getKey()));output.write(member.getValue());output.closeEntry(); }
        }
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),InvestigationActivity.class).putExtra("provider","dex").putExtra("path",input.getAbsolutePath()).putExtra("name",input.getName());
        controller=Robolectric.buildActivity(InvestigationActivity.class,intent).setup().visible();activity=controller.get();settle();action("classes.dex","Inspect this DEX");
    }
    @After public void cleanup() throws Exception {
        if(controller!=null) { ExecutorService worker=ReflectionHelpers.getField(activity,"worker");controller.pause().stop().destroy();assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS)); }
        if(temporary!=null) try(var paths=Files.walk(temporary)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    private ListView list() { return activity.findViewById(R.id.investigation_records); }
    private EvidenceRows.Row row(int i) { return (EvidenceRows.Row)list().getAdapter().getItem(i); }
    private Button button(String field) { return ReflectionHelpers.getField(activity,field); }
    private String status() { return ((TextView)activity.findViewById(R.id.investigation_status)).getText().toString(); }
    private void settle() throws Exception {
        for(int i=0;i<8;i++) {
            ReflectionHelpers.<ExecutorService>getField(activity,"worker").submit(()->{}).get(30,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(!ReflectionHelpers.<Boolean>getField(activity,"busy")) return;
        }
        fail("Definition operation did not settle: "+status());
    }
    private void choose(String name) {
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ListView choices=dialog.getListView();
        for(int i=0;i<choices.getAdapter().getCount();i++) if(choices.getAdapter().getItem(i).toString().contains(name)) { choices.performItemClick(null,i,i);dialog.dismiss();return; }
        fail("Missing definition action "+name);
    }
    private void action(String name,String label) throws Exception {
        for(int i=0;i<list().getAdapter().getCount();i++) if(row(i).name.contains(name)) {
            list().performItemClick(null,i,i);ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle();choose(label);settle();return;
        }
        fail("Missing definition row "+name+" / "+status());
    }
    private void root(String name) throws Exception { button("views").performClick();choose(name);settle(); }
    private void search(String name) throws Exception { ((EditText)activity.findViewById(R.id.investigation_filter)).setText(name);button("search").performClick();settle(); }
    private void methodDefinitions() throws Exception {
        root("Method references");search(DexFixtureFactory.SHARED_OWNER+"->value(I)I");
        action("value(I)I","exact declarations across captured");assertEquals(2,list().getAdapter().getCount());
    }
    private byte[] finishExport() throws Exception {
        Uri uri=Uri.parse("content://org.diemobile.test/definition-export");ByteArrayOutputStream output=new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(uri,output);activity.onActivityResult(1,Activity.RESULT_OK,new Intent().setData(uri));settle();
        assertTrue(status(),status().contains("Export saved"));return output.toByteArray();
    }
    @Test public void realCrossDexAmbiguityOpensCorrectOwnedMethodAfterParentExpiry() throws Exception {
        methodDefinitions();assertNull(row(0).physicalOffset);assertEquals(2,row(0).json().getInt("targetArchiveMemberIndex"));assertEquals(3,row(1).json().getInt("targetArchiveMemberIndex"));
        String expected=row(0).json().getString("targetDexSha256");assertTrue(input.delete());
        action("value(I)I","Open this exact captured declaration");
        JSONObject provenance=new JSONObject(ReflectionHelpers.<String>getField(activity,"provenance"));assertEquals(expected,provenance.getJSONObject("input").getString("sha256"));
        EvidenceRows.Page shown=ReflectionHelpers.getField(activity,"shown");assertTrue(shown.rows.stream().anyMatch(value->value.name.endsWith("value(I)I")&&value.physicalOffset!=null));
        action("value(I)I","Method bytecode");assertTrue(list().getAdapter().getCount()>0);assertTrue(row(0).json().has("opcode"));
        button("back").performClick();settle();button("back").performClick();settle();assertEquals(2,list().getAdapter().getCount());
        assertNull(row(0).physicalOffset);assertTrue(new JSONObject(ReflectionHelpers.<String>getField(activity,"metadata")).getJSONObject("lookup").getBoolean("completeInSelectedScopes"));
    }
    @Test public void globalClassPagesSourceSearchAndBackSurviveRecreation() throws Exception {
        root("Class definitions across all");assertEquals(129,ReflectionHelpers.<EvidenceRows>getField(activity,"source").size());assertEquals(50,list().getAdapter().getCount());
        button("next").performClick();settle();assertEquals(50,row(0).index);
        root("Captured multidex sources");assertEquals(4,list().getAdapter().getCount());controller.recreate();activity=controller.get();settle();
        button("back").performClick();settle();assertEquals(50,row(0).index);
        search("classes2.dex");assertEquals(1,list().getAdapter().getCount());assertEquals(DexFixtureFactory.SHARED_OWNER,row(0).name);
        controller.recreate();activity=controller.get();settle();assertEquals(1,list().getAdapter().getCount());assertEquals("classes2.dex",row(0).json().getString("targetSourceNamePreview"));
        root("Captured multidex sources");assertEquals(4,list().getAdapter().getCount());assertTrue(row(0).json().getString("sha256").matches("[0-9a-f]{64}"));
        button("back").performClick();settle();assertEquals(1,list().getAdapter().getCount());assertEquals("classes2.dex",((EditText)activity.findViewById(R.id.investigation_filter)).getText().toString());
    }
    @Test public void filteredCompleteDefinitionExportSurvivesSourceDeletionAndPickerRecreation() throws Exception {
        methodDefinitions();search("classes2.dex");assertEquals(1,list().getAdapter().getCount());button("export").performClick();settle();
        PreparedExport pending=ReflectionHelpers.getField(activity,"prepared");byte[] captured=Files.readAllBytes(pending.file.toPath());
        assertTrue(input.delete());controller.recreate();activity=controller.get();settle();assertArrayEquals(captured,finishExport());
        JSONObject report=new JSONObject(new String(captured,StandardCharsets.UTF_8));assertEquals(2,report.getJSONArray("rows").length());
        assertTrue(report.getJSONObject("metadata").getJSONObject("lookup").getBoolean("completeInSelectedScopes"));
        assertEquals("classes2.dex",report.getJSONObject("view").getString("filter"));
    }
    @Test public void exactAcquiredSourceExportUsesStoredIdentityAfterParentDeletion() throws Exception {
        root("Captured multidex sources");action("assets/extra.DEX","Export exact acquired DEX snapshot");
        assertTrue(input.delete());controller.recreate();activity=controller.get();settle();assertArrayEquals(ownerB,finishExport());
    }
}
