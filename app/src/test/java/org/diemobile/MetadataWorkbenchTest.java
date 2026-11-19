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
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

/** Source-built metadata/XMP plus real Android widgets and staged output streams. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class MetadataWorkbenchTest {
    private ActivityController<InvestigationActivity> controller;
    private InvestigationActivity activity;
    private Path temporary;
    private File input;
    @Before public void fixture()throws Exception {
        temporary=Files.createTempDirectory("die-metadata-ui");input=temporary.resolve("rich.jpg").toFile();Files.write(input.toPath(),MetadataFixtureFactory.jpeg());open();
    }
    private void open()throws Exception {
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),InvestigationActivity.class).putExtra("provider","metadata").putExtra("path",input.getAbsolutePath()).putExtra("name","rich.jpg");
        controller=Robolectric.buildActivity(InvestigationActivity.class,intent).setup().visible();activity=controller.get();settle();
    }
    private void destroy()throws Exception {if(controller!=null){ExecutorService worker=ReflectionHelpers.getField(activity,"worker");controller.pause().stop().destroy();controller=null;assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS));}}
    @After public void close()throws Exception {destroy();if(temporary!=null)try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private void settle()throws Exception {for(int i=0;i<8;i++){ReflectionHelpers.<ExecutorService>getField(activity,"worker").submit(()->{}).get(30,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();if(!ReflectionHelpers.<Boolean>getField(activity,"busy"))return;}fail("Metadata worker did not settle: "+status());}
    private Button button(String field){return ReflectionHelpers.getField(activity,field);}
    private ListView records(){return activity.findViewById(R.id.investigation_records);}
    private EditText filter(){return activity.findViewById(R.id.investigation_filter);}
    private String status(){return ((TextView)activity.findViewById(R.id.investigation_status)).getText().toString();}
    private EvidenceRows.Row row(int i){return (EvidenceRows.Row)records().getAdapter().getItem(i);}
    private void choose(String label){AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ListView list=dialog.getListView();for(int i=0;i<list.getAdapter().getCount();i++)if(list.getAdapter().getItem(i).toString().contains(label)){list.performItemClick(null,i,i);dialog.dismiss();return;}fail("Missing metadata action "+label);}
    private void root(String label)throws Exception {button("views").performClick();choose(label);settle();}
    private void action(int row,String label)throws Exception {records().performItemClick(null,row,row);ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle();choose(label);settle();}
    private void search(String term)throws Exception {filter().setText(term);button("search").performClick();settle();}
    private void icc()throws Exception {search("0x64657363");assertEquals(1,records().getAdapter().getCount());}
    private byte[] finishExport()throws Exception {
        Uri target=Uri.parse("content://org.diemobile.test/metadata-export");ByteArrayOutputStream output=new ByteArrayOutputStream();Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(target,output);
        activity.onActivityResult(1,Activity.RESULT_OK,new Intent().setData(target));settle();assertTrue(status(),status().contains("Export saved"));return output.toByteArray();
    }
    @Test public void rawExifRationalsDirectoryParentsAndUnknownOffsetsStayExplicit()throws Exception {
        assertTrue(ReflectionHelpers.<EvidenceRows>getField(activity,"source").size()>20);search("Exposure Time");assertEquals(1,records().getAdapter().getCount());
        JSONObject raw=row(0).json().getJSONObject("rawValue");assertEquals("1",raw.getString("numerator"));assertEquals("125",raw.getString("denominator"));assertNull(row(0).physicalOffset);
        root("Directory instances");search("GpsDirectory");assertEquals(1,records().getAdapter().getCount());int parent=row(0).json().getInt("parentDirectoryIndex");action(0,"Parent directory tags");
        assertEquals(parent,new JSONObject(ReflectionHelpers.<String>getField(activity,"metadata")).getInt("selectedDirectoryIndex"));
    }
    @Test public void xmpRetainsAllAncestorsQualifiersSearchAndRecreatedBack()throws Exception {
        root("All XMP");assertTrue(ReflectionHelpers.<EvidenceRows>getField(activity,"source").size()>1005);assertEquals(50,records().getAdapter().getCount());button("next").performClick();settle();
        search("ancestor-1004");assertEquals(1,records().getAdapter().getCount());filter().setText("draft not applied");action(0,"Tags in this directory");controller.recreate();activity=controller.get();settle();button("back").performClick();settle();
        assertEquals("draft not applied",filter().getText().toString());assertEquals("ancestor-1004",row(0).json().getString("value"));search("dc:title[1]/xml:lang");assertEquals("qualifier",row(0).json().getString("nodeKind"));
        search("https://diemobile.invalid/ns/1.0/");assertTrue("Decoded namespace field should match despite JSON slash escaping",records().getAdapter().getCount()>0);
    }
    @Test public void selectedRecordExportStreamsCompleteBinaryAndSurvivesInputExpiry()throws Exception {
        icc();assertFalse(row(0).json().getJSONObject("rawValue").getBoolean("previewComplete"));action(0,"Export complete selected record JSON");assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        assertTrue(input.delete());controller.recreate();activity=controller.get();settle();JSONObject exported=new JSONObject(new String(finishExport(),StandardCharsets.UTF_8));
        JSONObject value=exported.getJSONArray("rows").getJSONObject(0).getJSONObject("fullRawValue");assertArrayEquals(MetadataFixtureFactory.iccValue(),Base64.getDecoder().decode(value.getString("base64")));
        assertEquals(64,exported.getJSONObject("provenance").getJSONObject("input").getString("sha256").length());
    }
    @Test public void binaryChunksAndExactValueExportPreserveAllBytes()throws Exception {
        icc();action(0,"Complete value / binary chunks");assertEquals(2,records().getAdapter().getCount());assertEquals(65536,row(1).json().getLong("valueByteOffset"));assertNull(row(1).physicalOffset);
        action(1,"Export complete binary value bytes");Files.write(input.toPath(),new byte[]{0});assertArrayEquals(MetadataFixtureFactory.iccValue(),finishExport());
    }
    @Test public void dtdIsRejectedWithoutLosingOtherMetadataOrReadingReferencedFiles()throws Exception {
        destroy();Path secret=temporary.resolve("secret.txt");Files.write(secret,"DIE_METADATA_MUST_NOT_READ_SENTINEL".getBytes(StandardCharsets.UTF_8));
        String xml="<!DOCTYPE x:xmpmeta [<!ENTITY xxe SYSTEM \""+secret.toUri()+"\">]><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" xmlns:die=\"https://fixture.invalid/\"><die:Value>&xxe;</die:Value></rdf:Description></rdf:RDF></x:xmpmeta>";
        Files.write(input.toPath(),MetadataFixtureFactory.jpeg(xml));open();assertTrue(records().getAdapter().getCount()>0);root("All engine diagnostics");assertTrue(row(0).json().getString("message").contains("DTD declarations are prohibited"));
        assertFalse(row(0).encoded.contains("DIE_METADATA_MUST_NOT_READ_SENTINEL"));root("All XMP");assertEquals(0,records().getAdapter().getCount());
    }
    @Test public void cancellationReloadAndOwnedInputCleanupKeepMetadataUsable()throws Exception {
        ExecutorService worker=ReflectionHelpers.getField(activity,"worker");CountDownLatch release=new CountDownLatch(1);worker.submit(()->{try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        try{button("reload").performClick();button("cancel").performClick();}finally{release.countDown();}settle();assertTrue(status(),status().contains("cancelled"));button("reload").performClick();settle();assertTrue(records().getAdapter().getCount()>0);
        File directory=new File(activity.getCacheDir(),"metadata-snapshots");assertEquals(1,Objects.requireNonNull(directory.list()).length);destroy();assertEquals(0,Objects.requireNonNull(directory.list()).length);assertTrue(input.isFile());
    }
}
