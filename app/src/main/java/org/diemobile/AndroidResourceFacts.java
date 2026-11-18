// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.google.devrel.gmscore.tools.apk.arsc.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Exact encoded values with explicit, separate interpretations of their bits. */
final class AndroidResourceFacts {
    static JSONObject value(ResourceValue value) throws Exception {
        int data=value.data(),type=value.type().code()&255;
        JSONObject result=new JSONObject().put("type",value.type().name()).put("typeCode",type)
            .put("logicalValueSize",value.size()).put("dataUnsigned",Integer.toUnsignedLong(data))
            .put("dataSigned",data).put("dataHex",hex(data));
        if(type==0) result.put("meaning",data==0?"@null":data==1?"@empty":"Unrecognized TYPE_NULL payload; raw bits retained");
        else if(type==1||type==2||type==7||type==8) result.put("resourceId",Integer.toUnsignedLong(data))
            .put("meaning","Logical resource/attribute identifier; dynamic IDs require package remapping. This is not a physical byte address.");
        else if(type==3) result.put("stringIndex",Integer.toUnsignedLong(data))
            .put("meaning","Index into the associated XML/global resource string pool, not a file offset");
        else if(type==4) {
            float decoded=Float.intBitsToFloat(data);
            result.put("ieee754Bits",hex(data)).put("decodedFloatText",Float.toString(decoded))
                .put("finite",!Float.isInfinite(decoded)&&!Float.isNaN(decoded));
        } else if(type==5||type==6) {
            int radix=(data>>>4)&3,unit=data&15,mantissa=data&0xffffff00;
            double[] multipliers={1.0/256,1.0/32768,1.0/8388608,1.0/2147483648L};
            String[] units=type==5?new String[]{"px","dp","sp","pt","in","mm"}:new String[]{"fraction of base","fraction of parent base"};
            result.put("radix",radix).put("unitCode",unit).put("unit",unit<units.length?units[unit]:"unrecognized")
                .put("shiftedSignedMantissa",mantissa).put("decodedMagnitude",mantissa*multipliers[radix])
                .put("mechanism","Signed mantissa/radix decode the stored magnitude. Dimensions require display metrics for pixel conversion; fractions require the appropriate base.");
        } else if(type==18) result.put("decodedBoolean",data!=0).put("meaning","Stored nonzero boolean. A declared manifest flag does not prove runtime behavior.");
        else if(type>=28&&type<=31) result.put("meaning","Encoded ARGB/RGB color components; retain the type to distinguish 8-bit versus 4-bit component layouts");
        return result;
    }
    static JSONObject configuration(ResourceConfiguration configuration) throws Exception {
        JSONObject result=new JSONObject();
        List<Method> getters=new ArrayList<>();
        for(Method method:ResourceConfiguration.class.getDeclaredMethods()) {
            if(Modifier.isPublic(method.getModifiers())&&Modifier.isAbstract(method.getModifiers())&&method.getParameterTypes().length==0&&!method.getName().equals("toBuilder")) getters.add(method);
        }
        getters.sort(Comparator.comparing(Method::getName));
        for(Method getter:getters) {
            Object value=getter.invoke(configuration);
            if(value instanceof byte[]) result.put(getter.getName()+"Hex",Inspector.toHex((byte[])value));
            else if(value instanceof Number||value instanceof Boolean||value instanceof String) result.put(getter.getName(),value);
            else throw new IOException("Unrecognized resource configuration fact: "+getter.getName());
        }
        return result.put("engineQualifierText",configuration.toString()).put("engineDefaultConfiguration",configuration.isDefault())
            .put("interpretation","Qualifiers describe candidate resource selection. Device/SDK/locale matching and actual runtime selection are not verified; raw configuration bytes remain authoritative.");
    }
    static String hex(int bits) { return String.format(Locale.ROOT,"0x%08x",bits); }
    static String chunkName(int type) {
        try { return Chunk.Type.fromCode((short)type).name(); }
        catch(RuntimeException unknown) { return "UNKNOWN_0x"+Integer.toHexString(type); }
    }
    private AndroidResourceFacts() {}
}
