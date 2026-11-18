// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;
import android.app.Activity;
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
import org.robolectric.util.ReflectionHelpers;

/** Actual input/Google string decoding plus the main report staging/picker lifecycle. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ScanExportTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private Path temporary;
    private File input;
    private Inspector.Result original;
    private String exact;
    @Before public void fixture() throws Exception {
        temporary=Files.createTempDirectory("die-scan-export");input=temporary.resolve("manifest.axml").toFile();
        byte[] bytes=ResourceFixtureFactory.xml(true,true);Files.write(input.toPath(),bytes);
        original=Inspector.scan(input,4);
        JSONObject actual=new JSONObject(AndroidResourceProbe.scan(new ByteArrayInputStream(bytes),bytes.length,()->false));
        exact=actual.getJSONObject("tables").getJSONArray("strings").getJSONObject(6).getString("text")+"\0😀";
        controller=Robolectric.buildActivity(MainActivity.class,new Intent(RuntimeEnvironment.getApplication(),MainActivity.class)).setup().visible();activity=controller.get();
        ReflectionHelpers.setField(activity,"result",original);ReflectionHelpers.setField(activity,"scannedOptions",new ScanOptions(ScanOptions.DEFAULT_FLAGS,4,"original-tag",""));
        ReflectionHelpers.setField(activity,"name","captured-"+exact+".axml");ReflectionHelpers.setField(activity,"apkReport",exact);ReflectionHelpers.setField(activity,"guide",exact);
        ReflectionHelpers.setField(activity,"nativeFacts",new JSONObject().put("exact",exact));ReflectionHelpers.setField(activity,"elapsedMs",123L);
        ReflectionHelpers.<Button>getField(activity,"export").setEnabled(true);
    }
    private void settle() throws Exception {
        for(int i=0;i<8;i++) {
            ReflectionHelpers.<ExecutorService>getField(activity,"worker").submit(()->{}).get(30,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(!ReflectionHelpers.<Boolean>getField(activity,"preparingReport")&&!ReflectionHelpers.<Boolean>getField(activity,"writingReport")) return;
        }
        fail("Primary report export did not settle");
    }
    @After public void cleanup() throws Exception {
        if(controller!=null) {
            ExecutorService worker=ReflectionHelpers.getField(activity,"worker");controller.pause().stop().destroy();assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS));
        }
        if(temporary!=null) try(var paths=Files.walk(temporary)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    private PreparedExport prepare() throws Exception {
        ReflectionHelpers.<Button>getField(activity,"export").performClick();settle();
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        PreparedExport captured=ReflectionHelpers.getField(activity,"preparedReport");assertNotNull(captured);assertTrue(captured.file.isFile());
        return captured;
    }
    private String status() { return ReflectionHelpers.<TextView>getField(activity,"status").getText().toString(); }
    @Test public void capturedReportRetainsUnicodeIdentityOptionsAcrossChangesExpiryAndRecreation() throws Exception {
        PreparedExport staged=prepare();byte[] captured=Files.readAllBytes(staged.file.toPath());
        assertTrue(staged.name.contains(original.sha256.substring(0,12)));
        ReflectionHelpers.setField(activity,"name","changed.bin");ReflectionHelpers.setField(activity,"apkReport","changed");
        ReflectionHelpers.setField(activity,"scannedOptions",new ScanOptions(ScanOptions.AGGRESSIVE,7,"changed",""));ReflectionHelpers.setField(activity,"result",null);
        assertTrue(input.delete());controller.recreate();activity=controller.get();settle();
        Uri destination=Uri.parse("content://org.diemobile.test/scan-export");ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(destination,bytes);
        activity.onActivityResult(2,Activity.RESULT_OK,new Intent().setData(destination));settle();
        assertTrue(status(),status().contains("Report saved"));assertArrayEquals(captured,bytes.toByteArray());
        JSONObject report=new JSONObject(bytes.toString(StandardCharsets.UTF_8));
        assertEquals("captured-"+exact+".axml",report.getJSONObject("source").getString("name"));assertEquals(exact,report.getString("apkAnalysis"));
        assertEquals(exact,report.getJSONObject("facts").getString("exact"));assertEquals(original.sha256,report.getJSONObject("source").getString("sha256"));
        assertEquals("original-tag",report.getJSONObject("options").getString("includeTags"));assertEquals(4,report.getJSONObject("options").getInt("minimumStringLength"));
        assertFalse(staged.file.exists());
    }
    @Test public void cancelledPickerDiscardsOnlyThePreparedSnapshotAndAllowsRetry() throws Exception {
        PreparedExport staged=prepare();activity.onActivityResult(2,Activity.RESULT_CANCELED,null);settle();
        assertFalse(staged.file.exists());assertTrue(status(),status().contains("cancelled"));assertTrue(input.isFile());
        assertTrue(ReflectionHelpers.<Button>getField(activity,"export").isEnabled());
        PreparedExport retry=prepare();assertNotEquals(staged.file,retry.file);activity.onActivityResult(2,Activity.RESULT_CANCELED,null);settle();assertFalse(retry.file.exists());
    }
    @Test public void failedDestinationDiscardsTheStageWithoutRegeneratingCurrentData() throws Exception {
        PreparedExport staged=prepare();Uri destination=Uri.parse("content://org.diemobile.test/failing-scan-export");
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(destination,new OutputStream(){
            @Override public void write(int value) throws IOException { throw new IOException("Fixture destination refused bytes"); }
        });
        activity.onActivityResult(2,Activity.RESULT_OK,new Intent().setData(destination));settle();
        assertTrue(status(),status().contains("destination may contain partial data"));assertFalse(staged.file.exists());
        assertTrue(ReflectionHelpers.<Button>getField(activity,"export").isEnabled());
    }
}
