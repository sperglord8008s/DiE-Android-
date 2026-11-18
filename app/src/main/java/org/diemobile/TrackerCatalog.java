// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Offline imported data, independent matcher and complete original-record evidence. */
final class TrackerCatalog {
    static final int MAX_BYTES=8*1024*1024;
    static final String REFERENCE="8a18f43f16f1727438c5dd8f18a8a31301e1df9a";
    static final String REFERENCE_SHA256="4569ea6464d888f9edda362cc392247934db50bb598b35655e7dec99c1d230a1";
    static final class Rule {
        final TrackerJson.Slice slice;
        final int index;
        final String id,name,signature,status,diagnostic;
        final Pattern pattern;
        Rule(TrackerJson.Slice slice,int index,int[] budget) throws Exception {
            this.slice=slice;this.index=index;id=slice.key;
            JSONObject fields=slice.decoded instanceof JSONObject?(JSONObject)slice.decoded:null;
            name=fields!=null&&fields.opt("name") instanceof String?fields.getString("name"):id;
            Object code=fields==null?null:fields.opt("code_signature");signature=code instanceof String?(String)code:"";
            String state="unsupported",error="";Pattern compiled=null;
            if(fields==null) error="Tracker record is not an object";
            else if(!(code instanceof String)) error="Missing/non-string code_signature; other fields are preserved";
            else if(signature.codePointCount(0,signature.length())<=3) { state="ignored-short";error="Not evaluated: the upstream detection gate requires more than three Unicode code points; short-pattern syntax is not verified here"; }
            else {
                try {
                    if(signature.length()>1024||signature.length()>512*1024-budget[0]) throw new IOException("Per-pattern / aggregate pattern text budget exceeded");
                    budget[0]+=signature.length();compiled=TrackerRegex.compile(signature);
                    if(compiled.programSize()>8192||compiled.programSize()>65536-budget[1]) throw new IOException("Per-pattern / aggregate compiled instruction budget exceeded");
                    budget[1]+=compiled.programSize();state="compiled";
                } catch(IOException unsupported) { compiled=null;error=unsupported.getMessage(); }
            }
            pattern=compiled;status=state;diagnostic=error;
        }
    }
    static final class Limits {
        final long comparisons,units,cost,nanos;
        Limits() { this(2000000,128L*1024*1024,1000000000,25000000000L); }
        Limits(long comparisons,long units,long cost,long nanos) { this.comparisons=comparisons;this.units=units;this.cost=cost;this.nanos=nanos; }
    }
    static final class Evaluation {
        final Rule rule;
        final String status,descriptor,input;
        final int start,end;
        Evaluation(Rule rule,String status,String descriptor,String input,int start,int end) { this.rule=rule;this.status=status;this.descriptor=descriptor;this.input=input;this.start=start;this.end=end; }
    }
    static final class Result {
        final List<Evaluation> evaluations=new ArrayList<>();
        final List<String> unsupportedInputs=new ArrayList<>();
        long comparisons,units,cost;
        int inputCount,hits,unsupportedRules,unsearched;
        String limit="";
        boolean complete() { return unsupportedInputs.isEmpty()&&unsupportedRules==0&&unsearched==0; }
    }
    final byte[] bytes;
    final String text,sha256;
    final List<Rule> rules;
    final List<TrackerJson.Slice> metadata;
    private TrackerCatalog(byte[] bytes,BooleanSupplier stop) throws Exception {
        this.bytes=bytes;sha256=Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        TrackerJson parsed=new TrackerJson(text,stop);metadata=Collections.unmodifiableList(parsed.metadata);
        List<Rule> records=new ArrayList<>();int[] budget={0,0};long started=System.nanoTime();
        for(TrackerJson.Slice slice:parsed.records) {
            EvidenceRows.check(stop);if(System.nanoTime()-started>15000000000L) throw new IOException("Tracker compilation exceeds 15-second cooperative budget; snapshot was not activated");
            records.add(new Rule(slice,records.size(),budget));
        }
        rules=Collections.unmodifiableList(records);
    }
    static TrackerCatalog read(InputStream input,BooleanSupplier stop) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[32768];int count;
        while((count=input.read(buffer))!=-1) {
            EvidenceRows.check(stop);if(count>MAX_BYTES-output.size()) throw new IOException("Exodus snapshot exceeds 8 MiB");output.write(buffer,0,count);
        }
        EvidenceRows.check(stop);return new TrackerCatalog(output.toByteArray(),stop);
    }
    static TrackerCatalog install(InputStream input,File destination,BooleanSupplier stop) throws Exception {
        TrackerCatalog candidate=read(input,stop);File parent=destination.getParentFile();
        if(!parent.isDirectory()&&!parent.mkdirs()) throw new IOException("Cannot create private tracker directory");
        File stage=File.createTempFile("exodus-import-",".tmp",parent);
        try {
            try(OutputStream output=new FileOutputStream(stage)) { output.write(candidate.bytes); }
            EvidenceRows.check(stop);
            java.nio.file.Files.move(stage.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return candidate;
        } finally { stage.delete(); }
    }
    Result match(Collection<String> descriptors,BooleanSupplier stop) throws Exception { return match(descriptors,stop,new Limits()); }
    Result match(Collection<String> descriptors,BooleanSupplier stop,Limits limits) throws Exception {
        if(descriptors.size()>120000) throw new IOException("Tracker class evidence exceeds 120,000 names");
        Result result=new Result();List<String> inputs=new ArrayList<>(),originals=new ArrayList<>();long inputUnits=0;
        // Stable first evidence, retaining slash names directly from the DEX descriptor.
        for(String descriptor:new TreeSet<>(descriptors)) {
            EvidenceRows.check(stop);result.inputCount++;
            if(result.inputCount>120000||(inputUnits+=descriptor.length())>16L*1024*1024) throw new IOException("Tracker class evidence exceeds 120,000 names / 16 Mi UTF-16 units");
            if(!descriptor.startsWith("L")||!descriptor.endsWith(";")||descriptor.length()<3) throw new IOException("Tracker input is not an original object-class descriptor");
            String input=descriptor.substring(1,descriptor.length()-1);
            if(!TrackerRegex.inputSupported(input)) { result.unsupportedInputs.add(descriptor);continue; }
            inputs.add(input);originals.add(descriptor);
        }
        long started=System.nanoTime();
        for(Rule rule:rules) {
            EvidenceRows.check(stop);
            if(rule.pattern==null) {
                if(rule.status.equals("unsupported")) result.unsupportedRules++;
                result.evaluations.add(new Evaluation(rule,rule.status,"","",-1,-1));continue;
            }
            Evaluation evaluation=null;
            for(int i=0;i<inputs.size();i++) {
                EvidenceRows.check(stop);String input=inputs.get(i);long cost=Math.max(1,input.length())*(long)rule.pattern.programSize();
                if(!result.limit.isEmpty()||result.comparisons>=limits.comparisons||input.length()>limits.units-result.units||cost>limits.cost-result.cost||System.nanoTime()-started>limits.nanos) {
                    result.limit="Matching comparison/text/estimated-instruction/time budget reached; remaining searches are unknown";break;
                }
                result.comparisons++;result.units+=input.length();result.cost+=cost;
                Matcher found=rule.pattern.matcher(input);
                if(found.find()) { evaluation=new Evaluation(rule,"matched",originals.get(i),input,found.start(),found.end());result.hits++;break; }
            }
            if(evaluation==null) {
                String status=!result.limit.isEmpty()?"not-fully-searched":result.unsupportedInputs.isEmpty()?"no-match":"no-match-in-supported-inputs";
                if(status.equals("not-fully-searched")) result.unsearched++;
                evaluation=new Evaluation(rule,status,"","",-1,-1);
            }
            result.evaluations.add(evaluation);
        }
        return result;
    }
    JSONObject provenance() throws Exception {
        int supported=0,ignored=0;for(Rule rule:rules) { if(rule.pattern!=null) supported++;else if(rule.status.equals("ignored-short")) ignored++; }
        return new JSONObject().put("inputKind","user-imported Exodus snapshot; no online authenticity verification").put("snapshotSha256",sha256).put("snapshotSizeBytes",bytes.length)
            .put("ruleCount",rules.size()).put("compiledRules",supported).put("ignoredShortRules",ignored).put("unsupportedRules",rules.size()-supported-ignored)
            .put("matcher","independent GPL adapter / source-built RE2/J 1.8 / Python 3.12-compatible compared subset")
            .put("unicodeVersion",TrackerUnicode.VERSION).put("unicodeDataSha256",TrackerUnicode.SOURCE_SHA256)
            .put("upstreamContractRevision",REFERENCE).put("upstreamReferenceSourceSha256",REFERENCE_SHA256)
            .put("contract","Regex search over slash-separated original defined-class names without leading L or trailing semicolon; signatures <=3 code points ignored. Every matching rule reports its first evidence in deterministic UTF-16 descriptor order; spans use UTF-16 units. No dotted-prefix reinterpretation, case folding or Unicode normalization.")
            .put("limitations","Python regex is broader: flags/lookaround/backreferences/word boundaries and other unsupported syntax remain explicit diagnostics. LF/isolated-surrogate/overlong names are unsupported. This class-definition acquisition differs from upstream dexdump text extraction and does not inspect arbitrary type references or nested APKs automatically. Network signatures are retained metadata, not traffic observations. A class association does not prove initialization, collection, transmission, a vulnerability or malicious behavior.")
            .put("license","Imported database: ODbL-1.0 / individual contents DbCL-1.0; user must retain the snapshot's actual source/licensing context. Exodus AGPL core code is a development-only reference, not bundled or linked.");
    }
    EvidenceRows rows(boolean errorsOnly,boolean metadataOnly) {
        List<Rule> selected=new ArrayList<>();for(Rule rule:rules) if(!errorsOnly||rule.pattern==null) selected.add(rule);
        return new EvidenceRows("tracker-rule-catalog",metadataOnly?"Imported snapshot metadata":"Exodus signatures • "+(errorsOnly?"unsupported / short":"every imported record")) {
            int size() { return metadataOnly?metadata.size():selected.size(); }
            private TrackerJson.Slice slice(int index) { return metadataOnly?metadata.get(index):selected.get(index).slice; }
            Row row(int index,BooleanSupplier stop) throws Exception {
                EvidenceRows.check(stop);TrackerJson.Slice slice=slice(index);JSONObject fields=new JSONObject()
                    .put("snapshotKey",preview(slice.key,8192)).put("snapshotKeyUtf16Units",slice.key.length()).put("snapshotKeyPreviewTruncated",slice.key.length()>8192)
                    .put("originalJsonUtf16Start",slice.start).put("originalJsonUtf16End",slice.end)
                    .put("originalJsonLength",slice.raw.length()).put("originalJsonPreview",preview(slice.raw,8192))
                    .put("previewTruncated",slice.raw.length()>8192).put("fullExport","Complete exact original JSON value accompanies every exported row; exact snapshot bytes are a separate action");
                if(metadataOnly) return new Row(index,preview(slice.key,256),"Original top-level snapshot metadata",null,null,fields);
                Rule rule=selected.get(index);
                fields.put("trackerId",rule.id).put("originalRuleIndex",rule.index).put("codeSignature",preview(rule.signature,8192)).put("signatureUtf16Units",rule.signature.length()).put("signaturePreviewTruncated",rule.signature.length()>8192).put("status",rule.status).put("diagnostic",rule.diagnostic)
                    .put("compiledProgramSize",rule.pattern==null?JSONObject.NULL:rule.pattern.programSize());
                return new Row(index,preview(rule.name,256),rule.status+" • "+preview(rule.signature,512),null,null,fields);
            }
            JSONObject metadata() throws Exception { return provenance().put("errorsOnly",errorsOnly).put("metadataOnly",metadataOnly); }
            @Override boolean matches(Row row,String query,BooleanSupplier stop) throws Exception {
                TrackerJson.Slice slice=slice(row.index);Object original=slice.decoded;
                return slice.key.toLowerCase(Locale.ROOT).contains(query)||slice.raw.toLowerCase(Locale.ROOT).contains(query)
                    ||!metadataOnly&&(selected.get(row.index).status.toLowerCase(Locale.ROOT).contains(query)||selected.get(row.index).diagnostic.toLowerCase(Locale.ROOT).contains(query))
                    ||containsDecoded(original,query,0,new int[]{0},stop);
            }
            @Override void writeSelectedRow(Writer output,Row row,BooleanSupplier stop) throws Exception {
                EvidenceRows.check(stop);output.write(row.encoded,0,row.encoded.length()-1);output.write(",\"fullOriginalKey\":");EvidenceJson.write(output,slice(row.index).key,stop);output.write(",\"fullOriginalRecord\":");
                String raw=slice(row.index).raw;for(int at=0;at<raw.length();at+=4096) { EvidenceRows.check(stop);output.write(raw,at,Math.min(4096,raw.length()-at)); }output.write('}');
            }
        };
    }
    static String preview(String text,int max) { return text.length()<=max?text:text.substring(0,max)+"… [preview; full export available]"; }
}
