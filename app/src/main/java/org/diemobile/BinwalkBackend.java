// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Entire-blob signatures retain overlaps, parser failures and unknown extents. */
final class BinwalkBackend extends InvestigationBackend {
    static final long MAX_BYTES=64L*1024*1024;
    private final File root,directory;
    private final String name;
    private final BinaryRunner runner;
    private InputSnapshot snapshot;
    private JSONObject header;
    private JSONArray candidates,patterns;
    BinwalkBackend(File root,File directory,String name,BinaryRunner runner){this.root=root;this.directory=directory;this.name=name;this.runner=runner;}
    private void acquire(BooleanSupplier stop)throws Exception {
        if(header!=null)return;InputSnapshot input=InputSnapshot.acquire(root,directory,MAX_BYTES,stop);boolean success=false;
        try{
            String encoded=runner.scan(input.file,stop);EvidenceRows.check(stop);
            if(encoded==null||encoded.length()>32*1024*1024)throw new IOException("Binary worker returned no report or exceeded 32 Mi-characters");
            JSONObject response=new JSONObject(encoded);
            if(!response.optBoolean("ok"))throw new IOException(response.optString("error","Binary worker failed without a diagnostic"));
            if(!response.optString("schema").equals("org.diemobile.binwalk-evidence")||response.optInt("schemaVersion")!=1||response.getLong("fileSizeBytes")!=input.size
                ||!response.getString("sourceRevision").equals("26713972e3f9f52dc37d4a421c4554b0bd9e82ef"))throw new IOException("Binary worker report schema/input/source mismatch");
            JSONArray rows=response.getJSONArray("rows"),catalog=response.getJSONArray("patterns");
            if(rows.length()>20000||catalog.length()>10000||rows.length()!=response.getInt("candidateCount")||catalog.length()!=response.getInt("patternCount"))throw new IOException("Binary worker exceeded budgets or returned incomplete candidate/catalog counts");
            if(!response.getBoolean("fullBlobSearch")||response.getBoolean("nestedResultsPruned")||response.getBoolean("unknownExtentsInferred")||response.getBoolean("externalExtractorsExecuted"))throw new IOException("Binary worker analysis mode differs from the requested read-only complete-blob scope");
            for(int i=0;i<rows.length();i++){
                EvidenceRows.check(stop);JSONObject row=rows.getJSONObject(i);long offset=row.getLong("physicalOffset");
                if(offset<0||offset>=input.size)throw new IOException("Binwalk candidate location outside input");
                long magic=row.getLong("magicOffset");int pattern=row.getInt("patternIndex");if(magic<0||magic>=input.size||pattern<0||pattern>=catalog.length())throw new IOException("Binwalk magic/pattern selector outside its declared scope");
                if(!row.isNull("reportedSizeBytes")){long count=row.getLong("reportedSizeBytes");if(count<=0||count>input.size-offset||!row.getBoolean("parserAccepted"))throw new IOException("Binwalk extent is not a bounded accepted parser result");}
            }
            response.remove("rows");response.remove("patterns");header=response;patterns=catalog;candidates=rows;snapshot=input;success=true;
        }finally{if(!success)input.close();}
    }
    @Override String initialRequest(BooleanSupplier stop){return "accepted";}
    @Override EvidenceRows open(String request,BooleanSupplier stop)throws Exception {
        acquire(stop);
        if(request.equals("catalog"))return new EvidenceRows("binwalk-patterns","Binwalk signature pattern catalog"){
            int size(){return patterns.length();}
            Row row(int i,BooleanSupplier cancelled)throws Exception{EvidenceRows.check(cancelled);JSONObject value=new JSONObject(patterns.getJSONObject(i).toString());return new Row(i,value.getString("name"),value.getString("description")+" • "+value.getString("magicHex"),null,null,value);}
            JSONObject metadata()throws Exception{return BinwalkBackend.this.metadata().put("scope","Every compiled Binwalk magic pattern, including short patterns");}
        };
        if(!(request.equals("all")||request.equals("accepted")||request.equals("unresolved")||request.startsWith("format:")))throw new IOException("Unknown Binwalk scope");
        int[] selected=new int[candidates.length()];int count=0;
        for(int i=0;i<candidates.length();i++){EvidenceRows.check(stop);JSONObject value=candidates.getJSONObject(i);boolean accepted=value.getBoolean("parserAccepted");
            if(request.equals("all")||request.equals("accepted")&&accepted||request.equals("unresolved")&&!accepted||request.startsWith("format:")&&request.substring(7).equals(value.getString("name")))selected[count++]=i;
        }
        final int[] indices=Arrays.copyOf(selected,count);
        return new EvidenceRows("binwalk-candidates","Binwalk • "+request+" • "+name){
            int size(){return indices.length;}
            Row row(int i,BooleanSupplier cancelled)throws Exception{
                EvidenceRows.check(cancelled);int original=indices[i];JSONObject value=new JSONObject(candidates.getJSONObject(original).toString());
                Long length=value.isNull("reportedSizeBytes")?null:value.getLong("reportedSizeBytes");boolean accepted=value.getBoolean("parserAccepted");
                value.put("interpretation",accepted?"Upstream parser accepted this candidate. Extent, when supplied, is a parser report bounded to this source; it is not proof of provenance, complete decoding, safety or behavior.":"Magic bytes matched but the upstream parser rejected or could not resolve this candidate. Only its observed magic location is established; no extraction extent is inferred.");
                return new Row(original,value.getString("name"),value.getString("parserStatus")+" • "+value.getString("description"),value.getLong("physicalOffset"),length,value);
            }
            JSONObject metadata()throws Exception{return BinwalkBackend.this.metadata().put("selectedScope",request).put("selectedRows",indices.length);}
        };
    }
    private JSONObject metadata()throws Exception {
        return new JSONObject(header.toString()).put("scope","Every raw magic pattern across the entire supported private blob; short patterns included; nested/overlapping results retained. No inferred next-hit/EOF size and no automatic external extraction.")
            .put("confidenceMeaning","Raw Binwalk parser confidence (0/128/250 conventions), not a security score or proof that a recovered file fully decodes.")
            .put("validationLimits","Format-specific upstream validation varies. Some parsers decompress in memory. A known reported extent enables exact stored-byte export; an unknown extent remains null. Rejected/panicked candidates are visible separately.");
    }
    @Override List<Link> roots(String request,BooleanSupplier stop)throws Exception {
        List<Link> result=new ArrayList<>();result.add(Link.view("Parser-accepted candidates","accepted"));result.add(Link.view("All signature candidates / overlaps","all"));result.add(Link.view("Rejected / unresolved magic candidates","unresolved"));result.add(Link.view("Complete signature / magic catalog","catalog"));
        TreeSet<String> names=new TreeSet<>();for(int i=0;i<candidates.length();i++)names.add(candidates.getJSONObject(i).getString("name"));for(String format:names)result.add(Link.view("Format: "+format,"format:"+format));return result;
    }
    @Override List<Link> related(String request,EvidenceRows source,EvidenceRows.Row row,BooleanSupplier stop)throws Exception {EvidenceRows.check(stop);return Arrays.asList(Link.view("All candidates for this format","format:"+row.json().getString("name")),Link.view("Inspect the actual magic catalog","catalog"));}
    @Override JSONObject provenance()throws Exception {return metadata().put("input",new JSONObject().put("name",name).put("sizeBytes",snapshot.size).put("sha256",snapshot.sha256));}
    @Override File byteSource(){return snapshot==null?root:snapshot.file;}
    @Override String sourceName(){return name;}
    @Override String help(String request){return (header!=null&&header.optInt("parserPanics")>0?"PARTIAL: "+header.optInt("parserPanics")+" upstream parser failures; inspect unresolved candidates. ":"")+"Binwalk whole-blob investigation • accepted candidates, unresolved headers, overlaps and the full pattern catalog remain available. Parser confidence is not a verdict. Only reported bounded extents enable stored-range extraction; unknown lengths stay unknown. Tap here for engine/process/coverage details.";}
    @Override public void close()throws Exception{try{runner.close();}finally{if(snapshot!=null)snapshot.close();}}
}
