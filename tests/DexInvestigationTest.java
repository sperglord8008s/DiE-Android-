// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.tools.smali.dexlib2.ReferenceType;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Adler32;
import org.json.*;

/** Real generated DEX bytes through the production adapters and upstream engines. */
public final class DexInvestigationTest {
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static EvidenceRows.Row find(EvidenceRows rows, String name) throws Exception {
        for (int i = 0; i < rows.size(); i++) { EvidenceRows.Row row = rows.row(i, () -> false); if (row.name.contains(name)) return row; }
        throw new AssertionError("Missing row " + name + " in " + rows.title);
    }
    private static DexQuery query(String kind, int owner, int member) { return new DexQuery(0, kind, owner, member, -1, -1); }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); byte[] bytes = DexFixtureFactory.rich();
        File input = directory.resolve("rich.dex").toFile(); Files.write(input.toPath(), bytes);
        DexDocument document = DexDocument.read(input, () -> false); DexDocument.Entry entry = document.entries.get(0);
        require(document.entries.size() == 1 && entry.version == 39, "Wrong DEX identification");
        require(entry.checksumValid && entry.signatureValid, "Generated DEX integrity did not validate");
        require(document.sha256.equals(Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes))), "Input SHA-256 differs");
        require(document.definedClassNames(() -> false).contains("org.diemobile.fixture.研究"), "Unicode class name was dropped");
        EvidenceRows header = entry.rows(DexQuery.root(0, "header"), () -> false);
        require(header.size() == 23 && find(header, "file_size").json().getLong("value") == bytes.length, "Raw header fields differ from source bytes");
        EvidenceRows classes = entry.rows(DexQuery.root(0, "classes"), () -> false);
        require(classes.size() == 126, "Class definitions lost");
        EvidenceRows.Selection filtered = classes.select("研究", () -> false); require(filtered.count == 1, "Unicode class search failed");
        require(classes.select("", () -> false).page(1, () -> false).rows.get(0).index == 50, "Class paging lost native indices");
        int owner = find(classes, DexFixtureFactory.OWNER).index;
        JSONObject type = classes.row(owner, () -> false).json();
        require(type.getJSONArray("annotations").length() == 1 && type.getString("sourceFile").equals("研究.smali"), "Class annotation/source metadata lost");
        EvidenceRows fields = entry.rows(query("fields", owner, -1), () -> false);
        require(fields.size() == 5, "Field definitions lost");
        require(find(fields, "->BIG:J").json().getJSONObject("initialValue").getString("value").equals(Long.toString(Long.MAX_VALUE)), "64-bit initial value changed");
        require(find(fields, "->ZERO:D").json().getJSONObject("initialValue").getString("value").equals("-0.0"), "Negative zero changed");
        require(find(fields, "->ENDPOINT:").json().getJSONObject("initialValue").getString("value").equals(DexFixtureFactory.ENDPOINT), "String initial value lost");
        EvidenceRows methods = entry.rows(query("methods", owner, -1), () -> false);
        int inspect = find(methods, "->inspect()V").index, nativeMethod = find(methods, "->nativeMethod()V").index;
        JSONObject target = find(methods, "->target(I)I").json();
        require(target.getJSONArray("parameters").getJSONObject(0).getString("name").equals("入力"), "Parameter name lost");
        require(target.getJSONArray("parameters").getJSONObject(0).getJSONArray("annotations").length() == 1, "Parameter annotations lost");
        require(!methods.row(nativeMethod, () -> false).json().getBoolean("hasImplementation"), "Native declaration invented a body");

        EvidenceRows strings = entry.rows(DexQuery.root(0, "strings"), () -> false); EvidenceRows.Row text = find(strings, DexFixtureFactory.TEXT);
        require(text.json().getString("value").equals(DexFixtureFactory.TEXT), "MUTF-8/NUL/supplementary/isolated-surrogate string changed");
        require(text.json().getInt("utf16Units") == DexFixtureFactory.TEXT.length(), "Length counts bytes/code points instead of UTF-16 units");
        require(bytes[(int)(text.physicalOffset + text.sizeBytes - 1)] == 0, "String range omitted its NUL terminator");
        require(text.encoded.contains("\\uD800"), "Isolated surrogate not preserved as a JSON escape");
        ByteArrayOutputStream utf8 = new ByteArrayOutputStream();
        try (Writer out = new OutputStreamWriter(utf8, StandardCharsets.UTF_8)) { out.write(text.encoded); }
        require(new JSONObject(utf8.toString("UTF-8")).getString("value").equals(DexFixtureFactory.TEXT), "UTF-8 export substituted an isolated surrogate");

        DexQuery codeQuery = query("code", owner, inspect); EvidenceRows code = entry.rows(codeQuery, () -> false); long lastPhysical = -1; int units = 0;
        for (int i = 0; i < code.size(); i++) {
            EvidenceRows.Row row = code.row(i, () -> false); JSONObject value = row.json();
            require(value.getInt("codeUnitOffset") == units, "Code-unit offsets have gaps");
            if (lastPhysical >= 0) require(row.physicalOffset == lastPhysical, "Physical code offsets have gaps");
            lastPhysical = row.physicalOffset + row.sizeBytes; units += value.getInt("codeUnits");
            if (value.has("targetInstructionIndex") && !value.isNull("targetInstructionIndex")) {
                require(code.row(value.getInt("targetInstructionIndex"), () -> false).json().getLong("codeUnitOffset") == value.getLong("targetCodeUnits"), "Branch/payload target mapping differs");
            }
        }
        require(code.metadata().getInt("codeUnits") == units && code.metadata().getInt("registerCount") == 6, "Method frame metadata wrong");
        EvidenceRows.Row call = find(code, "invoke-static"); JSONObject reference = call.json().getJSONObject("reference");
        require(reference.getInt("index") == target.getInt("methodIndex") && reference.getInt("type") == ReferenceType.METHOD, "Encoded method index differs from definition table");
        EvidenceRows callers = entry.rows(DexQuery.root(0, "classes").references(ReferenceType.METHOD, target.getInt("methodIndex")), () -> false);
        require(callers.size() == 1 && callers.row(0, () -> false).json().getString("caller").contains("->inspect()V"), "Encoded callers are wrong");

        int payload = find(code, "array-payload").index;
        JSONObject payloadPreview = code.row(payload, () -> false).json();
        require(payloadPreview.getInt("elementCount") == 80 && payloadPreview.getJSONArray("valuesPreview").length() == 64, "Array preview scope is wrong");
        EvidenceRows array = entry.rows(new DexQuery(0, "array", owner, inspect, -1, payload), () -> false);
        require(array.size() == 80 && array.row(0, () -> false).json().getString("valueSigned").equals("-40"), "Full array values are missing");
        require(array.row(0, () -> false).json().getString("rawBytes").equals("d8"), "Array raw-byte extent differs");
        int switchIndex = find(code, "packed-switch-payload").index;
        EvidenceRows cases = entry.rows(new DexQuery(0, "switch", owner, inspect, -1, switchIndex), () -> false);
        require(cases.size() == 2 && cases.row(0, () -> false).json().getInt("key") == 7, "Switch keys lost");
        JSONObject firstCase = cases.row(0, () -> false).json();
        require(code.row(firstCase.getInt("targetInstructionIndex"), () -> false).name.startsWith("const-string"), "Switch offsets were based on the payload instead of the switch");
        EvidenceRows tries = entry.rows(query("tries", owner, inspect), () -> false); JSONObject region = tries.row(0, () -> false).json();
        require(region.getBoolean("rangeFitsMethod") && region.getJSONArray("handlers").length() == 2, "Typed/catch-all handlers lost");
        boolean catchAll = false; for (int i = 0; i < region.getJSONArray("handlers").length(); i++) catchAll |= region.getJSONArray("handlers").getJSONObject(i).isNull("exceptionType");
        require(catchAll, "Catch-all was relabeled as a typed exception");
        require(entry.rows(query("debug", owner, inspect), () -> false).size() >= 6, "Debug source/line/local events lost");
        require(entry.rows(query("code", owner, nativeMethod), () -> false).size() == 0, "Native method has invented instructions");

        int dual = find(methods, "->dualReferences()V").index;
        EvidenceRows dualCode = entry.rows(query("code", owner, dual), () -> false); int proto = dualCode.row(0, () -> false).json().getJSONObject("reference2").getInt("index");
        require(dualCode.row(1, () -> false).json().getJSONObject("reference2").getInt("index") == proto, "45cc/4rcc second reference indices differ");
        require(entry.rows(DexQuery.root(0, "protos").references(ReferenceType.METHOD_PROTO, proto), () -> false).size() == 3, "Second-reference / const-method-type usages missing");
        EvidenceRows sites = entry.rows(DexQuery.root(0, "call-sites"), () -> false);
        require(sites.size() == 1 && sites.row(0, () -> false).json().getJSONArray("extraArguments").length() == 2, "Invoke-custom call-site metadata lost");
        require(entry.rows(DexQuery.root(0, "method-handles"), () -> false).size() == 1, "Method handle pool lost");

        StringWriter smali = new StringWriter(); DexSmali.write(document, codeQuery, smali, () -> false); String source = smali.toString();
        for (String required : Arrays.asList(".method", "inspect()V", "invoke-static", "->target(I)I", ".array-data", ".packed-switch", ".catchall", ".line 7", ".local")) require(source.contains(required), "Baksmali output missing " + required);
        StringWriter nativeSmali = new StringWriter(); DexSmali.write(document, query("code", owner, nativeMethod), nativeSmali, () -> false);
        require(nativeSmali.toString().contains("native nativeMethod()V") && !nativeSmali.toString().contains(".registers"), "Native declaration disassembly invented a frame");
        StringWriter report = new StringWriter(); classes.writeReport(report, document.provenance("rich.dex"), "研究", 0, () -> false);
        JSONObject exported = new JSONObject(report.toString()); require(exported.getJSONArray("rows").length() == 126 && exported.getInt("rowCount") == 126, "Filter reduced complete scoped export");
        require(exported.getJSONObject("provenance").getJSONObject("input").getString("sha256").equals(document.sha256), "Report source identity lost");
        File staging = directory.resolve("exports").toFile();
        PreparedExport staged = PreparedExport.stream(staging, "dex.json", "application/json", out -> classes.writeReport(out, document.provenance("rich.dex"), "研究", 0, () -> false), () -> false);
        require(new JSONObject(Files.readString(staged.file.toPath())).getJSONArray("rows").length() == 126, "Streamed staging changed the table"); staged.discard();
        AtomicInteger checks = new AtomicInteger(); rejected(() -> PreparedExport.stream(staging, "cancel.json", "application/json", out -> classes.writeReport(out, document.provenance("rich.dex"), "", 0, () -> checks.incrementAndGet() > 5), () -> false));
        require(Objects.requireNonNull(staging.list()).length == 0, "Cancelled stream left staged data");
        rejected(() -> classes.select("missing", () -> true)); rejected(() -> DexDocument.read(input, () -> true));

        byte[] badChecksum = bytes.clone(); badChecksum[8] ^= 1;
        DexDocument.Entry mismatch = DexDocument.parse(badChecksum, () -> false).entries.get(0);
        require(!mismatch.checksumValid && mismatch.signatureValid, "Integrity mismatch hidden or relabeled as a publisher signature");
        byte[] overlay = Arrays.copyOf(bytes, bytes.length + 4); DexDocument overlaid = DexDocument.parse(overlay, () -> false);
        require(overlaid.entries.get(0).checksumValid && overlaid.entries.get(0).metadata().getInt("trailingSourceBytes") == 4, "Trailing bytes were folded into entry checksum coverage");
        byte[] hugeString = bytes.clone(); int firstString = u32(bytes, u32(bytes, 60));
        for (int i = 0; i < 5; i++) hugeString[firstString + i] = (byte)(i == 4 ? 7 : 255);
        rejected(() -> DexDocument.parse(hugeString, () -> false));
        byte[] badMap = bytes.clone(); put(badMap, u32(bytes, 52), 0x7fffffff); rejected(() -> DexDocument.parse(badMap, () -> false));
        byte[] unterminated = bytes.clone(); unterminated[(int)(text.physicalOffset + text.sizeBytes - 1)] = 1; rejected(() -> DexDocument.parse(unterminated, () -> false));
        rejected(() -> DexDocument.parse(Arrays.copyOf(bytes, 60), () -> false));
        byte[] reversed = bytes.clone(); put(reversed, 40, 0x78563412); rejected(() -> DexDocument.parse(reversed, () -> false));
        int longMethod = find(methods, "->longBody()V").index; DexCode nops = new DexCode(document, entry, owner, longMethod, () -> false);
        byte[] unknownBytes = bytes.clone(); unknownBytes[nops.firstPhysical.intValue()] = 0x3e;
        DexDocument unknownDoc = DexDocument.parse(unknownBytes, () -> false); EvidenceRows unknown = unknownDoc.entries.get(0).rows(query("code", owner, longMethod), () -> false);
        require(unknown.row(0, () -> false).json().getBoolean("unknownOpcode") && unknown.row(0, () -> false).json().isNull("canContinue"), "Unknown opcode claimed NOP behavior");
        byte[] truncatedInstruction = bytes.clone(); truncatedInstruction[(int)(nops.firstPhysical + nops.codeUnits * 2L - 2)] = 0x18;
        DexDocument truncated = DexDocument.parse(truncatedInstruction, () -> false); rejected(() -> truncated.entries.get(0).rows(query("code", owner, longMethod), () -> false));
        byte[] capped = DexFixtureFactory.instructionCap(DexCode.MAX_INSTRUCTIONS + 1); DexDocument cap = DexDocument.parse(capped, () -> false);
        rejected(() -> cap.entries.get(0).rows(query("code", 0, 0), () -> false));
        byte[] copied = bytes.clone(); DexDocument immutable = DexDocument.parse(copied, () -> false); Arrays.fill(copied, (byte)0);
        require(immutable.definedClassNames(() -> false).contains("org.diemobile.fixture.研究"), "Caller mutated an existing snapshot");
        DexDocument single41 = DexDocument.parse(DexFixtureFactory.container41(bytes, false), () -> false);
        require(single41.entries.size() == 1 && single41.entries.get(0).version == 41 && single41.entries.get(0).checksumValid, "Single-entry 041 header/integrity failed");
        byte[] containerBytes = DexFixtureFactory.container41(bytes, true); DexDocument container = DexDocument.parse(containerBytes, () -> false);
        require(container.entries.size() == 2 && container.entries.get(0).checksumValid && container.entries.get(1).checksumValid && container.entries.get(1).signatureValid, "041 container entries/integrity failed");
        EvidenceRows sharedCode = container.entries.get(1).rows(new DexQuery(1, "code", owner, inspect, -1, -1), () -> false);
        require(sharedCode.size() == code.size() && sharedCode.row(0, () -> false).physicalOffset.equals(code.row(0, () -> false).physicalOffset), "Shared 041 data offsets were incorrectly made relative to the second header");
        require(sharedCode.row(0, () -> false).physicalOffset < container.entries.get(1).headerOffset, "Fixture must reference earlier shared data");
        byte[] wrongContainerOffset = containerBytes.clone(); put(wrongContainerOffset, container.entries.get(1).headerOffset + 116, 0);
        rejected(() -> DexDocument.parse(wrongContainerOffset, () -> false));
        int[] apis = {23,25,27,28,34}, versions = {35,37,38,39,40};
        for (int i = 0; i < apis.length; i++) {
            DexDocument classic = DexDocument.parse(DexFixtureFactory.simpleVersion(apis[i]), () -> false);
            require(classic.entries.get(0).version == versions[i] && classic.entries.get(0).checksumValid, "Classic DEX version failed: " + versions[i]);
            require(classic.entries.get(0).rows(query("code",0,0), () -> false).size() == 2, "Classic instruction decode failed");
        }
        System.out.println("DEX fixtures passed: Unicode/MUTF-8, exact integrity/offsets, declarations/values, payloads/branches/debug/handlers, modern dual/dynamic references, complete streaming reports/smali, cancellation, malformed inputs and decode budgets.");
    }
    private static int u32(byte[] b, int at) { return (b[at]&255) | (b[at+1]&255)<<8 | (b[at+2]&255)<<16 | (b[at+3]&255)<<24; }
    private static void put(byte[] b, int at, int value) { for (int i = 0; i < 4; i++) b[at+i] = (byte)(value >>> (8*i)); }
    interface Action { void run() throws Exception; }
    private static void rejected(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid/cancelled DEX operation succeeded"); } catch (Exception expected) {}
    }
}
