// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Names observed in the compiled manifest, with exact child-source relationships. */
final class SdkManifestNames {
    static final class Fact {
        final int type,attributeIndex,elementChunk;
        final String value,original,element,selection;
        final long physicalOffset;
        Fact(int type,String value,String original,String element,String selection,int attributeIndex,int elementChunk,long at) {
            this.type=type;this.value=value;this.original=original;this.element=element;this.selection=selection;
            this.attributeIndex=attributeIndex;this.elementChunk=elementChunk;physicalOffset=at;
        }
    }
    final List<Fact> facts;
    final List<String> diagnostics;
    final String packageName;
    SdkManifestNames(AndroidResourceDocument document,BooleanSupplier stop) throws Exception {
        JSONArray events=document.tables.getJSONArray("xml-events"),attributes=document.tables.getJSONArray("attributes"),maps=document.tables.getJSONArray("resource-map");
        Map<Integer,List<JSONObject>> byElement=new HashMap<>();Map<String,Long> ids=new HashMap<>();
        for(int i=0;i<attributes.length();i++) {
            EvidenceRows.check(stop);JSONObject attribute=attributes.getJSONObject(i);int owner=attribute.getInt("elementChunkIndex");
            byElement.computeIfAbsent(owner,key->new ArrayList<>()).add(attribute);
        }
        for(int i=0;i<maps.length();i++) {
            JSONObject map=maps.getJSONObject(i);String key=map.getInt("poolChunkIndex")+":"+map.getInt("stringIndex");
            Long previous=ids.put(key,map.getLong("resourceId"));
            if(previous!=null) throw new IOException("Ambiguous repeated XML attribute resource map");
        }
        List<Fact> found=new ArrayList<>();List<String> errors=new ArrayList<>();String declaredPackage="";
        for(int i=0;i<events.length();i++) {
            JSONObject event=events.getJSONObject(i);
            if(event.getInt("chunkType")!=0x102||!event.optString("namespace").isEmpty()||!event.optString("elementName").equals("manifest")) continue;
            for(JSONObject attribute:byElement.getOrDefault(event.getInt("chunkIndex"),Collections.emptyList())) {
                if(attribute.getString("namespace").isEmpty()&&attribute.getString("name").equals("package")) {
                    String value=string(attribute);
                    if(value!=null) {
                        if(!declaredPackage.isEmpty()&&!declaredPackage.equals(value)) errors.add("Conflicting manifest package declarations; class-name resolution is ambiguous");
                        else declaredPackage=value;
                    }
                }
            }
        }
        boolean ambiguous=!errors.isEmpty();packageName=declaredPackage;
        for(int i=0;i<events.length();i++) {
            EvidenceRows.check(stop);JSONObject event=events.getJSONObject(i);
            if(event.getInt("chunkType")!=0x102||!event.optString("namespace").isEmpty()) continue;
            String element=event.getString("elementName");int type=type(element);
            if(type<0) continue;
            for(JSONObject attribute:byElement.getOrDefault(event.getInt("chunkIndex"),Collections.emptyList())) {
                String key=attribute.getInt("poolChunkIndex")+":"+attribute.getInt("nameStringIndex");
                Long id=ids.get(key);String selection;
                if(id!=null&&id==0x01010003L) selection="compiled android:name resource ID 0x01010003";
                else if(id==null&&attribute.getString("namespace").equals("http://schemas.android.com/apk/res/android")&&attribute.getString("name").equals("name")) selection="namespace/name fallback; platform acceptance unverified";
                else continue;
                String original=string(attribute);
                if(original==null||original.isEmpty()) {
                    errors.add("Unresolved/nonliteral "+element+" name at manifest attribute "+attribute.getInt("index")+"; raw typed value remains in the binary manifest viewer");continue;
                }
                String value=original;
                if(type>=1&&type<=4) {
                    if(original.startsWith(".")||original.indexOf('.')<0) {
                        if(ambiguous||packageName.isEmpty()) {
                            errors.add("Relative component name lacks one unambiguous manifest package: "+original);continue;
                        }
                        value=original.startsWith(".")?packageName+original:packageName+"."+original;
                    }
                }
                found.add(new Fact(type,value,original,element,selection,attribute.getInt("index"),event.getInt("chunkIndex"),attribute.getLong("physicalOffset")));
            }
        }
        facts=Collections.unmodifiableList(found);diagnostics=Collections.unmodifiableList(errors);
    }
    private static String string(JSONObject attribute) throws Exception {
        JSONObject value=attribute.getJSONObject("value");
        return value.getInt("typeCode")==3?value.getString("text"):null;
    }
    private static int type(String element) {
        switch(element) {
            case "service":return 1;
            case "activity":case "activity-alias":return 2;
            case "receiver":return 3;
            case "provider":return 4;
            case "uses-library":case "uses-static-library":case "uses-sdk-library":case "static-library":case "sdk-library":return 6;
            case "uses-permission":case "uses-permission-sdk-23":case "permission":case "permission-tree":case "permission-group":return 7;
            case "meta-data":return 8;
            case "action":return 9;
            default:return -1;
        }
    }
}
