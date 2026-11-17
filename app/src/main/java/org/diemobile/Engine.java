// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;
import java.nio.charset.StandardCharsets;
final class Engine {
    static final boolean AVAILABLE;
    static { boolean ok; try { System.loadLibrary("diemobile"); ok=true; } catch(UnsatisfiedLinkError e){ok=false;} AVAILABLE=ok; }
    private static native byte[] scanNative(String path,String db,int flags);
    private static native byte[] toolNative(String path,int tool,long offset,int mode,int minimum,String selector,int json,int syntax);
    static synchronized String tool(String path,int tool,long offset,int mode,int minimum) {
        return tool(path,tool,offset,mode,minimum,"",false,false);
    }
    static synchronized String tool(String path,int tool,long offset,int mode,int minimum,String selector,boolean json,boolean attSyntax) {
        if(!AVAILABLE)return "Native engine unavailable.";
        byte[] raw=toolNative(path,tool,offset,mode,minimum,selector,json?1:0,attSyntax?1:0);
        return raw==null?"Native report allocation failed.":new String(raw,StandardCharsets.UTF_8);
    }
    static synchronized String scan(String path,String db,int flags) {
        if(!AVAILABLE) return null;
        byte[] raw=scanNative(path,db,flags);
        if(raw==null||raw.length==0) throw new IllegalStateException("CDiE returned an empty result");
        return new String(raw,StandardCharsets.UTF_8);
    }
}
