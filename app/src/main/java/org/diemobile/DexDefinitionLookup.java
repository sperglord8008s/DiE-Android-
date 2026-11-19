// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.tools.smali.dexlib2.ReferenceType;
import com.android.tools.smali.dexlib2.dexbacked.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Exact declared-owner/signature lookup; this is deliberately not an ART class linker. */
final class DexDefinitionLookup {
    private static final int MAX_DECLARATIONS=200000,MAX_PARAMETERS=1000000,MAX_TEXT=32*1024*1024,MAX_MATCHES=50000;
    private static final class Limit extends IOException { Limit(String message) { super(message); } }
    private static final class Work {
        final DexDefinitionIndex.Deadline deadline;
        int declarations,parameters;
        long units;
        Work(BooleanSupplier stop) { deadline=new DexDefinitionIndex.Deadline(stop); }
        boolean same(String first,String second) throws IOException {
            deadline.check();units+=(long)first.length()+second.length();
            if(units>MAX_TEXT) throw new Limit("Definition comparison exceeds 32 Mi UTF-16 units; no complete lookup returned");
            return first.equals(second);
        }
        void declaration() throws IOException {
            deadline.check();if(++declarations>MAX_DECLARATIONS) throw new Limit("Definition lookup exceeds 200,000 declarations; no complete lookup returned");
        }
        boolean parameters(List<? extends CharSequence> first,List<? extends CharSequence> second) throws IOException {
            if(first.size()!=second.size()) return false;
            for(int i=0;i<first.size();i++) {
                if(++parameters>MAX_PARAMETERS) throw new Limit("Definition lookup exceeds one million parameter comparisons; no complete lookup returned");
                if(!same(first.get(i).toString(),second.get(i).toString())) return false;
            }
            return true;
        }
    }
    private static final class Request {
        final int type;
        final String owner,descriptor;
        final MethodReference method;
        final FieldReference field;
        Request(DexDocument.Entry entry,DexQuery query,BooleanSupplier stop) throws Exception {
            if(query.referenceType==-1&&query.owner>=0) {
                type=ReferenceType.TYPE;owner=entry.dex.getClassSection().get(query.owner).getType();descriptor=owner;method=null;field=null;
            } else if(query.referenceType==ReferenceType.TYPE) {
                type=ReferenceType.TYPE;owner=entry.dex.getTypeSection().get(query.referenceIndex);descriptor=owner;method=null;field=null;
            } else if(query.referenceType==ReferenceType.METHOD) {
                type=ReferenceType.METHOD;method=entry.dex.getMethodSection().get(query.referenceIndex);field=null;
                owner=method.getDefiningClass();descriptor=DexValues.reference(method,stop);
            } else if(query.referenceType==ReferenceType.FIELD) {
                type=ReferenceType.FIELD;field=entry.dex.getFieldSection().get(query.referenceIndex);method=null;
                owner=field.getDefiningClass();descriptor=DexValues.reference(field,stop);
            } else throw new IOException("Exact definitions require a class/type, field or method reference");
        }
    }
    private static final class Match {
        final DexDefinitionIndex.Location location;
        final String kind,group;
        final int declaration;
        final long offset;
        Match(DexDefinitionIndex.Location location,String kind,String group,int declaration,long offset) {
            this.location=location;this.kind=kind;this.group=group;this.declaration=declaration;this.offset=offset;
        }
    }
    static EvidenceRows resolve(DexDefinitionIndex index,int originMember,DexDocument origin,DexQuery query,BooleanSupplier stop) throws Exception {
        Work work=new Work(stop);work.deadline.check();Request request;
        try { request=new Request(origin.entries.get(query.entry),query,work.deadline::stopped); }
        catch(InterruptedIOException failure) { work.deadline.check();throw failure; }
        List<Match> matches=new ArrayList<>();JSONArray errors=new JSONArray();int failures=0;
        int loadedSource=-1;DexDocument loaded=null;String loadError="";
        for(DexDefinitionIndex.Location location:index.find(request.owner)) {
            work.deadline.check();DexDefinitionIndex.Source source=index.sources.get(location.source);
            if(request.type==ReferenceType.TYPE) {
                add(matches,new Match(location,"classes","class_def",-1,location.physicalOffset));continue;
            }
            if(loadedSource!=location.source) {
                loadedSource=location.source;loaded=null;loadError="";
                try { loaded=source.member==originMember&&source.sha256.equals(origin.sha256)?origin:index.load(source,work.deadline::stopped); }
                catch(Exception failure) {
                    work.deadline.check();loadError=failure.toString();failures++;
                    diagnostic(errors,location,loadError);
                }
            }
            if(!loadError.isEmpty()) continue;
            try {
                DexDocument.Entry entry=loaded.entries.get(location.entry);
                if(request.type==ReferenceType.METHOD) {
                    DexDocument.Declarations<DexBackedMethod> declarations=entry.methodDeclarations(location.classIndex,work.deadline::stopped);
                    for(int i=0;i<declarations.values.size();i++) {
                        work.declaration();DexBackedMethod method=declarations.values.get(i);
                        MethodReference identifier=entry.dex.getMethodSection().get(method.methodIndex);
                        if(!work.same(location.descriptor,identifier.getDefiningClass())) throw new IOException("class_data method identifier owner differs from its declared class; inspect original declaration metadata");
                        if(work.same(request.method.getName(),method.getName())&&work.same(request.method.getReturnType(),method.getReturnType())
                            &&work.parameters(request.method.getParameterTypes(),method.getParameterTypes())) {
                            long at=entry.dex.getMethodSection().getOffset(method.methodIndex);entry.requireRange(at,8);
                            add(matches,new Match(location,"methods",i<declarations.firstGroupCount?"direct":"virtual",i,at));
                        }
                    }
                } else {
                    DexDocument.Declarations<DexBackedField> declarations=entry.fieldDeclarations(location.classIndex,work.deadline::stopped);
                    for(int i=0;i<declarations.values.size();i++) {
                        work.declaration();DexBackedField field=declarations.values.get(i);
                        FieldReference identifier=entry.dex.getFieldSection().get(field.fieldIndex);
                        if(!work.same(location.descriptor,identifier.getDefiningClass())) throw new IOException("class_data field identifier owner differs from its declared class; inspect original declaration metadata");
                        if(work.same(request.field.getName(),field.getName())&&work.same(request.field.getType(),field.getType())) {
                            long at=entry.dex.getFieldSection().getOffset(field.fieldIndex);entry.requireRange(at,8);
                            add(matches,new Match(location,"fields",i<declarations.firstGroupCount?"static":"instance",i,at));
                        }
                    }
                }
            } catch(Limit failure) { throw failure; }
            catch(Exception failure) { work.deadline.check();failures++;diagnostic(errors,location,failure.toString()); }
            catch(StackOverflowError failure) { work.deadline.check();failures++;diagnostic(errors,location,"Parser recursion exhausted"); }
        }
        work.deadline.check();boolean complete=index.complete()&&failures==0;int totalFailures=failures;
        JSONObject lookup=new JSONObject().put("originArchiveMemberIndex",originMember).put("originEntryIndex",query.entry).put("originDexSha256",origin.sha256)
            .put("referenceType",request.type).put("referenceIndex",query.referenceIndex).put("originClassIndex",query.owner)
            .put("requestedDescriptor",request.descriptor).put("requestedDeclaredOwner",request.owner)
            .put("completeInSelectedScopes",complete).put("matchCount",matches.size()).put("queryFailures",totalFailures).put("retainedDiagnostics",errors)
            .put("diagnosticLimit",256).put("diagnosticsOmitted",Math.max(0,totalFailures-errors.length()))
            .put("inspectedDeclarations",work.declarations).put("parameterComparisons",work.parameters).put("comparedUtf16Units",work.units)
            .put("matchingContract","Exact original declared owner/name/type or owner/name/parameter/return signature. All duplicates remain observations, never an automatically chosen implementation. Arrays/primitives have no class_def; inherited members, other splits, framework classes, dispatch and reflection are not resolved.")
            .put("absenceMeaning",complete?"No row means no exact declaration in the successfully indexed selected scope; it does not establish absence elsewhere or runtime linkage failure":"PARTIAL: unindexed/unavailable/malformed scopes leave absence and uniqueness unknown");
        return new EvidenceRows("dex-exact-definitions",(complete?"":"PARTIAL scope • ")+"Exact declarations • "+preview(request.descriptor,180)) {
            int size() { return matches.size(); }
            Row row(int row,BooleanSupplier cancelled) throws Exception {
                EvidenceRows.check(cancelled);Match match=matches.get(row);
                Row value=index.locationRow(row,match.location,match.kind,match.declaration,match.offset,match.kind.equals("classes")?32:8,request.descriptor);
                JSONObject fields=value.json().put("declarationGroup",match.group).put("identifierOwnerMatchesDeclaration",true);
                return new Row(row,value.name,value.description,null,null,fields);
            }
            JSONObject metadata() throws Exception { return index.provenance().put("lookup",new JSONObject(lookup.toString())); }
            @Override boolean matches(Row row,String term,BooleanSupplier cancelled) throws Exception {
                return index.sources.get(matches.get(row.index).location.source).name.toLowerCase(Locale.ROOT).contains(term)||DexDefinitionIndex.locationMatches(row,term,cancelled);
            }
        };
    }
    private static void add(List<Match> matches,Match match) throws IOException {
        if(matches.size()==MAX_MATCHES) throw new Limit("Definition lookup exceeds 50,000 exact matches; no successful partial match table returned");matches.add(match);
    }
    private static void diagnostic(JSONArray errors,DexDefinitionIndex.Location location,String message) throws Exception {
        if(errors.length()<256) errors.put(new JSONObject().put("sourceIndex",location.source).put("entryIndex",location.entry).put("classIndex",location.classIndex)
            .put("message",preview(message,4096)).put("messagePreviewTruncated",message.length()>4096));
    }
    private static String preview(String text,int maximum) { return text.length()<=maximum?text:text.substring(0,maximum)+"… [preview]"; }
}
