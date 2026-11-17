// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Private, immutable export staging decouples the document picker from live UI state. */
final class PreparedExport {
    final File file;
    final String name, mime;
    final long size;
    private PreparedExport(File file, String name, String mime, long size) {
        this.file = file; this.name = name; this.mime = mime; this.size = size;
    }
    static PreparedExport text(File directory, String name, String text) throws IOException {
        if (text.length() > 32 * 1024 * 1024) throw new IOException("Report staging limit: 32 million characters");
        File staged = create(directory); boolean success = false;
        try {
            try (Writer out = new OutputStreamWriter(new FileOutputStream(staged), StandardCharsets.UTF_8)) { out.write(text); }
            if (staged.length() > FileRange.MAX_OPERATION_BYTES) throw new IOException("Encoded report exceeds staging budget");
            success = true; return new PreparedExport(staged, name, "application/json", staged.length());
        } finally { if (!success) staged.delete(); }
    }
    static PreparedExport range(File directory, String name, File source, FileRange range, BooleanSupplier cancelled) throws IOException {
        File staged = create(directory); boolean success = false;
        try {
            try (OutputStream out = new FileOutputStream(staged)) { RangeAnalysis.copy(source, range, out, cancelled); }
            success = true; return new PreparedExport(staged, name, "application/octet-stream", staged.length());
        } finally { if (!success) staged.delete(); }
    }
    static PreparedExport restore(File directory, String path, String name, String mime, long size) throws IOException {
        File file = new File(path).getCanonicalFile();
        if (!directory.getCanonicalFile().equals(file.getParentFile()) || !file.getName().startsWith("tool-export-")
            || !file.isFile() || file.length() != size || size < 0 || size > FileRange.MAX_OPERATION_BYTES) {
            throw new IOException("Prepared export is unavailable or outside the private export directory");
        }
        return new PreparedExport(file, name, mime, size);
    }
    void copyTo(OutputStream output, BooleanSupplier cancelled) throws IOException {
        RangeAnalysis.copy(file, new FileRange(size, 0, size), output, cancelled);
    }
    void discard() { file.delete(); }
    private static File create(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private export directory");
        return File.createTempFile("tool-export-", ".snapshot", directory);
    }
}
