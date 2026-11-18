// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Bounded JSON reader: duplicate names and platform numeric conversion must not erase evidence. */
final class TrackerJson {
    /** A JSON number is retained lexically, without a lossy/infinite Java double conversion. */
    private static final class NumberToken {
        final String text;
        NumberToken(String text) { this.text=text; }
        @Override public String toString() { return text; }
    }
    static final class Slice {
        final String key,raw;
        final int start,end;
        // Used only for full-value searching and type-checked string-field access.
        // Numeric tokens intentionally are not Java Number instances. Complete
        // evidence exports use raw, never org.json serialization of this tree.
        final Object decoded;
        Slice(String key,String text,int start,int end,Object decoded) { this.key=key;raw=text.substring(start,end);this.start=start;this.end=end;this.decoded=decoded; }
    }
    final List<Slice> records=new ArrayList<>(),metadata=new ArrayList<>();
    private final String text;
    private final BooleanSupplier stop;
    private int at,nodes;
    private boolean found;
    TrackerJson(String text,BooleanSupplier stop) throws Exception {
        this.text=text;this.stop=stop;
        space();if(peek()!='{') fail("Snapshot root must be an object");object(0,true,false);space();
        if(at!=text.length()||!found) fail("Expected one complete snapshot with a trackers object");
    }
    private void fail(String reason) throws IOException { throw new IOException(reason+" at JSON UTF-16 unit "+at); }
    private char peek() { return at<text.length()?text.charAt(at):'\0'; }
    private void space() { while(at<text.length()&&" \t\r\n".indexOf(text.charAt(at))>=0) at++; }
    private void expect(char unit) throws IOException { if(at>=text.length()||text.charAt(at++)!=unit) fail("Expected '"+unit+"'"); }
    private Object value(int depth) throws Exception {
        EvidenceRows.check(stop);if(depth>64||++nodes>200000) fail("JSON exceeds depth 64 / 200,000 value budget");
        space();char unit=peek();
        if(unit=='{') return object(depth,false,false);
        else if(unit=='[') {
            at++;space();JSONArray values=new JSONArray();if(peek()==']') { at++;return values; }
            do { values.put(value(depth+1));space();if(peek()==']') { at++;return values; }expect(','); } while(true);
        } else if(unit=='"') return string();
        else if(unit=='t') { literal("true");return Boolean.TRUE; }
        else if(unit=='f') { literal("false");return Boolean.FALSE; }
        else if(unit=='n') { literal("null");return JSONObject.NULL; }
        else return number();
    }
    private void literal(String value) throws IOException { if(!text.startsWith(value,at)) fail("Invalid JSON literal");at+=value.length(); }
    private JSONObject object(int depth,boolean root,boolean trackers) throws Exception {
        expect('{');space();Set<String> keys=new HashSet<>();JSONObject values=new JSONObject();if(peek()=='}') { at++;return values; }
        do {
            EvidenceRows.check(stop);space();String key=string();
            if(!keys.add(key)) fail("Duplicate object key (including escaped aliases)");
            space();expect(':');space();int start=at;Object decoded;
            if(root&&key.equals("trackers")) {
                found=true;if(peek()!='{') fail("trackers must be an object");decoded=object(depth+1,false,true);
            } else decoded=value(depth+1);
            values.put(key,decoded);
            if(trackers) {
                if(records.size()>=10000||key.isEmpty()||key.length()>256) fail("Tracker inventory exceeds 10,000 entries / ID length budget");
                records.add(new Slice(key,text,start,at,decoded));
            } else if(root&&!key.equals("trackers")) metadata.add(new Slice(key,text,start,at,decoded));
            space();if(peek()=='}') { at++;return values; }expect(',');
        } while(true);
    }
    private String string() throws Exception {
        int start=at;expect('"');
        while(at<text.length()) {
            if((at&4095)==0) EvidenceRows.check(stop);
            char unit=text.charAt(at++);
            if(unit=='"') return new JSONTokener(text.substring(start,at)).nextValue().toString();
            if(unit<32) fail("Unescaped control in JSON string");
            if(unit=='\\') {
                if(at>=text.length()) fail("Truncated JSON escape");char escape=text.charAt(at++);
                if(escape=='u') { for(int i=0;i<4;i++) if(at>=text.length()||"0123456789abcdefABCDEF".indexOf(text.charAt(at++))<0) fail("Invalid JSON Unicode escape"); }
                else if("\"\\/bfnrt".indexOf(escape)<0) fail("Invalid JSON escape");
            }
        }
        fail("Unterminated JSON string");return "";
    }
    private boolean digit() { return peek()>='0'&&peek()<='9'; }
    private NumberToken number() throws IOException {
        int start=at;
        if(peek()=='-') at++;
        if(peek()=='0') at++;
        else { if(peek()<'1'||peek()>'9') fail("Invalid JSON value/number");do { at++; } while(digit()); }
        if(peek()=='.') { at++;if(!digit()) fail("Missing fractional digits");do { at++; } while(digit()); }
        if(peek()=='e'||peek()=='E') { at++;if(peek()=='+'||peek()=='-') at++;if(!digit()) fail("Missing exponent digits");do { at++; } while(digit()); }
        return new NumberToken(text.substring(start,at));
    }
}
