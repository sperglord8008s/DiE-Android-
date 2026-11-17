// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/** An explicitly owned private byte snapshot; display names never become paths. */
final class InputSnapshot implements AutoCloseable {
    final File file;
    final long size;
    final String sha256;
    private InputSnapshot(File file, long size, String sha256) { this.file = file; this.size = size; this.sha256 = sha256; }
    static InputSnapshot acquire(File source, File directory, long maximum, BooleanSupplier cancelled) throws Exception {
        EvidenceRows.check(cancelled); long size = source.length();
        if (!source.isFile() || size < 0 || size > maximum) throw new IOException("Input unavailable or exceeds the " + maximum + "-byte snapshot budget");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private input snapshot directory");
        File output = File.createTempFile("analysis-input-",".snapshot",directory); boolean success = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long copied = 0;
            try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[65536];
                while (copied < size) {
                    EvidenceRows.check(cancelled); int count = in.read(buffer,0,(int)Math.min(buffer.length,size-copied));
                    if (count < 0) throw new EOFException("Input shortened during snapshot acquisition");
                    out.write(buffer,0,count); digest.update(buffer,0,count); copied += count;
                }
                if (in.read() >= 0 || source.length() != size) throw new IOException("Input length changed during snapshot acquisition");
            }
            EvidenceRows.check(cancelled); success = true; return new InputSnapshot(output,size,Inspector.toHex(digest.digest()));
        } finally { if (!success) output.delete(); }
    }
    @Override public void close() { file.delete(); }
}
