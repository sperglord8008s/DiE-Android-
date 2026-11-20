// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.function.BooleanSupplier;

/** Private, immutable export staging decouples the document picker from live UI state. */
final class PreparedExport {
    interface TextProducer { void write(Writer output) throws Exception; }
    interface ByteProducer { void write(OutputStream output) throws Exception; }
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
            try (Writer out = new OutputStreamWriter(new FileOutputStream(staged), StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT))) { out.write(text); }
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
    static PreparedExport bytes(File directory, String name, byte[] value, BooleanSupplier cancelled) throws IOException {
        if (value.length > FileRange.MAX_OPERATION_BYTES) throw new IOException("Binary value exceeds the 256 MiB export budget");
        File staged = create(directory); boolean success = false;
        try {
            try (OutputStream out = new FileOutputStream(staged)) {
                for (int i = 0; i < value.length; i += 65536) { if (cancelled.getAsBoolean()) throw new InterruptedIOException("Export cancelled"); out.write(value,i,Math.min(65536,value.length-i)); }
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Export cancelled");
            }
            success = true; return new PreparedExport(staged,name,"application/octet-stream",staged.length());
        } finally { if (!success) staged.delete(); }
    }
    /** Stream large structured/smali reports without materializing another full string/DOM. */
    static PreparedExport stream(File directory, String name, String mime, TextProducer producer, BooleanSupplier cancelled) throws Exception {
        File staged = create(directory); boolean success = false;
        try {
            try (OutputStream file = new FileOutputStream(staged); OutputStream bounded = new FilterOutputStream(file) {
                private long written;
                @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}, 0, 1); }
                @Override public void write(byte[] data, int offset, int length) throws IOException {
                    if (cancelled.getAsBoolean()) throw new InterruptedIOException("Export cancelled");
                    if (length > FileRange.MAX_OPERATION_BYTES - written) throw new IOException("Encoded export exceeds the 256 MiB staging budget");
                    out.write(data, offset, length); written += length;
                }
            }; Writer out = new OutputStreamWriter(bounded, StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT))) {
                producer.write(out); if (cancelled.getAsBoolean()) throw new InterruptedIOException("Export cancelled");
            }
            success = true; return new PreparedExport(staged, name, mime, staged.length());
        } finally { if (!success) staged.delete(); }
    }
    /** Bounded binary staging for actual recovered-file bundles, independent of picker state. */
    static PreparedExport streamBytes(File directory, String name, String mime, ByteProducer producer,
            BooleanSupplier cancelled) throws Exception {
        File staged = create(directory);
        boolean success = false;
        try {
            try (OutputStream file = new FileOutputStream(staged); OutputStream output = new FilterOutputStream(file) {
                private long written;
                @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}, 0, 1); }
                @Override public void write(byte[] data, int offset, int length) throws IOException {
                    if (cancelled.getAsBoolean()) throw new InterruptedIOException("Export cancelled");
                    if (length > FileRange.MAX_OPERATION_BYTES - written)
                        throw new IOException("Binary export exceeds the 256 MiB staging budget");
                    out.write(data, offset, length);
                    written += length;
                }
            }) {
                producer.write(output);
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Export cancelled");
            }
            success = true;
            return new PreparedExport(staged, name, mime, staged.length());
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
