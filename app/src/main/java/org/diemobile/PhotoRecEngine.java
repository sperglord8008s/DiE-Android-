// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

/** Unmodified PhotoRec format callbacks; Android uses granted descriptors only. */
final class PhotoRecEngine {
    static final boolean AVAILABLE;
    static final String ERROR;

    static {
        boolean available = false;
        String error = "";
        try {
            System.loadLibrary("diephotorec");
            available = true;
        } catch (LinkageError failure) {
            error = failure.toString();
        }
        AVAILABLE = available;
        ERROR = error;
    }

    private PhotoRecEngine() {}

    static native String scanFd(int descriptor);

    /** Host fixtures only; Android does not pass input paths to the worker. */
    static native String scanPath(String path);
}
