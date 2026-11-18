// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.util.Iterator;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Streaming JSON for full engine values; preserves every UTF-16 code unit. */
final class EvidenceJson {
    /** Implemented only by grammar-validated source numeric tokens. */
    interface RawNumber { String literal(); }
    private static final char[] HEX="0123456789ABCDEF".toCharArray();
    private final Writer output;
    private final BooleanSupplier cancelled;
    private int visited;
    private EvidenceJson(Writer output, BooleanSupplier cancelled) {
        this.output = output;
        this.cancelled = cancelled;
    }
    static void write(Writer output, Object value, BooleanSupplier cancelled) throws Exception {
        new EvidenceJson(output,cancelled).value(value,0);
    }
    private void value(Object value, int depth) throws Exception {
        EvidenceRows.check(cancelled);
        if (depth > 256 || ++visited > 2000000) throw new IOException("Full JSON value exceeds its depth/node budget");
        if (value == null || value == JSONObject.NULL) output.write("null");
        else if (value instanceof JSONObject) {
            output.write('{');
            Iterator<String> keys = ((JSONObject)value).keys();
            boolean first = true;
            while (keys.hasNext()) {
                if (!first) output.write(',');
                first = false;
                String key = keys.next();
                quoted(output,key,cancelled); output.write(':');
                value(((JSONObject)value).get(key),depth+1);
            }
            output.write('}');
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value;
            output.write('[');
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) output.write(',');
                value(array.get(i),depth+1);
            }
            output.write(']');
        } else if (value instanceof String) quoted(output,(String)value,cancelled);
        else if (value instanceof Boolean) output.write(value.toString());
        else if (value instanceof RawNumber) output.write(((RawNumber)value).literal());
        else if (value instanceof Number) output.write(JSONObject.numberToString((Number)value));
        else throw new IOException("Unrecognized JSON value class: "+value.getClass().getName());
    }
    static void quoted(Writer output, String value, BooleanSupplier cancelled) throws Exception {
        output.write('"');
        int start = 0;
        char[] escaped=null;
        for (int i = 0; i < value.length(); i++) {
            if ((i & 4095) == 0) EvidenceRows.check(cancelled);
            char c = value.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20 || Character.isSurrogate(c)) {
                output.write(value,start,i-start);
                if (c == '"' || c == '\\') { output.write('\\'); output.write(c); }
                else {
                    if(escaped==null) escaped=new char[]{'\\','u','0','0','0','0'};
                    escaped[2]=HEX[(c>>>12)&15];escaped[3]=HEX[(c>>>8)&15];escaped[4]=HEX[(c>>>4)&15];escaped[5]=HEX[c&15];
                    output.write(escaped);
                }
                start = i+1;
            }
        }
        output.write(value,start,value.length()-start); output.write('"');
    }
}
