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
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

/** Android widgets/lifecycle plus the real host JNI; not an on-device test. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativeWorkbenchTest {
    private ActivityController<NativeToolsActivity> controller;
    private NativeToolsActivity activity;
    private Path temporary;
    private File input;

    @Before public void createTemporaryInputDirectory() throws Exception {
        assertTrue("Build host JNI with scripts/test-workbench-ui.sh", Engine.AVAILABLE);
        temporary = Files.createTempDirectory("die-android-ui");
    }
    @After public void close() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        if (temporary != null) try (var files = Files.walk(temporary)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private void open(String fixture, int tool) throws Exception {
        openWithoutWaiting(fixture, tool); settle();
    }
    private void openWithoutWaiting(String fixture, int tool) throws Exception {
        Path original = Path.of(System.getProperty("die.fixture.dir"), fixture);
        assertTrue("Generate UI fixtures with scripts/test-workbench-ui.sh: " + original, Files.isRegularFile(original));
        input = temporary.resolve(fixture).toFile(); Files.copy(original, input.toPath());
        String digest = Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input.toPath())));
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), NativeToolsActivity.class)
            .putExtra("path", input.getAbsolutePath()).putExtra("name", fixture).putExtra("tool", tool)
            .putExtra("sha256", digest).putExtra("sourceSize", input.length());
        controller = Robolectric.buildActivity(NativeToolsActivity.class, intent).setup().visible(); activity = controller.get();
    }
    private void settle() throws Exception {
        for (int i = 0; i < 5; i++) {
            ExecutorService executor = ReflectionHelpers.getField(activity, "worker");
            executor.submit(() -> {}).get(30, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!ReflectionHelpers.<Boolean>getField(activity, "busy")) return;
        }
        fail("UI worker did not settle");
    }
    private ListView list() { return activity.findViewById(R.id.native_records); }
    private String status() { return ((TextView)activity.findViewById(R.id.native_status)).getText().toString(); }
    private Button button(String label) { return findButton(activity.getWindow().getDecorView(), label); }
    private static Button findButton(View view, String label) {
        if (view instanceof Button && ((Button)view).getText().toString().equals(label)) return (Button)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            Button result = findButton(((ViewGroup)view).getChildAt(i), label); if (result != null) return result;
        }
        return null;
    }
    private void rowAction(int row, String label) {
        list().performItemClick(null, row, row);
        AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(details); details.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog actions = ShadowAlertDialog.getLatestAlertDialog(); ListView choices = actions.getListView();
        for (int i = 0; i < choices.getAdapter().getCount(); i++) if (choices.getAdapter().getItem(i).toString().equals(label)) {
            choices.performItemClick(null, i, i); return;
        }
        fail("Action unavailable: " + label);
    }
    private ByteArrayOutputStream finishExport() throws Exception {
        Uri uri = Uri.parse("content://org.diemobile.test/export"); ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(uri, captured);
        activity.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(uri)); settle();
        assertTrue(status(), status().contains("Export saved")); return captured;
    }

    @Test public void nativeRowsPageSearchAndNavigateBackWithoutLosingContext() throws Exception {
        open("pe-symbols-10000.exe", 4);
        assertEquals(50, list().getAdapter().getCount()); assertTrue(status(), status().contains("10000"));
        ((EditText)activity.findViewById(R.id.native_filter)).setText("S00000");
        activity.findViewById(R.id.native_next).performClick();
        assertEquals(50, ((NativeTable.Row)list().getAdapter().getItem(0)).index);
        rowAction(0, "Hex at physical offset"); settle();
        assertEquals(9, ((Spinner)activity.findViewById(R.id.native_tool)).getSelectedItemPosition());
        assertTrue(((TextView)activity.findViewById(R.id.native_output)).getText().toString().startsWith("0000000000000232"));
        activity.findViewById(R.id.native_back).performClick(); settle();
        assertEquals(4, ((Spinner)activity.findViewById(R.id.native_tool)).getSelectedItemPosition());
        assertEquals("S00000", ((EditText)activity.findViewById(R.id.native_filter)).getText().toString());
        assertEquals(50, ((NativeTable.Row)list().getAdapter().getItem(0)).index);
        ((EditText)activity.findViewById(R.id.native_filter)).setText("S0009999");
        assertEquals(1, list().getAdapter().getCount()); assertEquals(9999, ((NativeTable.Row)list().getAdapter().getItem(0)).index);
    }

    @Test public void resourceExportUsesPreparedBytesAcrossPickerDelayAndRecreation() throws Exception {
        open("pe32-rich.exe", 5); assertEquals(1, list().getAdapter().getCount());
        rowAction(0, "Export stored bytes"); settle();
        var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(started); assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.getAction());
        assertEquals("application/octet-stream", started.intent.getType());
        try (RandomAccessFile mutation = new RandomAccessFile(input, "rw")) { mutation.seek(0x880); mutation.write(new byte[22]); }
        controller.recreate(); activity = controller.get(); settle();
        byte[] exported = finishExport().toByteArray();
        assertArrayEquals(Files.readAllBytes(Path.of(System.getProperty("die.fixture.dir"), "resource-payload.bin")), exported);
    }

    @Test public void filteredTableReportKeepsAllRowsAndCapturedParameters() throws Exception {
        open("pe-symbols-10000.exe", 4);
        ((EditText)activity.findViewById(R.id.native_filter)).setText("S0009999");
        button("Export report").performClick(); settle();
        ((EditText)activity.findViewById(R.id.native_filter)).setText("different view");
        JSONObject report = new JSONObject(finishExport().toString("UTF-8"));
        assertEquals("org.diemobile.tool-report", report.getString("schema"));
        assertEquals(10000, report.getJSONObject("nativeRecords").getJSONArray("rows").length());
        assertEquals("S0009999", report.getJSONObject("view").getString("filter"));
        assertEquals(4, report.getJSONObject("request").getInt("toolId"));
        assertEquals(0, report.getJSONObject("request").getInt("mode"));
        assertFalse(report.getJSONObject("input").isNull("sha256"));
    }

    @Test public void rawDisassemblyRequiresModeAndSeparatesRowPagesFromByteBlocks() throws Exception {
        open("nops.bin", 6);
        assertTrue(status(), status().contains("No automatic architecture"));
        ((Spinner)activity.findViewById(R.id.native_mode)).setSelection(3);
        activity.findViewById(R.id.native_reload).performClick(); settle();
        assertEquals(50, list().getAdapter().getCount());
        NativeTable.Row first = (NativeTable.Row)list().getAdapter().getItem(0);
        assertNull(first.virtualAddress); assertEquals("file-offset", first.text("addressDomain"));
        activity.findViewById(R.id.native_next).performClick(); assertEquals(50, ((NativeTable.Row)list().getAdapter().getItem(0)).index);
        activity.findViewById(R.id.native_next_block).performClick(); settle();
        assertEquals(Long.valueOf(512), ((NativeTable.Row)list().getAdapter().getItem(0)).physicalOffset);
        activity.findViewById(R.id.native_back).performClick(); settle();
        assertEquals(Long.valueOf(50), ((NativeTable.Row)list().getAdapter().getItem(0)).physicalOffset);
    }

    @Test public void selectedResourceRangeHasItsOwnMeasuredDigest() throws Exception {
        open("pe64-rich.exe", 5);
        rowAction(0, "Analyze range: entropy / SHA-256 / CRC32"); settle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); settle();
        JSONObject report = new JSONObject(finishExport().toString("UTF-8"));
        assertEquals("org.diemobile.range-report", report.getString("schema"));
        assertEquals(0x880, report.getLong("physicalOffset")); assertEquals(22, report.getLong("lengthBytes"));
        byte[] expected = Files.readAllBytes(Path.of(System.getProperty("die.fixture.dir"), "resource-payload.bin"));
        assertEquals(Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(expected)), report.getString("rangeSha256"));
        assertTrue(report.getDouble("entropyBitsPerByte") > 0);
    }

    @Test public void editedMapModeDoesNotRelabelCapturedResults() throws Exception {
        open("pe32-rich.exe", 1);
        assertTrue(list().getAdapter().getCount() > 1);
        ((Spinner)activity.findViewById(R.id.native_mode)).setSelection(3);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(status(), status().contains("parameters changed"));
        rowAction(0, "Hex at physical offset"); settle();
        activity.findViewById(R.id.native_back).performClick(); settle();
        assertEquals(3, ((Spinner)activity.findViewById(R.id.native_mode)).getSelectedItemPosition());
        assertTrue(list().getAdapter().getCount() > 1); assertTrue(status(), status().contains("parameters changed"));
        controller.recreate(); activity = controller.get(); settle();
        assertEquals(3, ((Spinner)activity.findViewById(R.id.native_mode)).getSelectedItemPosition());
        assertTrue(list().getAdapter().getCount() > 1); assertTrue(status(), status().contains("parameters changed"));
        button("Export report").performClick(); settle();
        JSONObject old = new JSONObject(finishExport().toString("UTF-8"));
        assertEquals(0, old.getJSONObject("request").getInt("mode"));
        assertEquals("Sections", old.getJSONObject("nativeRecords").getJSONObject("metadata").getString("mode"));
        activity.findViewById(R.id.native_reload).performClick(); settle();
        assertEquals(1, list().getAdapter().getCount());
        assertNull(((NativeTable.Row)list().getAdapter().getItem(0)).virtualAddress);
        assertFalse(status(), status().contains("parameters changed"));
    }

    @Test public void preferredUnsignedVaNavigatesThroughNativeMap() throws Exception {
        open("mach64-high.bin", 1);
        button("Go to offset / VA").performClick();
        AlertDialog navigation = ShadowAlertDialog.getLatestAlertDialog();
        Spinner domain = findView(navigation.getWindow().getDecorView(), Spinner.class);
        EditText value = findView(navigation.getWindow().getDecorView(), EditText.class);
        domain.setSelection(1); value.setText("0xFFFF800000001000");
        navigation.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); settle();
        assertEquals(9, ((Spinner)activity.findViewById(R.id.native_tool)).getSelectedItemPosition());
        assertTrue(((TextView)activity.findViewById(R.id.native_output)).getText().toString().startsWith("0000000000001000"));
    }
    @Test public void queuedTableCancellationRecoversWithoutDisplayingACompletedSnapshot() throws Exception {
        synchronized (Engine.class) {
            openWithoutWaiting("pe32-rich.exe", 1);
            button("Cancel").performClick();
        }
        settle();
        assertTrue(status(), status().contains("cancelled"));
        assertEquals(View.GONE, list().getVisibility());
        activity.findViewById(R.id.native_reload).performClick(); settle();
        assertTrue(list().getAdapter().getCount() > 1); assertFalse(status(), status().contains("cancelled"));
    }
    @Test @Config(qualifiers = "w360dp-h640dp-mdpi")
    public void recordsKeepUsableSpaceOnAPhoneSizedWindow() throws Exception {
        open("pe32-rich.exe", 1);
        assertTrue("Record pane has only " + list().getHeight() + " px on a 360x640 phone", list().getHeight() >= 160);
        ((Spinner)activity.findViewById(R.id.native_tool)).setSelection(6);
        Shadows.shadowOf(Looper.getMainLooper()).idle(); settle();
        assertEquals(View.VISIBLE, list().getVisibility());
        assertTrue("Disassembly pane has only " + list().getHeight() + " px", list().getHeight() >= 160);
    }
    @Test public void structureFieldsResolveStorageAndPeRvaSeparatelyAndExportAllRows() throws Exception {
        open("pe32-rich.exe", 0);
        NativeTable table = ReflectionHelpers.getField(activity, "table");
        assertEquals("structures", table.kind); assertEquals(274, table.rows.size());
        EditText filter = activity.findViewById(R.id.native_filter);
        filter.setText("\"name\":\"e_lfanew\""); assertEquals(1, list().getAdapter().getCount());
        NativeTable.Row field = (NativeTable.Row)list().getAdapter().getItem(0);
        assertEquals(Long.valueOf(0x3c), field.physicalOffset);
        rowAction(0, "Hex at resolved field value (format-defined whole-input file offset)"); settle();
        assertTrue(((TextView)activity.findViewById(R.id.native_output)).getText().toString().startsWith("0000000000000080"));
        activity.findViewById(R.id.native_back).performClick(); settle();
        filter = activity.findViewById(R.id.native_filter);
        assertEquals("\"name\":\"e_lfanew\"", filter.getText().toString());
        rowAction(0, "Hex at physical offset"); settle();
        assertTrue(((TextView)activity.findViewById(R.id.native_output)).getText().toString().startsWith("000000000000003C"));
        activity.findViewById(R.id.native_back).performClick(); settle();
        filter = activity.findViewById(R.id.native_filter); filter.setText("\"name\":\"AddressOfEntryPoint\"");
        rowAction(0, "Map resolved field VA (PE RVA + captured ImageBase 0x400000)"); settle();
        assertTrue(((TextView)activity.findViewById(R.id.native_output)).getText().toString().startsWith("0000000000000200"));
        activity.findViewById(R.id.native_back).performClick(); settle();
        button("Export report").performClick(); settle(); controller.recreate(); activity = controller.get(); settle();
        JSONObject exported = new JSONObject(finishExport().toString("UTF-8"));
        assertEquals(274, exported.getJSONObject("nativeRecords").getJSONArray("rows").length());
        assertEquals("structures", exported.getJSONObject("nativeRecords").getString("kind"));
        assertEquals(0, exported.getJSONObject("request").getInt("toolId"));
        assertEquals(-1, exported.getJSONObject("request").getInt("physicalOffset"));
        assertEquals(-1, exported.getJSONObject("request").getInt("machOSliceIndex"));
    }

    @Test public void fatSliceFieldsUseWholeInputStorageAndResolvedSliceValues() throws Exception {
        open("mach-fat.bin", 0);
        ((EditText)activity.findViewById(R.id.native_offset)).setText("1");
        activity.findViewById(R.id.native_reload).performClick(); settle();
        NativeTable table = ReflectionHelpers.getField(activity, "table");
        NativeTable.Row target = null;
        for (NativeTable.Row row : table.rows) if (row.name.equals("offset") && row.physicalOffset != null && row.physicalOffset == 0x3000 + 152) target = row;
        assertNotNull(target);
        int parent = target.toJson().getInt("parentStructureIndex");
        ((EditText)activity.findViewById(R.id.native_filter)).setText("\"parentStructureIndex\":" + parent);
        int position = -1;
        for (int i = 0; i < list().getAdapter().getCount(); i++) if (((NativeTable.Row)list().getAdapter().getItem(i)).index == target.index) position = i;
        assertTrue(position >= 0);
        rowAction(position, "Hex at resolved field value (Mach-O slice-relative value + captured header offset 0x3000)"); settle();
        assertTrue(((TextView)activity.findViewById(R.id.native_output)).getText().toString().startsWith("0000000000004000"));
        activity.findViewById(R.id.native_back).performClick(); settle(); controller.recreate(); activity = controller.get(); settle();
        assertEquals("0x1", ((EditText)activity.findViewById(R.id.native_offset)).getText().toString());
        ToolRequest captured = ReflectionHelpers.getField(activity, "produced");
        assertEquals(1, captured.toJson().getInt("machOSliceIndex")); assertEquals(-1, captured.toJson().getInt("physicalOffset"));
        ReflectionHelpers.<EditText>getField(activity, "selector").setText("section_64#offset");
        ((EditText)activity.findViewById(R.id.native_filter)).setText("");
        activity.findViewById(R.id.native_reload).performClick(); settle();
        table = ReflectionHelpers.getField(activity, "table"); assertEquals(2, table.rows.size());
        assertEquals(Long.valueOf(0x3000 + 152), table.rows.get(1).physicalOffset);
        assertNull(NativeStructures.reference(table, table.rows.get(1)));
    }

    @Test public void structureFieldExportKeepsStoredBytesAcrossMutationAndRecreation() throws Exception {
        open("pe32-rich.exe", 0);
        ((EditText)activity.findViewById(R.id.native_filter)).setText("\"name\":\"e_lfanew\"");
        rowAction(0, "Export stored bytes"); settle();
        try (RandomAccessFile mutation = new RandomAccessFile(input, "rw")) { mutation.seek(0x3c); mutation.writeInt(0); }
        controller.recreate(); activity = controller.get(); settle();
        assertArrayEquals(new byte[]{(byte)0x80, 0, 0, 0}, finishExport().toByteArray());
    }

    @Test public void entropyHistogramHasNoInventedByteLocationsAndExportsTheCompleteScope() throws Exception {
        open("entropy-uniform.bin", 10);
        NativeTable original = ReflectionHelpers.getField(activity, "table");
        assertEquals("entropy", original.kind); assertEquals(100, original.metadata().getInt("sampleCount"));
        ((EditText)activity.findViewById(R.id.native_filter)).setText("byte-frequency");
        assertEquals(50, list().getAdapter().getCount());
        activity.findViewById(R.id.native_next).performClick();
        NativeTable.Row bin = (NativeTable.Row)list().getAdapter().getItem(0);
        assertEquals(50, bin.code("byteValue")); assertNull(bin.physicalOffset); assertNull(bin.virtualAddress); assertNull(bin.sizeBytes);
        list().performItemClick(null, 0, 0);
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog noBytes = ShadowAlertDialog.getLatestAlertDialog(); assertEquals("No byte navigation", Shadows.shadowOf(noBytes).getTitle().toString()); noBytes.dismiss();
        ((EditText)activity.findViewById(R.id.native_filter)).setText("\"byteValue\":255");
        assertEquals(1, list().getAdapter().getCount()); assertEquals("Byte 0xFF", ((NativeTable.Row)list().getAdapter().getItem(0)).name);
        button("Export report").performClick(); settle();
        try (RandomAccessFile mutation = new RandomAccessFile(input, "rw")) { mutation.seek(0); mutation.write(new byte[512]); }
        controller.recreate(); activity = controller.get(); settle();
        JSONObject report = new JSONObject(finishExport().toString("UTF-8"));
        JSONObject snapshot = report.getJSONObject("nativeRecords");
        assertEquals(original.rows.size(), snapshot.getJSONArray("rows").length());
        assertEquals(original.toJson().getJSONArray("rows").toString(), snapshot.getJSONArray("rows").toString());
        assertEquals(10, report.getJSONObject("request").getInt("toolId"));
        assertTrue(report.getJSONObject("request").getBoolean("nativeJson"));
        assertEquals("\"byteValue\":255", report.getJSONObject("view").getString("filter"));
    }

    @Test public void entropyFinalBlockExportsExactRemainderBytesAcrossRecreation() throws Exception {
        open("entropy-uniform.bin", 10);
        ((EditText)activity.findViewById(R.id.native_filter)).setText("\"name\":\"Block 99\"");
        assertEquals(1, list().getAdapter().getCount());
        NativeTable.Row block = (NativeTable.Row)list().getAdapter().getItem(0);
        assertEquals(Long.valueOf(129690), block.physicalOffset); assertEquals("1386", block.text("sizeBytes"));
        byte[] expected = java.util.Arrays.copyOfRange(Files.readAllBytes(input.toPath()), 129690, 131076);
        rowAction(0, "Export stored bytes"); settle();
        try (RandomAccessFile mutation = new RandomAccessFile(input, "rw")) { mutation.seek(129690); mutation.write(new byte[1386]); }
        controller.recreate(); activity = controller.get(); settle();
        assertArrayEquals(expected, finishExport().toByteArray());
        ToolRequest produced = ReflectionHelpers.getField(activity, "produced"); assertEquals(10, produced.tool);
    }

    private static <T extends View> T findView(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            T found = findView(((ViewGroup)view).getChildAt(i), type); if (found != null) return found;
        }
        return null;
    }
}
