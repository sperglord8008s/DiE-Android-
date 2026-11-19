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
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/** Real linear engine/catalog/DEX acquisition; document-provider streams alone are test doubles. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TrackerWorkbenchTest {
    private ActivityController<InvestigationActivity> controller;
    private InvestigationActivity activity;
    private File snapshot;
    private byte[] original;
    private Path temporary;
    @Before public void fixture() throws Exception {
        temporary=Files.createTempDirectory("die-tracker-ui");
        snapshot=new File(RuntimeEnvironment.getApplication().getFilesDir(),"exodus-trackers.json");
        JSONObject rules=new JSONObject();
        for(int i=0;i<65;i++) rules.put(Integer.toString(i),new JSONObject().put("name",String.format(Locale.ROOT,"Synthetic tracker %04d",i))
            .put("code_signature","org/diemobile/fixture|com/demo"+i).put("network_signature","fixture.invalid")
            .put("future",new JSONObject().put("unicode","fixture-😀-\ud800").put("exponent","__EXACT_EXPONENT_TOKEN__").put("longValue",i==0?"long-context-"+"x".repeat(20000)+"end-of-full-value":"other")));
        rules.put("unsupported",new JSONObject().put("code_signature","(?=fixture)fixture"));
        rules.put("short",new JSONObject().put("code_signature","ab"));rules.put("unknown-record",JSONObject.NULL);
        String text=new JSONObject().put("trackers",rules).put("snapshotVersion",5).put("source",new JSONObject().put("name","Synthetic test data, not an Exodus database")).toString();
        text=text.substring(0,text.length()-1)+",\"futureExactNumber\":9007199254740993123456789,\"futureExponent\":1e400}";
        text=text.replace("\ud800","\\uD800").replace("\"__EXACT_EXPONENT_TOKEN__\"","1e400");original=text.getBytes(StandardCharsets.UTF_8);Files.write(snapshot.toPath(),original);
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),InvestigationActivity.class).putExtra("provider","tracker-rules")
            .putExtra("path",temporary.resolve("expired-selected-input.bin").toString()).putExtra("name","Imported tracker catalog");
        controller=Robolectric.buildActivity(InvestigationActivity.class,intent).setup().visible();activity=controller.get();settle();
    }
    @After public void cleanup() throws Exception {
        if(controller!=null) { ExecutorService worker=ReflectionHelpers.getField(activity,"worker");controller.pause().stop().destroy();assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS)); }
        snapshot.delete();if(temporary!=null) try(var paths=Files.walk(temporary)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    private ListView list() { return activity.findViewById(R.id.investigation_records); }
    private EvidenceRows.Row row(int index) { return (EvidenceRows.Row)list().getAdapter().getItem(index); }
    private Button button(String field) { return ReflectionHelpers.getField(activity,field); }
    private void settle() throws Exception {
        for(int i=0;i<8;i++) {
            ReflectionHelpers.<ExecutorService>getField(activity,"worker").submit(()->{}).get(30,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(!ReflectionHelpers.<Boolean>getField(activity,"busy")) return;
        }
        fail("Tracker work did not settle");
    }
    private void search(String value) throws Exception { ((EditText)activity.findViewById(R.id.investigation_filter)).setText(value);button("search").performClick();settle(); }
    private void choose(String label) {
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ListView values=dialog.getListView();
        for(int i=0;i<values.getAdapter().getCount();i++) if(values.getAdapter().getItem(i).toString().contains(label)) { values.performItemClick(null,i,i);dialog.dismiss();return; }
        fail("Missing tracker action: "+label);
    }
    private void root(String label) throws Exception { button("views").performClick();choose(label);settle(); }
    private byte[] finishExport() throws Exception {
        Uri uri=Uri.parse("content://org.diemobile.test/tracker-export");ByteArrayOutputStream output=new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(uri,output);activity.onActivityResult(1,Activity.RESULT_OK,new Intent().setData(uri));settle();
        assertTrue(((TextView)activity.findViewById(R.id.investigation_status)).getText().toString().contains("Export saved"));return output.toByteArray();
    }
    @Test public void completeCatalogPagesFullValueSearchDiagnosticsMetadataAndBack() throws Exception {
        assertEquals(50,list().getAdapter().getCount());assertEquals(68,ReflectionHelpers.<EvidenceRows>getField(activity,"source").size());
        assertEquals(65,new JSONObject(ReflectionHelpers.<String>getField(activity,"provenance")).getInt("requiredLiteralIndexedRules"));
        assertNull(row(0).physicalOffset);button("next").performClick();settle();assertEquals(50,row(0).index);
        search("end-of-full-value");assertEquals(1,list().getAdapter().getCount());assertTrue(row(0).json().getBoolean("previewTruncated"));
        controller.recreate();activity=controller.get();settle();assertEquals(1,list().getAdapter().getCount());
        root("Unsupported / short");assertEquals(3,list().getAdapter().getCount());
        button("back").performClick();settle();assertEquals(1,list().getAdapter().getCount());
        root("Original snapshot version");search("9007199254740993123456789");assertEquals(1,list().getAdapter().getCount());
        assertTrue(row(0).json().getString("originalJsonPreview").contains("9007199254740993123456789"));
        list().performItemClick(null,0,0);ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle();
        choose("Export complete selected record JSON");settle();
        assertTrue(new String(finishExport(),StandardCharsets.UTF_8).contains("\"fullOriginalRecord\":9007199254740993123456789"));
    }
    @Test public void completeFilteredExportRetainsExactRecordsAcrossReplacementAndRecreation() throws Exception {
        search("end-of-full-value");button("export").performClick();settle();
        PreparedExport prepared=ReflectionHelpers.getField(activity,"prepared");byte[] staged=Files.readAllBytes(prepared.file.toPath());
        Files.write(snapshot.toPath(),"{\"trackers\":{}}".getBytes(StandardCharsets.UTF_8));controller.recreate();activity=controller.get();settle();
        byte[] exported=finishExport();assertArrayEquals(staged,exported);String exactJson=new String(exported,StandardCharsets.UTF_8);
        assertTrue(exactJson.contains("\"exponent\":1e400"));
        // Android's JSON reader rejects this valid out-of-double-range number.
        // Check its exact token above, then mask only the known raw numeric field
        // to let that reader inspect unrelated row/Unicode ownership assertions.
        JSONObject result=new JSONObject(exactJson.replace("\"exponent\":1e400","\"exponent\":\"__EXACT_EXPONENT_TOKEN__\""));
        assertEquals(68,result.getJSONArray("rows").length());assertEquals(TrackerCatalog.REFERENCE,result.getJSONObject("provenance").getString("upstreamContractRevision"));
        boolean found=false;for(int i=0;i<68;i++) { JSONObject record=result.getJSONArray("rows").getJSONObject(i);if(record.getString("snapshotKey").equals("0")) {
            String value=record.getJSONObject("fullOriginalRecord").getJSONObject("future").getString("longValue");assertTrue(value.endsWith("end-of-full-value"));assertTrue(value.length()>20000);found=true;
            assertEquals("fixture-😀-\ud800",record.getJSONObject("fullOriginalRecord").getJSONObject("future").getString("unicode"));
        }}assertTrue(found);
    }
    @Test public void exactSnapshotBytesSurviveDeletedImportAndPendingPickerRecreation() throws Exception {
        root("Export exact imported JSON");assertTrue(ReflectionHelpers.<Boolean>getField(activity,"pickerOpen"));
        assertTrue(snapshot.delete());controller.recreate();activity=controller.get();settle();
        assertArrayEquals(original,finishExport());
    }
    @Test public void actualDexAndArchiveReportsUseOriginalSlashNamesAndAllMatchingRules() throws Exception {
        File dex=temporary.resolve("sample.dex").toFile();Files.write(dex.toPath(),DexFixtureFactory.rich());
        DexDocument document=DexDocument.read(dex,()->false);assertTrue(document.definedClassDescriptors(()->false).contains(DexFixtureFactory.OWNER));
        KnowledgeReport.Output report=KnowledgeReport.analyze(RuntimeEnvironment.getApplication(),dex,Inspector.scan(dex,4),"sample.dex");
        String text=report.apk.toString();assertTrue(text,text.contains("65 matching rules"));assertTrue(text,text.contains("original descriptor "+DexFixtureFactory.OWNER));
        assertTrue(text,text.contains("PARTIAL tracker search")&&text.contains("unsupported rules"));assertTrue(text,text.contains("fixture.invalid"));assertFalse(text,text.contains("literal signature matches"));
        assertTrue(text,text.contains("65 actual regex/class comparisons")&&text.contains("65 indexed rules"));
        File zip=temporary.resolve("sample.zip").toFile();try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("classes.dex"));output.write(Files.readAllBytes(dex.toPath()));output.closeEntry();
        }
        text=KnowledgeReport.analyze(RuntimeEnvironment.getApplication(),zip,Inspector.scan(zip,4),"sample.zip").apk.toString();
        assertTrue(text,text.contains("65 matching rules")&&text.contains("regex-search association"));
    }
    @Test public void actualOptionsImportRejectsDuplicatesWithoutReplacingPriorSnapshot() throws Exception {
        ActivityController<MainActivity> main=Robolectric.buildActivity(MainActivity.class).setup().visible();MainActivity screen=main.get();
        ExecutorService worker=ReflectionHelpers.getField(screen,"worker");
        try {
            Uri uri=Uri.parse("content://org.diemobile.test/tracker-import");
            Shadows.shadowOf(screen.getContentResolver()).registerInputStream(uri,new ByteArrayInputStream("{\"trackers\":{},\"trackers\":{}}".getBytes(StandardCharsets.UTF_8)));
            ReflectionHelpers.callInstanceMethod(screen,"importExodus",ReflectionHelpers.ClassParameter.from(Uri.class,uri));
            worker.submit(()->{}).get(30,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertArrayEquals(original,Files.readAllBytes(snapshot.toPath()));assertTrue(ShadowToast.getTextOfLatestToast().contains("previous snapshot retained"));
        } finally { main.pause().stop().destroy();assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS)); }
    }
}
