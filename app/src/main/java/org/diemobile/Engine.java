// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;
import java.nio.charset.StandardCharsets;
final class Engine {
    static final boolean AVAILABLE;
    static { boolean ok; try { System.loadLibrary("diemobile"); ok=true; } catch(UnsatisfiedLinkError e){ok=false;} AVAILABLE=ok; }
    private static native byte[] scanNative(String path,String db,int flags);
    static synchronized String scan(String path,String db,int flags) {
        if(!AVAILABLE) return null;
        byte[] raw=scanNative(path,db,flags);
        if(raw==null||raw.length==0) throw new IllegalStateException("CDiE returned an empty result");
        return new String(raw,StandardCharsets.UTF_8);
    }
}
