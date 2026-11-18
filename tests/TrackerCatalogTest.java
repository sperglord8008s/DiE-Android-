// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.google.re2j.Matcher;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Actual pinned upstream methods, independent UCD input, complete metadata and acquisition. */
public final class TrackerCatalogTest {
    private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static TrackerCatalog read(String text) throws Exception { return TrackerCatalog.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),()->false); }
    private static TrackerCatalog single(String signature) throws Exception {
        return read(new JSONObject().put("trackers",new JSONObject().put("1",new JSONObject().put("name","Synthetic").put("code_signature",signature))).toString());
    }
    private static void rejected(String text) throws Exception {
        try { read(text);throw new AssertionError("Invalid snapshot accepted: "+text.substring(0,Math.min(64,text.length()))); }
        catch(IOException expected) {}
    }
    public static void main(String[] args) throws Exception {
        JSONObject reference=new JSONObject(Files.readString(Path.of(args[0])));JSONArray cases=reference.getJSONArray("cases");
        require(reference.getString("sourceSha256").equals(TrackerCatalog.REFERENCE_SHA256),"Wrong actual upstream reference");
        for(int i=0;i<cases.length();i++) {
            JSONObject test=cases.getJSONObject(i);TrackerCatalog catalog=single(test.getString("signature"));
            List<String> descriptors=new ArrayList<>();JSONArray values=test.getJSONArray("descriptors");for(int j=0;j<values.length();j++) descriptors.add(values.getString(j));
            TrackerCatalog.Result result=catalog.match(descriptors,()->false);require(result.complete(),"Compared portable case unsupported: "+test.getString("signature"));
            TrackerCatalog.Evaluation hit=result.evaluations.get(0);
            if(test.isNull("expected")) require(!hit.status.equals("matched"),"Unexpected regex hit in reference case "+i);
            else {
                JSONObject expected=test.getJSONObject("expected");
                require(hit.status.equals("matched")&&hit.input.equals(expected.getString("input"))&&hit.start==expected.getInt("start")&&hit.end==expected.getInt("end"),"Upstream search/evidence/span differs in case "+i+" / "+test);
            }
        }
        unicode(Path.of(args[1]));
        String raw="{\"version\":9007199254740993123456789,\"future\":{\"exact\":1e400},\"trackers\":{\"é\":{\"name\":\"Original\",\"code_signature\":\"com/(one|two)/\",\"future\":{\"number\":9007199254740993123456789,\"units\":\"\\uD800\\u0000\"}},\"2\":{\"code_signature\":\"(?=foo)bar\"},\"3\":{\"code_signature\":\"ab\"},\"4\":null,\"5\":{\"code_signature\":12}}}";
        TrackerCatalog catalog=read(raw);require(catalog.rules.size()==5,"Unknown/non-object records erased");
        require(catalog.rules.get(1).status.equals("unsupported")&&catalog.rules.get(2).status.equals("ignored-short"),"Unsupported/short diagnostic differs");
        EvidenceRows rows=catalog.rows(false,false);require(rows.size()==5&&rows.select("units",()->false).count==1,"Full unknown metadata search missing");
        EvidenceRows.Row first=rows.row(0,()->false);require(first.physicalOffset==null&&first.json().getString("snapshotKey").equals("é"),"Catalog given a selected-file offset");
        StringWriter output=new StringWriter();rows.writeSelectedRow(output,first,()->false);
        require(output.toString().contains("9007199254740993123456789")&&output.toString().contains("\\uD800\\u0000"),"Exact original numeric/Unicode record lost");
        require(catalog.rows(false,true).size()==2&&catalog.rows(true,false).size()==4,"Metadata / all skipped records unavailable");
        require(catalog.rows(false,true).select("9007199254740993123456789",()->false).count==1,"Original numeric spelling is not searchable");
        TrackerCatalog large=single("x".repeat(5*1024*1024));EvidenceRows largeRows=large.rows(false,false);
        require(largeRows.row(0,()->false).encoded.length()<32768&&largeRows.row(0,()->false).json().getBoolean("signaturePreviewTruncated"),"Overlong unsupported signature cannot be inspected within a row budget");
        StringWriter complete=new StringWriter();largeRows.writeSelectedRow(complete,largeRows.row(0,()->false),()->false);
        require(new JSONObject(complete.toString()).getJSONObject("fullOriginalRecord").getString("code_signature").length()==5*1024*1024,"Large unsupported original signature lost in full export");
        TrackerCatalog longKey=read("{\"trackers\":{},\""+"k".repeat(5*1024*1024)+"\":42}");EvidenceRows metadataRows=longKey.rows(false,true);
        require(metadataRows.row(0,()->false).encoded.length()<32768&&metadataRows.row(0,()->false).json().getBoolean("snapshotKeyPreviewTruncated"),"Large metadata key prevents inspecting its value");
        StringWriter keyExport=new StringWriter();metadataRows.writeSelectedRow(keyExport,metadataRows.row(0,()->false),()->false);
        require(new JSONObject(keyExport.toString()).getString("fullOriginalKey").length()==5*1024*1024,"Large original key lost in full metadata export");
        try { TrackerCatalog.read(new ByteArrayInputStream(new byte[TrackerCatalog.MAX_BYTES+1]),()->false);throw new AssertionError("Over-budget import accepted"); }
        catch(IOException expected) { require(expected.getMessage().contains("8 MiB"),"Import size check did not precede parsing"); }
        TrackerCatalog.Result hit=catalog.match(Arrays.asList("Lcom/two/Name;","Lunrelated/Type;"),()->false);
        require(hit.hits==1&&!hit.complete()&&hit.unsupportedRules==3,"Unsupported rules misrepresented as complete absence");
        TrackerCatalog.Result bounded=single("com/example").match(Collections.singleton("Lcom/example/Name;"),()->false,new TrackerCatalog.Limits(0,100000,1000000,1000000000));
        require(!bounded.complete()&&bounded.hits==0&&bounded.unsearched==1,"Budget failure reported as a negative result");
        TrackerCatalog.Result names=single("com/.*").match(Arrays.asList("Lcom/\nName;","Lcom/\ud800;","Lcom/😀;"),()->false);
        require(names.hits==1&&names.unsupportedInputs.size()==2&&!names.complete(),"Unsupported name omitted without partial coverage");
        try { catalog.match(Collections.singleton("Lcom/example/Name;"),()->true);throw new AssertionError("Search ignored cancellation"); } catch(InterruptedIOException expected) {}
        String[] unsupported={"(?i)abc","(?=abc)abc","(abc)\\1","abc\\b","[\\W]+","x{,4}","a++b","[[:alpha:]]","x\\p{L}"};
        for(String signature:unsupported) require(single(signature).rules.get(0).status.equals("unsupported"),"Unsupported Python syntax silently changed: "+signature);
        long before=System.nanoTime();require(single("(a+)+b").match(Collections.singleton("L"+"a".repeat(16000)+";"),()->false).hits==0,"Repetition stress matched");
        require(System.nanoTime()-before<5000000000L,"Regex backtracking regression");
        for(String text:new String[]{"{\"trackers\":{\"1\":{},\"\\u0031\":{}}}","{\"trackers\":{},\"trackers\":{}}","{\"trackers\":{\"1\":{\"x\":1,\"x\":2}}}","{\"trackers\":{},\"x\":01}","{\"trackers\":{},\"x\":NaN}","{\"trackers\":{},}","{\"trackers\":[]} ","{\"trackers\":{}} trailing","{\"trackers\":{},\"x\":\"\\u１２３４\"}","{\"trackers\":{},\"x\":"+"[".repeat(65)+"0"+"]".repeat(65)+"}"}) rejected(text);
        try { TrackerCatalog.read(new ByteArrayInputStream(new byte[]{(byte)0xc0,(byte)0xaf}),()->false);throw new AssertionError("Malformed UTF-8 accepted"); } catch(IOException expected) {}
        File directory=new File(args[2],"tracker-owned"),destination=new File(args[2],"snapshot.json");Files.writeString(destination.toPath(),raw);
        try(TrackerCatalogBackend backend=new TrackerCatalogBackend(()->new FileInputStream(destination),directory)) {
            String request=backend.initialRequest(()->false);require(backend.open(request,()->false).size()==5,"Owned catalog lost records");
            Files.writeString(destination.toPath(),"changed");require(Arrays.equals(backend.binaryValue("snapshot",()->false),raw.getBytes(StandardCharsets.UTF_8)),"Later import changed captured bytes");
            require(!backend.byteSource().equals(destination)&&backend.roots(request,()->false).size()==4,"Catalog ownership/root actions missing");
        }
        require(Objects.requireNonNull(directory.list()).length==0,"Closed catalog snapshot leaked");
        Files.writeString(destination.toPath(),raw);
        try { TrackerCatalog.install(new ByteArrayInputStream("{\"trackers\":{},}".getBytes(StandardCharsets.UTF_8)),destination,()->false);throw new AssertionError("Invalid snapshot installed"); } catch(IOException expected) {}
        require(Files.readString(destination.toPath()).equals(raw),"Invalid import damaged previous snapshot");
        TrackerCatalog installed=TrackerCatalog.install(new ByteArrayInputStream("{\"trackers\":{}}".getBytes(StandardCharsets.UTF_8)),destination,()->false);
        require(installed.rules.isEmpty()&&Files.readString(destination.toPath()).equals("{\"trackers\":{}}"),"Valid exact snapshot not atomically installed");
        System.out.println("Tracker fixtures passed: "+cases.length()+" actual pinned Exodus-method cases, exhaustive frozen Unicode 15 properties, regex search/spans/alternatives, full original metadata/numbers/Unicode, strict import/duplicate/depth/UTF-8, unsupported/short/budget/cancellation/no-backtracking and atomic ownership contracts.");
        if(args.length==4) {
            TrackerCatalog imported;try(InputStream input=new FileInputStream(args[3])) { imported=TrackerCatalog.read(input,()->false); }
            int compiled=0,ignored=0,unsupportedCount=0,instructions=0;
            for(TrackerCatalog.Rule rule:imported.rules) {
                if(rule.pattern!=null) { compiled++;instructions+=rule.pattern.programSize(); }
                else if(rule.status.equals("ignored-short")) ignored++;
                else { unsupportedCount++;System.out.println("Unsupported imported record "+rule.id+": "+rule.diagnostic); }
            }
            System.out.println("Optional local snapshot audit (no authenticity/behavior claim): SHA-256 "+imported.sha256+"; "+imported.rules.size()+" records, "+compiled+" compiled, "+ignored+" short, "+unsupportedCount+" unsupported, "+instructions+" compiled instructions.");
        }
    }
    private static void unicode(Path source) throws Exception {
        BitSet word=new BitSet(),digit=new BitSet(),space=new BitSet();word.set(95);int pending=-1;
        for(String line:Files.readAllLines(source)) {
            String[] fields=line.split(";",-1);int code=Integer.parseInt(fields[0],16);
            if(fields[1].endsWith(", First>")) { pending=code;continue; }
            int begin=fields[1].endsWith(", Last>")?pending:code;pending=-1;
            if(fields[2].startsWith("L")||fields[2].startsWith("N")) word.set(begin,code+1);
            if(fields[2].equals("Nd")) digit.set(begin,code+1);
            if(fields[2].equals("Zs")||Arrays.asList("WS","B","S").contains(fields[4])) space.set(begin,code+1);
        }
        StringBuilder all=new StringBuilder();for(int cp=0;cp<=0x10ffff;cp++) if(cp<0xd800||cp>0xdfff) all.appendCodePoint(cp);
        String[] patterns={"\\w","\\d","\\s"};BitSet[] expected={word,digit,space};
        for(int i=0;i<patterns.length;i++) {
            BitSet observed=new BitSet();Matcher matches=TrackerRegex.compile(patterns[i]).matcher(all);
            while(matches.find()) observed.set(all.codePointAt(matches.start()));
            require(observed.equals(expected[i]),"Frozen Python Unicode property differs from original UCD: "+patterns[i]);
        }
        require(!TrackerRegex.compile("\\s").matcher("\u180e").find(),"Obsolete Unicode 6 whitespace leaked into current matching");
    }
}
