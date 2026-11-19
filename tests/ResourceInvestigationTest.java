// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import org.json.*;

/** Real source-built parser reports, immutable APK acquisition and complete exports. */
public final class ResourceInvestigationTest {
    private static final BooleanSupplier RUN=()->false;
    private static void require(boolean value,String message) {
        if(!value) throw new AssertionError(message);
    }
    private static String probe(byte[] bytes) throws Exception {
        return AndroidResourceProbe.scan(new ByteArrayInputStream(bytes),bytes.length,RUN);
    }
    private static BinaryRunner runner() {
        return (file,stop)->{
            try(InputStream source=new FileInputStream(file)) {
                return AndroidResourceProbe.scan(source,file.length(),stop);
            }
        };
    }
    private static String sha(byte[] bytes) throws Exception {
        return Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static AndroidResourceDocument document(Path directory,byte[] bytes) throws Exception {
        File source=directory.resolve("input-"+UUID.randomUUID()+".bin").toFile();
        Files.write(source.toPath(),bytes);
        return new AndroidResourceDocument(source,"fixture",sha(bytes),probe(bytes),RUN);
    }
    private static JSONObject report(EvidenceRows rows,JSONObject origin) throws Exception {
        StringWriter output=new StringWriter();
        rows.writeReport(output,origin,"unmatched filter",3,10,RUN);
        return new JSONObject(output.toString());
    }
    private static InvestigationBackend.Link link(List<InvestigationBackend.Link> links,String text) {
        return links.stream().filter(value->value.label.contains(text)).findFirst()
            .orElseThrow(()->new AssertionError("Missing resource action: "+text));
    }
    private static void empty(File directory,String message) {
        require(directory.list()==null||Objects.requireNonNull(directory.list()).length==0,message);
    }
    private static void zip(Path path,Map<String,byte[]> entries) throws Exception {
        try(ZipOutputStream output=new ZipOutputStream(Files.newOutputStream(path))) {
            for(Map.Entry<String,byte[]> entry:entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));output.write(entry.getValue());output.closeEntry();
            }
        }
    }
    private static void rawReports(Path directory) throws Exception {
        for(boolean utf8:new boolean[]{true,false}) {
            AndroidResourceDocument document=document(directory,ResourceFixtureFactory.xml(utf8,true));
            require(document.header.getString("kind").equals("binary-xml"),"Compiled XML kind differs");
            EvidenceRows attributes=document.rows("attributes",new JSONObject(),RUN);
            require(attributes.size()==2,"Duplicate XML attribute lost in worker transport");
            require(attributes.row(0,RUN).name.equals(attributes.row(1,RUN).name),"Duplicate fixture name differs");
            require(attributes.row(0,RUN).json().getJSONObject("value").getString("text").equals("Fixture Ω 東京 😀"),"Typed XML value changed");
            require(attributes.row(1,RUN).json().getString("rawValue").equals("embedded\0value"),"Lexical XML value changed");
            require(document.rows("spans",new JSONObject(),RUN).size()==1,"Stored styles omitted");
            JSONObject span=document.raw("spans",0);
            require(span.getInt("lastUtf16UnitInclusive")==5,"UTF-16 inclusive style endpoint changed");
            EvidenceRows strings=document.rows("strings",new JSONObject(),RUN);
            JSONObject exported=report(strings,document.provenance());
            require(exported.getJSONArray("rows").length()==7,"Filtered export dropped complete strings");
            require(exported.getJSONArray("rows").getJSONObject(6).getJSONObject("fullNativeRecord").getString("text").equals("isolated-\ud800"),"JSON transport replaced isolated surrogate");
            JSONObject exact=document.raw("strings",4);
            require(exact.getLong("physicalOffset")<exact.getLong("payloadOffset"),"Length prefix merged with value bytes");
            require(exact.getLong("sizeBytes")>exact.getLong("payloadSizeBytes"),"Stored terminator omitted from range");
        }
        AndroidResourceDocument table=document(directory,ResourceFixtureFactory.table());
        require(table.rows("entries",new JSONObject(),RUN).size()==9,"Wire entries normalized in report");
        require(table.rows("offsets",new JSONObject(),RUN).size()==11,"Absent slots omitted");
        require(table.rows("maps",new JSONObject(),RUN).size()==2&&table.raw("maps",1).getBoolean("duplicateKey"),"Repeated mapping keys normalized");
        require(table.raw("maps",0).getJSONObject("value").getString("text").equals("Resource Ω")
            &&table.raw("maps",1).getJSONObject("value").getString("text").equals("Second value"),"Wire bag values changed");
        require(table.raw("types",0).getJSONObject("configuration").getInt("sdkVersion")==35,"Resource SDK qualifier absent");
        require(table.rows("entries",new JSONObject().put("resourceId",0x7f010000L),RUN).size()==5,"Resource ID correlation dropped configurations/aliases");
        require(table.raw("entries",7).getBoolean("compact")&&table.raw("entries",7).getInt("sizeBytes")==8,"Compact stored extent inferred from logical size");
        AndroidResourceDocument paged=document(directory,ResourceFixtureFactory.pagedXml());
        EvidenceRows strings=paged.rows("strings",new JSONObject(),RUN);
        require(strings.select("",RUN).page(1,RUN).rows.get(0).index==50,"Resource paging skipped records");
        require(strings.select("full-search-tail",RUN).count==1,"Complete-value search only examined its preview");
        require(strings.select("This is not a physical byte address",RUN).count==0,"Shared context contaminated searches");
        JSONObject full=report(strings,paged.provenance());
        require(full.getJSONArray("rows").length()==2051,"Complete export follows active page/filter");
        require(full.getJSONArray("rows").getJSONObject(1500).getJSONObject("fullNativeRecord").getString("text").endsWith("full-search-tail"),"Large pool string clipped in export");
    }
    private static void provider(Path directory) throws Exception {
        Path apk=directory.resolve("fixture.apk");File cache=directory.resolve("resource-inputs").toFile();
        Map<String,byte[]> members=new LinkedHashMap<>();
        members.put("AndroidManifest.xml",ResourceFixtureFactory.xml(true,true));
        members.put("resources.arsc",ResourceFixtureFactory.table());
        members.put("outside-res/long.xml",ResourceFixtureFactory.pagedXml());
        members.put("res/xml/plain.xml","<plain/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip(apk,members);byte[] original=Files.readAllBytes(apk);
        try(AndroidResourceBackend backend=new AndroidResourceBackend(apk.toFile(),cache,"fixture.apk",runner())) {
            String initial=backend.initialRequest(RUN);EvidenceRows events=backend.open(initial,RUN);
            require(events.size()==2,"APK manifest not opened by default");
            JSONObject origin=backend.provenance();
            require(origin.getJSONObject("parentArchive").getJSONObject("input").getString("sha256").equals(sha(original)),"Full parent APK identity absent");
            require(origin.getJSONObject("member").getString("acquiredSha256").equals(sha(members.get("AndroidManifest.xml"))),"Expanded child SHA differs");
            Files.write(apk,new byte[]{0}); // every later selection must use the owned parent.
            String stringsRequest=link(backend.roots(initial,RUN),"strings •").request;
            EvidenceRows strings=backend.open(stringsRequest,RUN);
            EvidenceRows.Row isolated=strings.row(6,RUN);
            List<InvestigationBackend.Link> actions=backend.related(stringsRequest,strings,isolated,RUN);
            InvestigationBackend.Link units=link(actions,"UTF-16LE code units");
            byte[] expected=new byte[20];String value="isolated-\ud800";
            for(int i=0;i<value.length();i++) { expected[2*i]=(byte)value.charAt(i);expected[2*i+1]=(byte)(value.charAt(i)>>>8); }
            require(Arrays.equals(expected,backend.binaryValue(units.request,RUN)),"Decoded surrogate code-unit export changed");
            try { backend.writeText(link(actions,"decoded pool string").request,new StringWriter(),RUN);throw new AssertionError("Unrepresentable UTF-8 export silently replaced data"); }
            catch(IOException error) { require(error.getMessage().contains("isolated UTF-16 surrogate"),"Wrong text export error"); }
            byte[] encoded=backend.binaryValue(link(actions,"encoded string payload").request,RUN);
            require(encoded.length>value.length(),"Original MUTF-8 payload was replaced with decoded UTF-8");
            PreparedExport staged=PreparedExport.bytes(directory.resolve("exports").toFile(),units.textName,backend.binaryValue(units.request,RUN),RUN);
            String chooser=link(backend.roots(stringsRequest,RUN),"Choose a compiled").request;
            EvidenceRows candidates=backend.open(chooser,RUN);
            require(candidates.size()==4,"Non-res/ XML candidate dropped");
            EvidenceRows.Row resource=candidates.row(1,RUN);
            String entriesRequest=backend.related(chooser,candidates,resource,RUN).get(0).request;
            EvidenceRows entries=backend.open(entriesRequest,RUN);
            require(entries.size()==9,"Owned parent failed after caller source expired");
            try { backend.binaryValue(units.request,RUN);throw new AssertionError("Stale string selector exported bytes from a different member"); }
            catch(IOException expectedError) { require(expectedError.getMessage().contains("scope is not active"),"Wrong stale selector error"); }
            String definitions=link(backend.related(entriesRequest,entries,entries.row(0,RUN),RUN),"sharing this resource ID").request;
            require(backend.open(definitions,RUN).size()==5,"Local ID navigation lost variants");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();staged.copyTo(bytes,RUN);staged.discard();
            require(Arrays.equals(expected,bytes.toByteArray()),"Changing members invalidated staged export");
            require(Objects.requireNonNull(cache.list()).length==2,"Old extracted resource snapshot leaked");
            backend.open(chooser,RUN);
            try { backend.open(backend.related(chooser,candidates,candidates.row(3,RUN),RUN).get(0).request,RUN);throw new AssertionError("Plain XML accepted as compiled XML"); }
            catch(IOException expectedError) {}
            require(Objects.requireNonNull(cache.list()).length==2,"Failed member parse leaked candidate or deleted valid snapshot");
            require(backend.open(entriesRequest,RUN).size()==9,"Failed member parse destroyed previous report");
        }
        empty(cache,"Closed resource provider leaked parent/member bytes");
        Files.write(apk,original);
        try(AndroidResourceBackend backend=new AndroidResourceBackend(apk.toFile(),cache,"fixture.apk",runner())) {
            try { backend.initialRequest(()->true);throw new AssertionError("Acquisition ignored cancellation"); }
            catch(InterruptedIOException expected) {}
        }
        empty(cache,"Cancelled resource acquisition leaked bytes");
        File raw=directory.resolve("resource-table.arsc").toFile();Files.write(raw.toPath(),ResourceFixtureFactory.table());
        try(AndroidResourceBackend backend=new AndroidResourceBackend(raw,cache,"resource-table.arsc",runner())) {
            require(backend.open(backend.initialRequest(RUN),RUN).size()==9,"Raw table workflow differs from APK member");
        }
        empty(cache,"Raw table workflow leaked snapshot");
    }
    private static void failures(Path directory) throws Exception {
        byte[] bytes=ResourceFixtureFactory.table();
        try { AndroidResourceProbe.scan(new ByteArrayInputStream(bytes),bytes.length+1,RUN);throw new AssertionError("Early EOF accepted"); }
        catch(IOException expected) { require(expected.getMessage().contains("ended"),"Wrong early EOF error"); }
        try { AndroidResourceProbe.scan(new ByteArrayInputStream(bytes),bytes.length-1,RUN);throw new AssertionError("Source growth accepted"); }
        catch(IOException expected) { require(expected.getMessage().contains("grew"),"Wrong source growth error"); }
        try { AndroidResourceProbe.scan(new ByteArrayInputStream(bytes),64L*1024*1024+1,RUN);throw new AssertionError("Input budget ignored"); }
        catch(IOException expected) {}
        try { AndroidResourceProbe.scan(new ByteArrayInputStream(bytes),bytes.length,()->true);throw new AssertionError("Report ignored cancellation"); }
        catch(InterruptedIOException expected) {}
        File file=directory.resolve("invalid-report.arsc").toFile();Files.write(file.toPath(),bytes);
        JSONObject altered=new JSONObject(probe(bytes)).put("networkUsed",true);
        try { new AndroidResourceDocument(file,"fixture",sha(bytes),altered.toString(),RUN);throw new AssertionError("Wrong worker mode accepted"); }
        catch(IOException expected) {}
        altered=new JSONObject(probe(bytes));altered.getJSONObject("tables").getJSONArray("entries").getJSONObject(0).put("physicalOffset",bytes.length);
        try { new AndroidResourceDocument(file,"fixture",sha(bytes),altered.toString(),RUN);throw new AssertionError("Out-of-source worker range accepted"); }
        catch(IOException expected) {}
        altered=new JSONObject(probe(bytes));altered.getJSONObject("tables").getJSONArray("strings").getJSONObject(0).put("payloadSizeBytes",Integer.MAX_VALUE);
        try { new AndroidResourceDocument(file,"fixture",sha(bytes),altered.toString(),RUN);throw new AssertionError("Oversized nested payload would allocate outside the trusted source range"); }
        catch(IOException expected) { require(expected.getMessage().contains("payload crosses"),"Wrong nested payload rejection"); }
    }
    private static void packagedReference(Path path) throws Exception {
        int parsed=0;
        JSONObject reference=new JSONObject(Files.readString(path.resolveSibling(path.getFileName().toString().replace(".apk",".reference.json"))));
        try(ZipFile apk=new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries=apk.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();String name=entry.getName();
                if(!name.equals("resources.arsc")&&!name.equals("AndroidManifest.xml")&&!name.startsWith("res/xml/")) continue;
                byte[] bytes;try(InputStream source=apk.getInputStream(entry)) { bytes=source.readAllBytes(); }
                JSONObject report=new JSONObject(probe(bytes));
                require(report.getBoolean("wireCoverageComplete"),"AAPT2 resource inventory incomplete");
                if(name.equals("resources.arsc")) {
                    require(report.getJSONObject("tables").getJSONArray("entries").length()>20,"AAPT2 resource entries absent");
                    require(report.getJSONObject("tables").getJSONArray("maps").length()>5,"AAPT2 styles/arrays/plurals absent");
                    JSONArray definitions=report.getJSONObject("tables").getJSONArray("entries");boolean dimension=false,fraction=false;
                    for(int i=0;i<definitions.length();i++) {
                        JSONObject definition=definitions.getJSONObject(i);if(!definition.has("value")) continue;
                        JSONObject value=definition.getJSONObject("value");
                        if(value.getInt("typeCode")==5) {
                            dimension=true;require(Math.abs(value.getDouble("decodedMagnitude")+12.5)<0.00001&&value.getString("unit").equals("dp"),"AAPT2 dimension interpretation differs");
                        } else if(value.getInt("typeCode")==6) {
                            fraction=true;require(Math.abs(value.getDouble("decodedMagnitude")-0.75)<0.00001&&value.getString("unit").equals("fraction of parent base"),"AAPT2 fraction interpretation differs");
                        }
                    }
                    require(dimension&&fraction,"AAPT2 typed scalar reference fixtures absent");
                    if(path.getFileName().toString().equals("sparse.apk")) {
                        JSONArray types=report.getJSONObject("tables").getJSONArray("types");boolean sparse=false;
                        for(int i=0;i<types.length();i++) sparse|=(types.getJSONObject(i).getInt("flags")&1)!=0;
                        require(sparse,"AAPT2 sparse optimizer fixture did not produce sparse entries");
                    }
                } else {
                    if(name.equals("AndroidManifest.xml")) {
                        AndroidResourceDocument document=document(path.getParent(),bytes);
                        SdkManifestNames names=new SdkManifestNames(document,RUN);
                        require(names.facts.size()==8&&names.diagnostics.isEmpty(),"Compiled SDK manifest name coverage differs");
                        boolean relativeActivity=false,relativeReceiver=false,action=false,permission=false,metadata=false,library=false;
                        for(SdkManifestNames.Fact fact:names.facts) {
                            require(fact.selection.equals("compiled android:name resource ID 0x01010003"),"AAPT2 android:name did not resolve through its original resource map");
                            if(fact.type==2) relativeActivity=fact.value.equals("org.diemobile.resource.reference.ReferenceActivity")&&fact.original.equals(".ReferenceActivity");
                            if(fact.type==3) relativeReceiver=fact.value.equals("org.diemobile.resource.reference.ReferenceReceiver")&&fact.original.equals("ReferenceReceiver");
                            action|=fact.type==9;permission|=fact.type==7;metadata|=fact.type==8;library|=fact.type==6;
                        }
                        require(relativeActivity&&relativeReceiver&&action&&permission&&metadata&&library,"Manifest class/action/permission/metadata/library inputs omitted");
                    }
                    JSONObject expected=reference.getJSONObject(name);
                    JSONArray events=report.getJSONObject("tables").getJSONArray("xml-events");int starts=0;
                    for(int i=0;i<events.length();i++) if(events.getJSONObject(i).getInt("chunkType")==0x102) starts++;
                    if(name.startsWith("res/xml/")) {
                        boolean cdata=false;
                        for(int i=0;i<events.length();i++) if(events.getJSONObject(i).getInt("chunkType")==0x104) {
                            JSONObject value=events.getJSONObject(i);cdata=true;
                            require(!value.getBoolean("typedValuePresent")&&value.getString("typedValueRawHex").equals("0000000000000000"),"Unused AAPT2 CDATA field became a false typed value");
                        }
                        require(cdata,"AAPT2 CDATA fixture absent");
                    }
                    require(starts==expected.getInt("startElements"),"XML element count differs from independent AAPT2 dump: "+name);
                    require(report.getJSONObject("tables").getJSONArray("attributes").length()==expected.getInt("attributes"),"XML attribute count differs from independent AAPT2 dump: "+name);
                }
                parsed++;
            }
        }
        require(parsed>=3,"AAPT2 manifest/table/compiled XML fixtures missing");
        System.out.println("AAPT2 packaged reference parsed: "+path.getFileName()+" ("+parsed+" compiled files)");
    }
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);rawReports(directory);provider(directory);failures(directory);
        for(int i=1;i<args.length;i++) packagedReference(Path.of(args[i]));
        System.out.println("Resource investigation fixtures passed: real Google worker reports, XML attributes/styles, all typed entry layouts/aliases/maps, exact Unicode/surrogates/bytes, full-value search/paging/exports, immutable parent/member provenance, resource-ID navigation, staging, cancellation and bounded failures.");
    }
}
