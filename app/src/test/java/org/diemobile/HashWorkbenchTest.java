// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;
import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

/** Actual native digest contexts and Android widgets; OS transport alone is replaced. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk=28,shadows=HashWorkbenchTest.HostWorkerTransport.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class HashWorkbenchTest {
    @Implements(value=BinaryWorkerClient.class,isInAndroidSdk=false)
    public static final class HostWorkerTransport {
        @Implementation public String scan(File input,BooleanSupplier stop) throws Exception {
            EvidenceRows.check(stop);
            try(RandomAccessFile reader=new RandomAccessFile(input,"r")) {
                Field field=FileDescriptor.class.getDeclaredField("fd");field.setAccessible(true);
                String result=HashEngine.scanFd(field.getInt(reader.getFD()));EvidenceRows.check(stop);return result;
            }
        }
    }
    private ActivityController<InvestigationActivity> controller;
    private InvestigationActivity activity;
    private Path temporary;
    private File input;
    @Before public void fixture() throws Exception {
        assertTrue("Actual hash JNI is required",HashEngine.AVAILABLE);
        temporary=Files.createTempDirectory("die-hash-ui");input=temporary.resolve("abc.bin").toFile();
        Files.write(input.toPath(),new byte[]{'a','b','c'});
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),InvestigationActivity.class)
            .putExtra("provider","hashes").putExtra("path",input.getAbsolutePath()).putExtra("name",input.getName());
        controller=Robolectric.buildActivity(InvestigationActivity.class,intent).setup().visible();activity=controller.get();settle(activity);
    }
    private void destroy() throws Exception {
        if(controller==null) return;
        ExecutorService worker=ReflectionHelpers.getField(activity,"worker");controller.pause().stop().destroy();controller=null;
        assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS));
    }
    @After public void cleanup() throws Exception {
        destroy();
        if(temporary!=null) try(var paths=Files.walk(temporary)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private static void settle(Activity target) throws Exception {
        for(int i=0;i<8;i++) {
            ReflectionHelpers.<ExecutorService>getField(target,"worker").submit(()->{}).get(30,TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(!ReflectionHelpers.<Boolean>getField(target,"busy")) return;
        }
        fail("Hash work did not settle");
    }
    private Button button(String name) { return ReflectionHelpers.getField(activity,name); }
    private ListView list() { return activity.findViewById(R.id.investigation_records); }
    private EvidenceRows.Row row(int index) { return (EvidenceRows.Row)list().getAdapter().getItem(index); }
    private void search(String query) throws Exception {
        ((EditText)activity.findViewById(R.id.investigation_filter)).setText(query);button("search").performClick();settle(activity);
    }
    private byte[] finishExport() throws Exception {
        Uri uri=Uri.parse("content://org.diemobile.test/hash-export");ByteArrayOutputStream output=new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(uri,output);
        activity.onActivityResult(1,Activity.RESULT_OK,new Intent().setData(uri));settle(activity);
        assertTrue(((TextView)activity.findViewById(R.id.investigation_status)).getText().toString().contains("Export saved"));return output.toByteArray();
    }
    @Test public void allEightActualDigestsExposeExactValuesSourcesAndNoInventedByteOffsets() throws Exception {
        assertEquals(8,list().getAdapter().getCount());assertEquals("MD4",row(1).name);
        assertEquals("a448017aaf21d8525fc10ae87aa6729d",row(1).json().getString("hex"));
        assertEquals(HashDocument.RHASH_SOURCE,row(1).json().getString("sourceRevision"));
        assertNull(row(1).physicalOffset);assertNull(row(1).sizeBytes);
        search("SHA512");assertEquals(1,list().getAdapter().getCount());assertEquals(128,row(0).json().getString("hex").length());
        assertEquals(HashDocument.XXFCLIB_SOURCE,row(0).json().getString("sourceRevision"));
        assertTrue(row(0).json().getBoolean("wholeInputComplete"));
    }
    @Test public void fullFilteredDigestExportSurvivesSourceExpiryAndRecreation() throws Exception {
        search("SHA512");button("export").performClick();settle(activity);
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        assertTrue(input.delete());controller.recreate();activity=controller.get();settle(activity);
        JSONObject report=new JSONObject(new String(finishExport(),StandardCharsets.UTF_8));
        assertEquals(8,report.getJSONArray("rows").length());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",report.getJSONObject("provenance").getJSONObject("input").getString("sha256"));
        assertEquals(1,report.getJSONObject("provenance").getInt("passes"));
    }
    @Test public void nativeToolsHashEntryUsesEightAlgorithmsSelectorsAndFreshExportIdentity() throws Exception {
        destroy();
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),NativeToolsActivity.class)
            .putExtra("path",input.getAbsolutePath()).putExtra("name","abc.bin").putExtra("tool",7).putExtra("sha256","0".repeat(64));
        ActivityController<NativeToolsActivity> nativeController=Robolectric.buildActivity(NativeToolsActivity.class,intent).setup().visible();
        NativeToolsActivity nativeActivity=nativeController.get();ExecutorService worker=ReflectionHelpers.getField(nativeActivity,"worker");
        try {
            settle(nativeActivity);String text=((TextView)nativeActivity.findViewById(R.id.native_output)).getText().toString();
            assertTrue(text,text.contains("SHA224:")&&text.contains("SHA512:")&&text.contains("MD4: a448017aaf21d8525fc10ae87aa6729d"));
            ReflectionHelpers.<EditText>getField(nativeActivity,"selector").setText("SHA-512");
            ReflectionHelpers.<CheckBox>getField(nativeActivity,"json").setChecked(true);
            ReflectionHelpers.<Button>getField(nativeActivity,"reload").performClick();settle(nativeActivity);
            JSONObject selected=new JSONObject(((TextView)nativeActivity.findViewById(R.id.native_output)).getText().toString());
            assertEquals(1,selected.getJSONObject("data").getJSONObject("Hash").length());
            assertEquals(128,selected.getJSONObject("data").getJSONObject("Hash").getString("SHA512").length());
            ReflectionHelpers.<Button>getField(nativeActivity,"export").performClick();settle(nativeActivity);
            Uri uri=Uri.parse("content://org.diemobile.test/native-hash-export");ByteArrayOutputStream output=new ByteArrayOutputStream();
            Shadows.shadowOf(nativeActivity.getContentResolver()).registerOutputStream(uri,output);
            nativeActivity.onActivityResult(1,Activity.RESULT_OK,new Intent().setData(uri));settle(nativeActivity);
            JSONObject exported=new JSONObject(output.toString(StandardCharsets.UTF_8));
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",exported.getJSONObject("input").getString("sha256"));
            assertTrue(exported.getJSONObject("input").getString("identitySource").contains("independently checked"));
            assertTrue(exported.getString("engineRevision").contains(HashDocument.RHASH_SOURCE));
            assertEquals(1,new JSONObject(exported.getString("rawReport")).getJSONObject("data").getJSONObject("Hash").length());
        } finally {
            nativeController.pause().stop().destroy();assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS));
        }
    }
}
