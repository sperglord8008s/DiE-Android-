// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import org.json.*;

/** Actual serialized multidex/container inputs, acquisition identities and complete ambiguity. */
public final class DexDefinitionsTest {
    private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static InvestigationBackend.Link link(List<InvestigationBackend.Link> links,String label) {
        return links.stream().filter(value->value.label.contains(label)).findFirst().orElseThrow(()->new AssertionError("Missing link: "+label));
    }
    private static EvidenceRows.Row row(EvidenceRows rows,String name) throws Exception {
        for(int i=0;i<rows.size();i++) { EvidenceRows.Row row=rows.row(i,()->false);if(row.name.equals(name)||row.name.contains(name)) return row; }
        throw new AssertionError("Missing original row: "+name);
    }
    private static String root(DexBackend backend,String request,String kind) throws Exception {
        for(InvestigationBackend.Link link:backend.roots(request,()->false)) {
            JSONObject value=new JSONObject(link.request);JSONObject dex=value.optJSONObject("dex");if(dex!=null&&dex.getString("kind").equals(kind)) return link.request;
        }
        throw new AssertionError("Missing root kind "+kind);
    }
    private static void zip(File file,LinkedHashMap<String,byte[]> members,boolean stored) throws Exception {
        try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(file))) {
            for(Map.Entry<String,byte[]> member:members.entrySet()) {
                ZipEntry entry=new ZipEntry(member.getKey());if(stored) { CRC32 crc=new CRC32();crc.update(member.getValue());entry.setMethod(ZipEntry.STORED);entry.setSize(member.getValue().length);entry.setCrc(crc.getValue()); }
                output.putNextEntry(entry);output.write(member.getValue());output.closeEntry();
            }
        }
    }
    private static LinkedHashMap<String,byte[]> members(byte[] second) throws Exception {
        LinkedHashMap<String,byte[]> members=new LinkedHashMap<>();members.put("readme.txt",new byte[]{1});members.put("classes.dex",DexFixtureFactory.definitionConsumer());
        members.put("classes2.dex",DexFixtureFactory.definitionOwner("Owner-A.smali"));members.put("assets/extra.DEX",second);return members;
    }
    private static DexBackend backend(File input,File directory) throws Exception { return new DexBackend(input,directory,input.getName(),Inspector.scan(input,4).sha256,input.length()); }
    private static String consumer(DexBackend backend) throws Exception {
        String inputs=backend.initialRequest(()->false);EvidenceRows rows=backend.open(inputs,()->false);
        String request=link(backend.related(inputs,rows,row(rows,"classes.dex"),()->false),"Inspect").request;backend.open(request,()->false);return request;
    }
    private static String resolve(DexBackend backend,String current,String kind,String descriptor) throws Exception {
        String pool=root(backend,current,kind);EvidenceRows rows=backend.open(pool,()->false);
        return link(backend.related(pool,rows,row(rows,descriptor),()->false),"exact declarations across captured").request;
    }
    private static void empty(File directory,String message) { require(directory.list()!=null&&directory.list().length==0,message); }
    public static void main(String[] args) throws Exception {
        Path temporary=Path.of(args[0]);File input=temporary.resolve("definitions.apk").toFile(),cache=temporary.resolve("definition-owned").toFile();
        byte[] ownerB=DexFixtureFactory.definitionOwner("Owner-B.smali");zip(input,members(ownerB),false);String parentSha=Inspector.scan(input,4).sha256;
        PreparedExport stage;
        try(DexBackend backend=backend(input,cache)) {
            String initial=consumer(backend),sources=link(backend.roots(initial,()->false),"Captured multidex sources").request;
            EvidenceRows inputs=backend.open(sources,()->false);require(inputs.size()==3&&inputs.metadata().getBoolean("completeInSelectedScope"),"Complete candidate acquisition failed");
            require(inputs.row(0,()->false).json().getInt("archiveMemberIndex")==1,"Filtered candidate ordinal replaced the original archive index");
            File manifest=backend.byteSource();JSONObject inputFacts=new JSONObject(Files.readString(manifest.toPath()));
            require(inputFacts.getString("schema").equals("org.diemobile.dex-definition-index")&&backend.provenance().getJSONObject("input").getString("sha256").equals(Inspector.scan(manifest,4).sha256),"Index manifest does not identify its own byte source");
            String classRoot=link(backend.roots(sources,()->false),"Class definitions across all").request;EvidenceRows classes=backend.open(classRoot,()->false);
            require(classes.size()==3&&classes.select(DexFixtureFactory.SHARED_OWNER,()->false).count==2,"Duplicate class definitions were normalized away");
            require(classes.select("initialization",()->false).count==0,"Shared interpretation text polluted evidence search");
            String methodRequest=resolve(backend,classRoot,"method-refs",DexFixtureFactory.SHARED_OWNER+"->value(I)I");EvidenceRows methods=backend.open(methodRequest,()->false);
            require(methods.size()==2&&methods.metadata().getJSONObject("lookup").getBoolean("completeInSelectedScopes"),"Exact parameter/return signature not retained across duplicate owners");
            require(methods.row(0,()->false).physicalOffset==null&&methods.row(0,()->false).sizeBytes==null,"A target DEX offset was assigned to index-manifest bytes");
            require(methods.row(0,()->false).json().getInt("targetArchiveMemberIndex")==2&&methods.row(1,()->false).json().getInt("targetArchiveMemberIndex")==3,"All candidates/source order were not retained");
            String fieldRequest=resolve(backend,methodRequest,"field-refs",DexFixtureFactory.SHARED_OWNER+"->VALUE:I");
            require(backend.open(fieldRequest,()->false).size()==2,"Field name matched the wrong field type or lost an owner");
            String typeRequest=resolve(backend,fieldRequest,"types",DexFixtureFactory.SHARED_OWNER);require(backend.open(typeRequest,()->false).size()==2,"Type-to-class declaration association missing");
            String arrayRequest=resolve(backend,typeRequest,"types","["+DexFixtureFactory.SHARED_OWNER);
            require(backend.open(arrayRequest,()->false).size()==0,"Array type was silently reinterpreted as its component class");
            String external=resolve(backend,arrayRequest,"method-refs","Ljava/lang/System;->currentTimeMillis()J");require(backend.open(external,()->false).size()==0,"Framework implementation was invented");
            String inherited=resolve(backend,external,"method-refs",DexFixtureFactory.CONSUMER+"->value(I)I");require(backend.open(inherited,()->false).size()==0,"Inherited/dynamic resolution was claimed as an exact local declaration");
            String handles=root(backend,inherited,"method-handles");EvidenceRows handleRows=backend.open(handles,()->false);
            require(handleRows.size()==2,"Both method/field method handles were not serialized");
            for(int i=0;i<handleRows.size();i++) {
                String fromHandle=link(backend.related(handles,handleRows,handleRows.row(i,()->false),()->false),"Method-handle member • exact declarations").request;
                EvidenceRows resolved=backend.open(fromHandle,()->false);require(resolved.size()==2,"Actual method/field handle member lost exact declaration navigation");
            }
            require(input.delete(),"Fixture parent not deleted");methods=backend.open(methodRequest,()->false);require(methods.size()==2,"Parent expiry invalidated owned DEX snapshots");
            InvestigationBackend.Link target=link(backend.related(methodRequest,methods,methods.row(0,()->false),()->false),"Open this exact");
            EvidenceRows declarations=backend.open(target.request,()->false);EvidenceRows.Row selected=declarations.row(target.row,()->false);
            require(backend.provenance().getJSONObject("input").getString("sha256").equals(methods.row(0,()->false).json().getString("targetDexSha256")),"Jump selected the wrong child bytes");
            require(backend.provenance().getJSONObject("parentInput").getString("sha256").equals(parentSha)&&selected.physicalOffset.equals(methods.row(0,()->false).json().getLong("targetPhysicalOffset")),"Parent identity/target address domain changed after expiry");
            String code=link(backend.related(target.request,declarations,selected,()->false),"Method bytecode").request;StringWriter smali=new StringWriter();backend.writeText(code,smali,()->false);
            require(smali.toString().contains("value(I)I")&&smali.toString().contains("const/4")&&smali.toString().contains("return "),"Actual target method smali unavailable: "+smali);
            methods=backend.open(methodRequest,()->false);final EvidenceRows captured=methods;JSONObject provenance=backend.provenance();
            stage=PreparedExport.stream(temporary.resolve("definition-exports").toFile(),"definitions.json","application/json",out->captured.writeReport(out,provenance,"no-row-could-match",9,()->false),()->false);
            EvidenceRows acquired=backend.open(sources,()->false);InvestigationBackend.Link blob=link(backend.related(sources,acquired,acquired.row(2,()->false),()->false),"Export exact");
            require(Arrays.equals(backend.binaryValue(blob.request,()->false),ownerB),"Captured source-byte export changed the DEX");
        }
        empty(cache,"Closed multidex snapshots leaked");ByteArrayOutputStream saved=new ByteArrayOutputStream();stage.copyTo(saved,()->false);stage.discard();
        require(new JSONObject(saved.toString(StandardCharsets.UTF_8)).getJSONArray("rows").length()==2,"Staged full ambiguity report depended on live parsers/filter/page");
        // A CRC failure must exclude otherwise valid DEX bytes (including valid header checksums).
        byte[] overlaid=Arrays.copyOf(ownerB,ownerB.length+1);zip(input,members(overlaid),true);byte[] container=Files.readAllBytes(input.toPath());
        int payload=find(container,overlaid);require(payload>=0,"Stored fixture payload missing");container[payload+overlaid.length-1]^=1;Files.write(input.toPath(),container);
        try(DexBackend backend=backend(input,cache)) {
            String origin=consumer(backend),request=resolve(backend,origin,"method-refs",DexFixtureFactory.SHARED_OWNER+"->value(I)I");EvidenceRows result=backend.open(request,()->false);
            require(result.size()==1&&!result.metadata().getJSONObject("lookup").getBoolean("completeInSelectedScopes")&&result.title.startsWith("PARTIAL"),"CRC-failed scope produced verified code or confident absence");
            require(result.metadata().toString().contains("CRC32 mismatch"),"Acquisition failure was hidden");
        }
        empty(cache,"CRC failure leaked snapshots");
        zip(input,members(DexFixtureFactory.foreignMethodOwner(ownerB)),false);
        try(DexBackend backend=backend(input,cache)) {
            String origin=consumer(backend),request=resolve(backend,origin,"method-refs",DexFixtureFactory.SHARED_OWNER+"->value(I)I");EvidenceRows result=backend.open(request,()->false);
            require(result.size()==1&&result.metadata().getJSONObject("lookup").getInt("queryFailures")==1&&!result.metadata().getJSONObject("lookup").getBoolean("completeInSelectedScopes"),"Foreign identifier owner became an exact declaration");
        }
        zip(input,members(DexFixtureFactory.duplicateMethodDefinition(ownerB)),false);
        try(DexBackend backend=backend(input,cache)) {
            String origin=consumer(backend),request=resolve(backend,origin,"method-refs",DexFixtureFactory.SHARED_OWNER+"->value(I)I");EvidenceRows result=backend.open(request,()->false);
            require(result.size()==3,"Duplicate encoded class_data methods were silently deduplicated");
            JSONObject first=result.row(1,()->false).json(),second=result.row(2,()->false).json();
            require(first.getLong("targetPhysicalOffset")==second.getLong("targetPhysicalOffset")&&first.getInt("targetDeclarationIndex")!=second.getInt("targetDeclarationIndex"),"Identifier offsets were confused with duplicate declaration identities");
        }
        // No borrowed parent or old selected input is lost when publication is cancelled.
        zip(input,members(ownerB),false);AtomicBoolean stop=new AtomicBoolean();AtomicInteger builds=new AtomicInteger();
        try(DexBackend backend=new DexBackend(input,cache,input.getName(),Inspector.scan(input,4).sha256,input.length(),(root,origin,cancelled)->{
            DexDefinitionIndex index=DexDefinitionIndex.acquire(root,origin,cancelled);if(builds.incrementAndGet()==1) stop.set(true);return index;
        })) {
            String origin=consumer(backend);File before=backend.byteSource();String request=link(backend.roots(origin,()->false),"Captured multidex").request;
            try { backend.open(request,stop::get);throw new AssertionError("Late cancellation published its candidate"); } catch(InterruptedIOException expected) {}
            require(before.isFile()&&cache.list().length==1,"Cancelled candidate leaked or deleted the prior snapshot");stop.set(false);
            require(backend.open(request,stop::get).size()==3,"Cancelled definition publication could not recover");
        }
        empty(cache,"Late cancellation/recovery leaked snapshots");
        zip(input,members(ownerB),false);String restored;
        try(DexBackend backend=backend(input,cache)) {
            String initial=backend.initialRequest(()->false);backend.open(initial,()->false);restored=link(backend.roots(initial,()->false),"Class definitions across all").request;
        }
        try(DexBackend backend=backend(input,cache)) { require(backend.open(restored,()->false).size()==3,"Restored global query forgot its APK acquisition mode"); }
        File shared=temporary.resolve("definitions-041.dex").toFile();Files.write(shared.toPath(),DexFixtureFactory.container41(ownerB,true));
        try(DexBackend backend=backend(shared,cache)) {
            String initial=backend.initialRequest(()->false);EvidenceRows rows=backend.open(initial,()->false);
            String request=link(backend.related(initial,rows,rows.row(0,()->false),()->false),"Every exact class declaration").request;
            EvidenceRows definitions=backend.open(request,()->false);require(definitions.size()==2,"041 shared definitions were deduplicated");
            require(definitions.row(0,()->false).json().getLong("targetPhysicalOffset")==definitions.row(1,()->false).json().getLong("targetPhysicalOffset")
                &&definitions.row(1,()->false).json().getInt("targetEntryIndex")==1,"041 absolute shared addresses were rebased");
            String method=resolve(backend,request,"method-refs",DexFixtureFactory.SHARED_OWNER+"->value(I)I");EvidenceRows declarations=backend.open(method,()->false);
            require(declarations.size()==2&&declarations.row(1,()->false).json().getInt("targetEntryIndex")==1,"041 shared method declarations were not retained");
            InvestigationBackend.Link target=link(backend.related(method,declarations,declarations.row(1,()->false),()->false),"Open this exact");
            EvidenceRows methods=backend.open(target.request,()->false);String code=link(backend.related(target.request,methods,methods.row(target.row,()->false),()->false),"Method bytecode").request;
            StringWriter text=new StringWriter();backend.writeText(code,text,()->false);require(text.toString().contains("value(I)I")&&text.toString().contains("const/4"),"041 second-entry method could not retain absolute shared code addresses");
        }
        empty(cache,"041 snapshots leaked");require(input.isFile()&&shared.isFile(),"Closing definitions deleted borrowed inputs");
        // A same-length parent replacement must not mix an old origin with new siblings.
        byte[] original=Files.readAllBytes(input.toPath());
        try(DexBackend backend=backend(input,cache)) {
            String initial=consumer(backend);File previous=backend.byteSource();byte[] changed=original.clone();changed[changed.length-1]^=1;Files.write(input.toPath(),changed);
            String request=link(backend.roots(initial,()->false),"Captured multidex sources").request;
            try { backend.open(request,()->false);throw new AssertionError("Mixed parent/origin identities accepted"); } catch(IOException expected) { require(expected.getMessage().contains("SHA-256"),"Parent identity failed for an unrelated reason"); }
            require(previous.isFile()&&cache.list().length==1,"Failed parent acquisition clobbered the old selection or leaked files");
            Files.write(input.toPath(),original);require(backend.open(request,()->false).size()==3,"Corrected parent could not recover");
        }
        empty(cache,"Parent identity recovery leaked snapshots");
        // Excess candidate counts fail before extracting a partial source set.
        LinkedHashMap<String,byte[]> tooMany=new LinkedHashMap<>();for(int i=0;i<129;i++) tooMany.put("part"+i+".dex",new byte[0]);zip(input,tooMany,false);
        try {
            DexDefinitionIndex.acquire(new DexDefinitionIndex.Root(input,cache,input.getName(),true,null,input.length()),null,()->false);
            throw new AssertionError("129-source scope accepted");
        } catch(IOException expected) { require(expected.getMessage().contains("128"),"Candidate budget failed for an unrelated reason"); }
        empty(cache,"Excess source candidates leaked parent data");
        // Successfully acquired but subsequently changed cache bytes are never promoted as code.
        zip(input,members(ownerB),false);DexDefinitionIndex.Root root=new DexDefinitionIndex.Root(input,cache,input.getName(),true,null,input.length());
        try(DexDefinitionIndex index=DexDefinitionIndex.acquire(root,null,()->false)) {
            DexDefinitionIndex.Source first=index.source(1),second=index.source(2);DexDocument origin=index.load(first,()->false);
            byte[] changed=Files.readAllBytes(second.file.toPath());changed[12]^=1;Files.write(second.file.toPath(),changed);
            DexDocument.Entry entry=origin.entries.get(0);int method=-1;
            for(int i=0;i<entry.dex.getMethodSection().size();i++) if(DexValues.reference(entry.dex.getMethodSection().get(i),()->false).equals(DexFixtureFactory.SHARED_OWNER+"->value(I)I")) method=i;
            require(method>=0,"Independent origin method reference missing");
            EvidenceRows result=DexDefinitionLookup.resolve(index,1,origin,new DexQuery(0,"definitions",-1,-1,com.android.tools.smali.dexlib2.ReferenceType.METHOD,method),()->false);
            require(result.size()==1&&!result.metadata().getJSONObject("lookup").getBoolean("completeInSelectedScopes")&&result.metadata().toString().contains("SHA-256 changed"),"Changed source bytes produced trusted declarations or confident absence");
            long manifestSize=index.manifestSize;require(index.manifest.delete(),"Manifest fixture did not expire");
            require(index.provenance().getJSONObject("input").getLong("sizeBytes")==manifestSize&&!index.provenance().getBoolean("indexManifestAvailable"),"Expired manifest changed captured identity");
        }
        empty(cache,"Changed-source/manifest expiry leaked snapshots");
        byte[] padded=Arrays.copyOf(ownerB,DexDocument.MAX_BYTES);LinkedHashMap<String,byte[]> aggregate=new LinkedHashMap<>();
        aggregate.put("readme.txt",new byte[]{1});aggregate.put("classes.dex",DexFixtureFactory.definitionConsumer());
        aggregate.put("classes2.dex",padded);aggregate.put("classes3.dex",padded);aggregate.put("classes4.dex",padded);zip(input,aggregate,false);
        try(DexBackend backend=backend(input,cache)) {
            String origin=consumer(backend),request=link(backend.roots(origin,()->false),"Captured multidex sources").request;
            EvidenceRows sources=backend.open(request,()->false);JSONObject facts=sources.metadata();
            require(sources.size()==4&&!facts.getBoolean("completeInSelectedScope")&&facts.getLong("reservedDexBytes")<DexDefinitionIndex.MAX_DEX_BYTES,"Aggregate source-byte budget lost explicit partial coverage");
            require(sources.row(3,()->false).json().getString("diagnostic").contains("96 MiB")&&sources.row(3,()->false).json().isNull("sha256"),"Over-budget member was expanded or given invented identity");
            String method=resolve(backend,request,"method-refs",DexFixtureFactory.SHARED_OWNER+"->value(I)I");EvidenceRows matches=backend.open(method,()->false);
            require(matches.size()==2&&!matches.metadata().getJSONObject("lookup").getBoolean("completeInSelectedScopes"),"Known declarations were lost or excluded scopes became a uniqueness claim");
        }
        empty(cache,"Aggregate reservation limit leaked large snapshots");
        System.out.println("DEX definition fixtures passed: real multidex overload/field/type/handle/041 lookup, all duplicate scopes, correct target bytes/smali, complete staged exports, parent expiry, CRC/foreign-owner partial diagnostics, late cancellation cleanup/recovery and restored acquisition mode.");
        if(args.length>1) {
            File reference=new File(args[1]);String sha=Inspector.scan(reference,4).sha256;
            try(DexDefinitionIndex index=DexDefinitionIndex.acquire(new DexDefinitionIndex.Root(reference,cache,reference.getName(),true,sha,reference.length()),null,()->false)) {
                System.out.println("Optional local APK index audit: SHA-256 "+sha+"; sources="+index.sources.size()+", classes="+index.classes.size()+", completeInSelectedScope="+index.complete()+", reserved DEX bytes="+index.reservedBytes);
                for(DexDefinitionIndex.Source source:index.sources) if(!source.error.isEmpty()) System.out.println("Source "+source.index+" not indexed: "+source.error);
            }
            empty(cache,"Optional reference acquisition leaked snapshots");
        }
    }
    private static int find(byte[] all,byte[] expected) {
        outer: for(int i=0;i<=all.length-expected.length;i++) { for(int j=0;j<expected.length;j++) if(all[i+j]!=expected[j]) continue outer;return i; }return -1;
    }
}
