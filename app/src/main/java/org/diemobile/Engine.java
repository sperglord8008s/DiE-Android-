// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;
import java.nio.charset.StandardCharsets;
final class Engine {
    private static final Object CANCELLATION_LOCK = new Object();
    private static Cancellation activeCancellation;
    private static final String CANCELLED = "{\"ok\":false,\"error\":\"Scan cancelled\"}";
    static final boolean AVAILABLE;
    static { boolean ok; try { System.loadLibrary("diemobile"); ok=true; } catch(UnsatisfiedLinkError e){ok=false;} AVAILABLE=ok; }
    private static native byte[] scanNative(String path,String db,int flags);
    private static native byte[] toolNative(String path,int tool,long offset,int mode,int minimum,String selector,int json,int syntax);
    private static native byte[] recordsNative(String path,int tool,int mode,long offset,int syntax);
    static synchronized String records(String path,int tool,int mode,long offset,boolean attSyntax) {
        return records(path,tool,mode,offset,attSyntax,()->false);
    }
    static synchronized String records(String path,int tool,int mode,long offset,boolean attSyntax,java.util.function.BooleanSupplier cancelled) {
        if(!AVAILABLE)return "{\"schema\":\"org.diemobile.native-records\",\"schemaVersion\":1,\"ok\":false,\"kind\":\"unknown\",\"error\":\"Native engine unavailable\"}";
        if(cancelled.getAsBoolean())return "{\"schema\":\"org.diemobile.native-records\",\"schemaVersion\":1,\"ok\":false,\"kind\":\"unknown\",\"error\":\"Table request cancelled\"}";
        byte[] result=recordsNative(path,tool,mode,offset,attSyntax?1:0);
        if(cancelled.getAsBoolean())return "{\"schema\":\"org.diemobile.native-records\",\"schemaVersion\":1,\"ok\":false,\"kind\":\"unknown\",\"error\":\"Table request cancelled\"}";
        return result==null?"{\"schema\":\"org.diemobile.native-records\",\"schemaVersion\":1,\"ok\":false,\"kind\":\"unknown\",\"error\":\"Native report allocation failed\"}":new String(result,StandardCharsets.UTF_8);
    }
    static synchronized String tool(String path,int tool,long offset,int mode,int minimum) {
        return tool(path,tool,offset,mode,minimum,"",false,false);
    }
    static synchronized String tool(String path,int tool,long offset,int mode,int minimum,String selector,boolean json,boolean attSyntax) {
        if(!AVAILABLE)return "ERROR: Native engine unavailable.";
        byte[] raw=toolNative(path,tool,offset,mode,minimum,selector,json?1:0,attSyntax?1:0);
        return raw==null?"ERROR: Native report allocation failed.":new String(raw,StandardCharsets.UTF_8);
    }
    private static native byte[] desktopNative(String path,String db,String custom,int verb,int flags,int typeId,String include,String exclude,String arg,String source);
    private static native void cancelDesktopNative();
    private static native void resetDesktopCancellationNative();

    /** Owned by one request, including time spent waiting for the serialized engine. */
    static final class Cancellation {
        private boolean requested, finished;

        void cancel() {
            synchronized (CANCELLATION_LOCK) {
                if (finished || requested) return;
                requested = true;
                if (activeCancellation == this && AVAILABLE) cancelDesktopNative();
            }
        }

        boolean isRequested() {
            synchronized (CANCELLATION_LOCK) { return requested; }
        }
    }

    static String desktop(String path,String db,String custom,int verb,int flags,int typeId,String include,String exclude,String arg,String source) {
        return desktop(path,db,custom,verb,flags,typeId,include,exclude,arg,source,
            verb==1 ? new Cancellation() : null);
    }

    static synchronized String desktop(String path,String db,String custom,int verb,int flags,int typeId,String include,String exclude,String arg,String source,Cancellation cancellation) {
        if(!AVAILABLE)return "{\"ok\":false,\"error\":\"Native engine unavailable\"}";
        if(verb==1 && cancellation==null)throw new IllegalArgumentException("Scan requires a cancellation scope");
        if(verb!=1 && cancellation!=null)throw new IllegalArgumentException("Cancellation scopes apply to scans");
        if(cancellation!=null)synchronized(CANCELLATION_LOCK){
            if(cancellation.finished)throw new IllegalStateException("Scan cancellation scope cannot be reused");
            if(cancellation.requested){cancellation.finished=true;return CANCELLED;}
            // Reset before publishing the active scope. A later cancel can no longer
            // be lost to the JNI entry point resetting its global progress flag.
            resetDesktopCancellationNative();
            activeCancellation=cancellation;
        }
        byte[] result;
        boolean cancelled=false;
        try {result=desktopNative(path,db,custom,verb,flags,typeId,include,exclude,arg,source);}
        finally {
            if(cancellation!=null)synchronized(CANCELLATION_LOCK){
                cancelled=cancellation.requested;
                activeCancellation=null;cancellation.finished=true;
            }
        }
        if(cancelled)return CANCELLED;
        return result==null?"{\"ok\":false,\"error\":\"Native report allocation failed\"}":new String(result,StandardCharsets.UTF_8);
    }
    static synchronized String scan(String path,String db,int flags) {
        if(!AVAILABLE) return null;
        byte[] raw=scanNative(path,db,flags);
        if(raw==null||raw.length==0) throw new IllegalStateException("CDiE returned an empty result");
        return new String(raw,StandardCharsets.UTF_8);
    }
}
