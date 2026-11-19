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

/** Real libmpv and Android widgets; only OS worker transport is substituted. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk=28, shadows=MpvWorkbenchTest.HostWorkerTransport.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class MpvWorkbenchTest {
    @Implements(value=BinaryWorkerClient.class,isInAndroidSdk=false)
    public static final class HostWorkerTransport {
        @Implementation public String scan(File input, BooleanSupplier stop) throws Exception {
            EvidenceRows.check(stop);
            try (RandomAccessFile source = new RandomAccessFile(input,"r")) {
                Field field = FileDescriptor.class.getDeclaredField("fd"); field.setAccessible(true);
                String report = MpvEngine.scanFd(field.getInt(source.getFD()));
                EvidenceRows.check(stop);
                return report;
            }
        }
    }
    private ActivityController<InvestigationActivity> controller;
    private InvestigationActivity activity;
    private Path temporary;
    private File input;
    @Before public void fixture() throws Exception {
        assertTrue("Real host MPV JNI is required: "+MpvEngine.ERROR,MpvEngine.AVAILABLE);
        temporary = Files.createTempDirectory("die-mpv-ui");
        input = temporary.resolve("tagged.wav").toFile();
        Files.write(input.toPath(),MetadataFixtureFactory.wav());
        open();
    }
    private void open() throws Exception {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(),InvestigationActivity.class)
            .putExtra("provider","mpv").putExtra("path",input.getAbsolutePath()).putExtra("name",input.getName());
        controller = Robolectric.buildActivity(InvestigationActivity.class,intent).setup().visible();
        activity = controller.get(); settle();
    }
    private void destroy() throws Exception {
        if (controller == null) return;
        ExecutorService worker = ReflectionHelpers.getField(activity,"worker");
        controller.pause().stop().destroy(); controller = null;
        assertTrue(worker.awaitTermination(30,TimeUnit.SECONDS));
    }
    @After public void cleanup() throws Exception {
        destroy();
        if (temporary != null) try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private String status() { return ((TextView)activity.findViewById(R.id.investigation_status)).getText().toString(); }
    private Button button(String field) { return ReflectionHelpers.getField(activity,field); }
    private ListView list() { return activity.findViewById(R.id.investigation_records); }
    private EvidenceRows.Row row(int index) { return (EvidenceRows.Row)list().getAdapter().getItem(index); }
    private void settle() throws Exception {
        for (int i = 0; i < 8; i++) {
            ReflectionHelpers.<ExecutorService>getField(activity,"worker").submit(()->{}).get(30,TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!ReflectionHelpers.<Boolean>getField(activity,"busy")) return;
        }
        fail("MPV work did not settle: "+status());
    }
    private void search(String term) throws Exception {
        ((EditText)activity.findViewById(R.id.investigation_filter)).setText(term);
        button("search").performClick(); settle();
    }
    private void choose(String label) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView items = dialog.getListView();
        for (int i = 0; i < items.getAdapter().getCount(); i++) if (items.getAdapter().getItem(i).toString().contains(label)) {
            items.performItemClick(null,i,i); dialog.dismiss(); return;
        }
        fail("Missing MPV action: "+label);
    }
    private void root(String label) throws Exception { button("views").performClick(); choose(label); settle(); }
    private void action(int index, String label) throws Exception {
        list().performItemClick(null,index,index);
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle(); choose(label); settle();
    }
    private byte[] finishExport() throws Exception {
        Uri uri = Uri.parse("content://org.diemobile.test/mpv-export");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(uri,output);
        activity.onActivityResult(1,Activity.RESULT_OK,new Intent().setData(uri)); settle();
        assertTrue(status(),status().contains("Export saved")); return output.toByteArray();
    }
    @Test public void realContainerAndTypedTrackValuesHaveReferencesAndNoInventedOffsets() throws Exception {
        assertTrue(status(),list().getAdapter().getCount()>20);
        search("file-format"); assertEquals("wav",row(0).json().getJSONObject("nodePreview").getString("value"));
        assertNull(row(0).physicalOffset);
        search("track-list"); action(0,"Open complete typed value");
        assertEquals(1,list().getAdapter().getCount()); action(0,"Open complete typed value");
        search("demux-samplerate");
        assertEquals(1,list().getAdapter().getCount());
        assertEquals("demux-samplerate",row(0).name);
        assertEquals(44100,row(0).json().getJSONObject("nodePreview").getInt("value"));
        assertTrue(row(0).json().getJSONObject("reference").getBoolean("documentationAvailable"));
    }
    @Test public void stagedMetadataBytesSurviveInputExpiryAndActivityRecreation() throws Exception {
        search("metadata");
        int selected=-1;
        for (int i=0;i<list().getAdapter().getCount();i++) if (row(i).name.equals("metadata")) selected=i;
        assertTrue(selected>=0); action(selected,"Open complete typed value"); search("Fixture tone");
        assertEquals(1,list().getAdapter().getCount()); action(0,"Export exact engine-supplied UTF-8");
        assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        assertTrue(input.delete()); controller.recreate(); activity=controller.get(); settle();
        assertEquals("Fixture tone",new String(finishExport(),StandardCharsets.UTF_8));
    }
    @Test public void decoderCatalogPagesAndExportsTheEntireCapturedProperty() throws Exception {
        root("Codec / container capabilities"); search("decoder-list"); action(0,"Open complete typed value");
        int size=ReflectionHelpers.<EvidenceRows>getField(activity,"source").size();
        assertTrue(size>100); assertEquals(50,list().getAdapter().getCount());
        button("next").performClick(); settle(); assertEquals(50,row(0).index);
        controller.recreate(); activity=controller.get(); settle(); assertEquals(50,row(0).index);
        search("not a decoder"); button("export").performClick(); settle();
        JSONObject exported = new JSONObject(new String(finishExport(),StandardCharsets.UTF_8));
        assertEquals(size,exported.getJSONArray("rows").length());
        assertTrue(exported.getJSONArray("rows").getJSONObject(0).has("fullNativeNode"));
    }
    @Test public void unsupportedInputAndUnavailablePropertiesRemainExplicitAndRecoverable() throws Exception {
        root("Unavailable properties"); assertTrue(list().getAdapter().getCount()>0);
        for(int i=0;i<list().getAdapter().getCount();i++) assertTrue(row(i).json().getInt("errorCode")<0);
        destroy(); Files.write(input.toPath(),new byte[]{1,2,3,4}); open();
        assertTrue(ReflectionHelpers.<TextView>getField(activity,"context").getText().toString().contains("UNSUPPORTED / INCOMPLETE"));
        assertEquals(android.view.View.GONE,ReflectionHelpers.<TextView>getField(activity,"mechanism").getVisibility());
        root("Input / engine / process"); assertFalse(row(0).json().getBoolean("fileProbeComplete"));
        destroy(); Files.write(input.toPath(),MetadataFixtureFactory.wav()); open();
        File snapshots = new File(activity.getCacheDir(),"media-snapshots");
        assertEquals(1,Objects.requireNonNull(snapshots.list()).length); destroy(); assertEquals(0,Objects.requireNonNull(snapshots.list()).length);
    }
}
