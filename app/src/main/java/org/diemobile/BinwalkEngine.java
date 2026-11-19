// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

/** JNI data parsing only. Android calls scanFd inside the isolated worker. */
final class BinwalkEngine {
    static final boolean AVAILABLE;
    static final String ERROR;
    static {
        boolean available=false;String error="";
        try { System.loadLibrary("diebinwalk"); available=true; } catch (LinkageError failure) { error=failure.toString(); }
        AVAILABLE=available;ERROR=error;
    }
    private BinwalkEngine() {}
    static native String scanFd(int descriptor);
    /** Host fixture entry point; production Android dispatch uses granted descriptors. */
    static native String scanPath(String path);
}
