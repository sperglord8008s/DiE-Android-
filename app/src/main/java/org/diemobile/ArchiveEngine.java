// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

/** Pinned libarchive descriptor API. Production calls belong in the isolated worker. */
final class ArchiveEngine {
    static final boolean AVAILABLE;
    static final String ERROR;

    static {
        boolean available = false;
        String error = "";
        try {
            System.loadLibrary("diearchive");
            available = true;
        } catch (LinkageError failure) {
            error = failure.toString();
        }
        AVAILABLE = available;
        ERROR = error;
    }

    static native String inspectFd(int descriptor, long offset, long length);

    static native String extractFd(
            int descriptor, int output, long offset, long length, int member);

    private ArchiveEngine() {}
}
