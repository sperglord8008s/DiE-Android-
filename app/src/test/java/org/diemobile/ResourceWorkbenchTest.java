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
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

/** Real Google parser and Android widgets; only OS worker transport is substituted. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk=28,shadows=ResourceWorkbenchTest.HostWorkerTransport.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ResourceWorkbenchTest {
    @Implements(value=BinaryWorkerClient.class,isInAndroidSdk=false)
    public static final class HostWorkerTransport {
        @Implementation public String scan(File input,BooleanSupplier stop) throws Exception {
            try(InputStream source=new FileInputStream(input)) {
                return AndroidResourceProbe.scan(source,input.length(),stop);
            }
        }
    }
    private ActivityController<InvestigationActivity> controller;
    private InvestigationActivity activity;
    private Path temporary;
    private File input;
    @Before public void fixture() throws Exception {
        temporary=Files.createTempDirectory("die-resource-ui");input=temporary.resolve("fixture.apk").toFile();
        try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(input))) {
            member(output,"AndroidManifest.xml",ResourceFixtureFactory.xml(true,true));
            member(output,"resources.arsc",ResourceFixtureFactory.table());
            member(output,"elsewhere/long.xml",ResourceFixtureFactory.pagedXml());
            member(output,"res/xml/plain.xml","<plain/>".getBytes(StandardCharsets.UTF_8));
        }
        open();
    }
    private static void member(ZipOutputStream output,String name,byte[] bytes) throws Exception {
        output.putNextEntry(new ZipEntry(name));output.write(bytes);output.closeEntry();
    }
    private void open() throws Exception {
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),InvestigationActivity.class)
            .putExtra("provider","resources").putExtra("path",input.getAbsolutePath()).putExtra("name",input.getName());
        controller=Robolectric.buildActivity(InvestigationActivity.class,intent).setup().visible();activity=controller.get();settle();
    }
    private void destroy() throws Exception {
        if(controller==null) return;
        ExecutorService worker=ReflectionHelpers.getField(activity,"worker");
        controller.pause().stop().destroy();controller=null;
        assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS));
    }
    @After public void cleanup() throws Exception {
        destroy();
        if(temporary!=null) try(var paths=Files.walk(temporary)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private String status() { return ((TextView)activity.findViewById(R.id.investigation_status)).getText().toString(); }
    private Button button(String field) { return ReflectionHelpers.getField(activity,field); }
    private ListView list() { return activity.findViewById(R.id.investigation_records); }
    private EvidenceRows.Row row(int index) { return (EvidenceRows.Row)list().getAdapter().getItem(index); }
    private void settle() throws Exception {
        for(int i=0;i<8;i++) {
            ReflectionHelpers.<ExecutorService>getField(activity,"worker").submit(()->{}).get(30,TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(!ReflectionHelpers.<Boolean>getField(activity,"busy")) return;
        }
        fail("Resource work did not settle: "+status());
    }
    private void search(String query) throws Exception {
        ((EditText)activity.findViewById(R.id.investigation_filter)).setText(query);button("search").performClick();settle();
    }
    private void choose(String label) {
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ListView items=dialog.getListView();
        for(int i=0;i<items.getAdapter().getCount();i++) if(items.getAdapter().getItem(i).toString().contains(label)) {
            items.performItemClick(null,i,i);dialog.dismiss();return;
        }
        fail("Missing resource action: "+label);
    }
    private void root(String label) throws Exception { button("views").performClick();choose(label);settle(); }
    private void action(int index,String label) throws Exception {
        list().performItemClick(null,index,index);
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();choose(label);settle();
    }
    private byte[] finishExport() throws Exception {
        Uri uri=Uri.parse("content://org.diemobile.test/resource-export");ByteArrayOutputStream output=new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(uri,output);
        activity.onActivityResult(1,Activity.RESULT_OK,new Intent().setData(uri));settle();
        assertTrue(status(),status().contains("Export saved"));return output.toByteArray();
    }
    private void selectMember(String name) throws Exception {
        root("Choose a compiled");search(name);assertEquals(1,list().getAdapter().getCount());
        action(0,"Inspect this compiled resource snapshot");
    }
    @Test public void duplicateAttributesLinkToExactStringsAndKeepBackState() throws Exception {
        assertEquals(2,list().getAdapter().getCount());
        action(0,"Every stored attribute");assertEquals(2,list().getAdapter().getCount());
        assertEquals(row(0).name,row(1).name);
        assertEquals("Fixture Ω 東京 😀",row(0).json().getJSONObject("value").getString("text"));
        assertEquals("embedded\0value",row(1).json().getString("rawValue"));
        assertTrue(row(0).physicalOffset>0);assertEquals(Long.valueOf(20),row(0).sizeBytes);
        action(1,"Referenced complete pool string");assertEquals(1,list().getAdapter().getCount());
        assertEquals("embedded\0value",row(0).json().getString("text"));
        controller.recreate();activity=controller.get();settle();button("back").performClick();settle();
        assertEquals(2,list().getAdapter().getCount());assertEquals("message",row(1).name);
    }
    @Test public void configurationsAliasesCompactAndDuplicateBagMapsRemainNavigable() throws Exception {
        selectMember("resources.arsc");assertEquals(9,list().getAdapter().getCount());
        action(0,"Definitions sharing this resource ID");assertEquals(5,list().getAdapter().getCount());
        root("offsets •");assertEquals(11,list().getAdapter().getCount());
        search("4294967295");assertEquals(1,list().getAdapter().getCount());assertTrue(row(0).json().getBoolean("absent"));
        root("maps •");assertEquals(2,list().getAdapter().getCount());assertTrue(row(1).json().getBoolean("duplicateKey"));
        assertEquals("Second value",row(1).json().getJSONObject("value").getString("text"));
        root("types •");assertEquals(5,list().getAdapter().getCount());assertEquals(35,row(0).json().getJSONObject("configuration").getInt("sdkVersion"));
        root("entries •");search("compact"); // every row exposes the compact flag; select true via its value below.
        int compact=-1;for(int i=0;i<list().getAdapter().getCount();i++) if(row(i).json().getBoolean("compact")) compact=i;
        assertTrue(compact>=0);assertEquals(Long.valueOf(8),row(compact).sizeBytes);
    }
    @Test public void fullValueSearchPagingAndFilteredExportPreserveEveryStoredString() throws Exception {
        selectMember("long.xml");root("strings •");assertEquals(50,list().getAdapter().getCount());
        button("next").performClick();settle();assertEquals(50,row(0).index);
        controller.recreate();activity=controller.get();settle();assertEquals(50,row(0).index);
        search("full-search-tail");assertEquals(1,list().getAdapter().getCount());assertEquals(1500,row(0).index);
        assertFalse(row(0).json().getString("text").contains("full-search-tail"));
        button("export").performClick();settle();
        JSONObject report=new JSONObject(new String(finishExport(),StandardCharsets.UTF_8));
        assertEquals(2051,report.getJSONArray("rows").length());
        assertTrue(report.getJSONArray("rows").getJSONObject(1500).getJSONObject("fullNativeRecord").getString("text").endsWith("full-search-tail"));
    }
    @Test public void exactCodeUnitsSurvivePickerDelayInputExpiryAndRecreation() throws Exception {
        root("strings •");search("isolated-");assertEquals(1,list().getAdapter().getCount());
        action(0,"Export decoded UTF-16LE code units");
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        assertTrue(input.delete());controller.recreate();activity=controller.get();settle();
        byte[] actual=finishExport();assertEquals(20,actual.length);
        assertEquals(0,actual[18]);assertEquals((byte)0xd8,actual[19]);
        assertEquals("isolated-",new String(Arrays.copyOf(actual,18),StandardCharsets.UTF_16LE));
    }
    @Test public void failedCandidateKeepsOwnedParentAndValidPreviousSelection() throws Exception {
        Files.write(input.toPath(),new byte[]{0});
        selectMember("plain.xml");assertTrue(status(),status().contains("failed"));
        button("back").performClick();settle();assertEquals(1,list().getAdapter().getCount());assertEquals("res/xml/plain.xml",row(0).name);
        search("");assertEquals(4,list().getAdapter().getCount());
        search("resources.arsc");action(0,"Inspect this compiled resource snapshot");assertEquals(9,list().getAdapter().getCount());
        File cache=new File(activity.getCacheDir(),"resource-snapshots");assertEquals(2,Objects.requireNonNull(cache.list()).length);
        destroy();assertEquals(0,Objects.requireNonNull(cache.list()).length);
    }
}
