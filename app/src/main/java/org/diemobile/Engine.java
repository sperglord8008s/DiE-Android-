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
    private static native byte[] desktopNative(String path,String db,String custom,int verb,int flags,int typeId,String include,String exclude,String arg,String source);
    private static native void cancelDesktopNative();
    static void cancelDesktop(){if(AVAILABLE)cancelDesktopNative();}
    static synchronized String desktop(String path,String db,String custom,int verb,int flags,int typeId,String include,String exclude,String arg,String source) {
        if(!AVAILABLE)return "{\"ok\":false,\"error\":\"Native engine unavailable\"}";
        byte[] result=desktopNative(path,db,custom,verb,flags,typeId,include,exclude,arg,source);
        return result==null?"{\"ok\":false,\"error\":\"Native report allocation failed\"}":new String(result,StandardCharsets.UTF_8);
    }
    static synchronized String scan(String path,String db,int flags) {
        if(!AVAILABLE) return null;
        byte[] raw=scanNative(path,db,flags);
        if(raw==null||raw.length==0) throw new IllegalStateException("CDiE returned an empty result");
        return new String(raw,StandardCharsets.UTF_8);
    }
}
