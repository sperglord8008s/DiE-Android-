// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;

/** Streaming analysis/export of a checked physical range; no format interpretation. */
final class RangeAnalysis {
    static final class Result {
        final FileRange range;
        final String sha256, crc32;
        final double entropy;
        Result(FileRange range, String sha256, String crc32, double entropy) {
            this.range = range; this.sha256 = sha256; this.crc32 = crc32; this.entropy = entropy;
        }
    }

    private RangeAnalysis() {}

    static Result inspect(File source, FileRange range, BooleanSupplier cancelled) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); CRC32 crc = new CRC32();
        long[] histogram = new long[256];
        transfer(source, range, cancelled, (buffer, count) -> {
            hash.update(buffer, 0, count); crc.update(buffer, 0, count);
            for (int i = 0; i < count; i++) histogram[buffer[i] & 255]++;
        });
        return new Result(range, Inspector.toHex(hash.digest()), String.format(java.util.Locale.ROOT, "%08x", crc.getValue()),
            Inspector.entropy(histogram, range.length));
    }

    static void copy(File source, FileRange range, OutputStream output, BooleanSupplier cancelled) throws IOException {
        transfer(source, range, cancelled, (buffer, count) -> output.write(buffer, 0, count));
    }

    private interface Consumer { void accept(byte[] buffer, int count) throws IOException; }
    private static void transfer(File source, FileRange range, BooleanSupplier cancelled, Consumer consumer) throws IOException {
        range.requireOperationBudget(); range.requireCurrentLength(source.length());
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Range operation cancelled");
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            range.requireCurrentLength(input.length()); input.seek(range.offset);
            byte[] buffer = new byte[65536]; long remaining = range.length;
            while (remaining > 0) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Range operation cancelled");
                int count = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                if (count < 0) throw new EOFException("Input ended before the selected range");
                consumer.accept(buffer, count); remaining -= count;
            }
            range.requireCurrentLength(input.length());
        }
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Range operation cancelled");
    }
}
