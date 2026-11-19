// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.drew.lang.*;
import com.drew.metadata.StringValue;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Typed raw metadata values; interactive previews and complete streaming exports. */
final class MetadataValues {
    private final Writer out;
    private final BooleanSupplier cancelled;
    private final boolean complete;
    private final IdentityHashMap<Object,Integer> active = new IdentityHashMap<>();
    private int nodes, nextIdentity;
    private MetadataValues(Writer out, boolean complete, BooleanSupplier stop) { this.out = out; this.complete = complete; cancelled = stop; }
    static JSONObject preview(Object value, BooleanSupplier stop) throws Exception {
        StringWriter out = new StringWriter(); new MetadataValues(out,false,stop).value(value,0); return new JSONObject(out.toString());
    }
    static void write(Writer out, Object value, BooleanSupplier stop) throws Exception { new MetadataValues(out,true,stop).value(value,0); }
    private void value(Object value, int depth) throws Exception {
        EvidenceRows.check(cancelled);
        if (++nodes > (complete ? 1000000 : 1024) || depth > (complete ? 32 : 8)) {
            if (complete) throw new IOException("Metadata value exceeds one million nodes or 32 levels; no successful partial export returned");
            out.write("{\"previewComplete\":false,\"fullValueInTableExport\":true}"); return;
        }
        if (value == null) { out.write("{\"type\":\"null\",\"value\":null}"); return; }
        out.write("{\"type\":"); quoted(out,value.getClass().getName());
        if (value instanceof String) { out.write(",\"utf16Units\":"+((String)value).length()+",\"value\":"); string((String)value); }
        else if (value instanceof StringValue) {
            StringValue text = (StringValue)value; out.write(",\"charset\":"); if (text.getCharset() == null) out.write("null"); else quoted(out,text.getCharset().name());
            out.write(",\"decodedValue\":"); string(text.toString()); out.write(",\"originalBytes\":"); value(text.getBytes(),depth+1);
        } else if (value instanceof Rational) {
            Rational rational = (Rational)value; out.write(",\"numerator\":"); quoted(out,Long.toString(rational.getNumerator())); out.write(",\"denominator\":"); quoted(out,Long.toString(rational.getDenominator()));
            out.write(",\"approximateDouble\":"); quoted(out,Double.toString(rational.doubleValue()));
        } else if (value instanceof Float || value instanceof Double) {
            out.write(",\"value\":"); quoted(out,value.toString()); out.write(",\"ieeeBitsHex\":");
            quoted(out,value instanceof Float ? String.format(Locale.ROOT,"%08x",Float.floatToRawIntBits((Float)value)) : String.format(Locale.ROOT,"%016x",Double.doubleToRawLongBits((Double)value)));
        } else if (value instanceof Number) { out.write(",\"value\":"); quoted(out,value.toString()); }
        else if (value instanceof Boolean) out.write(",\"value\":"+value);
        else if (value instanceof Character) { out.write(",\"utf16Unit\":"+(int)(Character)value+",\"value\":"); quoted(out,value.toString()); }
        else if (value instanceof Date) { out.write(",\"epochMillis\":"); quoted(out,Long.toString(((Date)value).getTime())); out.write(",\"utcInstant\":"); quoted(out,((Date)value).toInstant().toString()); }
        else if (value instanceof byte[]) {
            byte[] data = (byte[])value; out.write(",\"byteLength\":"+data.length+",\"sha256\":"); quoted(out,Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(data)));
            out.write(",\"base64\":\""); int count = complete ? data.length : Math.min(data.length,128);
            for (int i = 0; i < count; i += 6144) { EvidenceRows.check(cancelled); out.write(Base64.getEncoder().encodeToString(Arrays.copyOfRange(data,i,Math.min(count,i+6144)))); }
            out.write("\",\"previewComplete\":"+(count==data.length));
        } else if (value instanceof KeyValuePair) {
            KeyValuePair pair = (KeyValuePair)value; out.write(",\"key\":"); string(pair.getKey()); out.write(",\"value\":"); value(pair.getValue(),depth+1);
        } else if (value.getClass().isArray() || value instanceof Collection) {
            int count = value.getClass().isArray() ? Array.getLength(value) : ((Collection<?>)value).size(); out.write(",\"elementCount\":"+count+",\"values\":[");
            int take = complete ? count : Math.min(count,16); Iterator<?> iterator = value instanceof Collection ? ((Collection<?>)value).iterator() : null;
            if (complete && count > 1000000-nodes) throw new IOException("Metadata array exceeds export node budget");
            Integer previous = active.put(value,++nextIdentity); if (previous != null) throw new IOException("Cyclic metadata array/collection");
            try { for (int i = 0; i < take; i++) { if (i > 0) out.write(','); value(iterator == null ? Array.get(value,i) : iterator.next(),depth+1); } } finally { active.remove(value); }
            out.write("],\"previewComplete\":"+(take==count));
        } else if (value.getClass().getName().startsWith("com.drew.")) {
            // Trusted engine-created value models (JPEG components, faces, ages,
            // Huffman tables, etc.); never reflect classes from an inspected app.
            if (active.containsKey(value)) throw new IOException("Cyclic engine metadata value"); active.put(value,++nextIdentity);
            try {
                List<Field> fields = new ArrayList<>();
                for (Class<?> type = value.getClass(); type != null && type.getName().startsWith("com.drew."); type = type.getSuperclass())
                    for (Field field : type.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) fields.add(field);
                fields.sort(Comparator.comparing(field -> field.getDeclaringClass().getName()+"."+field.getName())); out.write(",\"engineFields\":{");
                for (int i = 0; i < fields.size(); i++) { if (i > 0) out.write(','); Field field = fields.get(i); field.setAccessible(true); quoted(out,field.getDeclaringClass().getName()+"."+field.getName()); out.write(':'); value(field.get(value),depth+1); }
                out.write("},\"syntheticFieldsOmitted\":true");
            } finally { active.remove(value); }
        } else { out.write(",\"engineString\":"); string(value.toString()); out.write(",\"rawRepresentationComplete\":false,\"limitation\":\"Unrecognized engine value type; string representation only\""); }
        out.write('}');
    }
    private void string(String value) throws IOException {
        int count = complete ? value.length() : Math.min(value.length(),4096); quoted(out,value.substring(0,count));
        if (count != value.length()) out.write(",\"previewComplete\":false,\"fullValueInTableExport\":true");
    }
    static void quoted(Writer out, String value) throws IOException {
        out.write('"'); int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20 || Character.isSurrogate(c)) {
                out.write(value,start,i-start);
                if (c == '"' || c == '\\') { out.write('\\'); out.write(c); }
                else out.write(String.format(Locale.ROOT,"\\u%04X",(int)c));
                start = i+1;
            }
        }
        out.write(value,start,value.length()-start); out.write('"');
    }
}
