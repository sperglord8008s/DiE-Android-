// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.apksig.util.*;

import org.json.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;

/**
 * Public apksig DataSource boundary; acquisition and cryptographic interpretation stay separate.
 */
final class ApkSigningInput implements AutoCloseable {
    static final long MAX_APK_BYTES = 256L * 1024 * 1024, MAX_IDSIG_BYTES = 8L * 1024 * 1024;
    static final long MAX_READ_BYTES = 8L * 1024 * 1024 * 1024, DEADLINE_NANOS = 30_000_000_000L;
    static final int MAX_BUFFER_BYTES = 16 * 1024 * 1024;
    private final RandomAccessFile file;
    private final Guard guard;
    final DataSource source;
    final V4Layout layout;

    ApkSigningInput(File apk, File idsig, BooleanSupplier stop) throws Exception {
        guard = new Guard(stop);
        guard.check();
        if (!apk.isFile() || apk.length() > MAX_APK_BYTES)
            throw new IOException("APK unavailable or exceeds 256 MiB signing input budget");
        layout = idsig == null ? null : V4Layout.read(idsig, guard);
        file = new RandomAccessFile(apk, "r");
        source = new CheckedSource(DataSources.asDataSource(file), guard);
    }

    void check() throws InterruptedIOException {
        guard.check();
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    private static final class Guard {
        private final BooleanSupplier stop;
        private final long started = System.nanoTime();
        private long readBytes;

        Guard(BooleanSupplier stop) {
            this.stop = stop;
        }

        synchronized void check() throws InterruptedIOException {
            EvidenceRows.check(stop);
            if (System.nanoTime() - started > DEADLINE_NANOS)
                throw new InterruptedIOException(
                        "APK signing cooperative 30-second deadline exceeded");
        }

        synchronized void read(long size) throws IOException {
            check();
            if (size < 0 || size > MAX_READ_BYTES - readBytes)
                throw new IOException("APK signing 8 GiB cumulative read budget exceeded");
            readBytes += size;
        }
    }

    private static final class CheckedSource implements DataSource {
        private final DataSource source;
        private final Guard guard;

        CheckedSource(DataSource source, Guard guard) {
            this.source = source;
            this.guard = guard;
        }

        public long size() {
            return source.size();
        }

        public DataSource slice(long offset, long size) {
            return new CheckedSource(source.slice(offset, size), guard);
        }

        public ByteBuffer getByteBuffer(long offset, int size) throws IOException {
            if (size > MAX_BUFFER_BYTES)
                throw new IOException("APK signing contiguous read exceeds 16 MiB buffer budget");
            guard.read(size);
            ByteBuffer result = source.getByteBuffer(offset, size);
            guard.check();
            return result;
        }

        public void copyTo(long offset, int size, ByteBuffer destination) throws IOException {
            guard.read(size);
            source.copyTo(offset, size, destination);
            guard.check();
        }

        public void feed(long offset, long size, DataSink destination) throws IOException {
            if (offset < 0 || size < 0 || offset > source.size() || size > source.size() - offset)
                throw new IOException("APK signing feed outside input");
            for (long position = 0; position < size; ) {
                long amount = Math.min(size - position, 1024 * 1024);
                guard.read(amount);
                source.feed(offset + position, amount, destination);
                position += amount;
                guard.check();
            }
        }
    }

    /**
     * Allocation-length gate only. The unmodified engine parses/verifies all cryptographic fields.
     */
    static final class V4Layout {
        final int version;
        final long size,
                hashingOffset,
                hashingSize,
                signingOffset,
                signingSize,
                treeOffset,
                treeSize,
                trailingOffset,
                trailingSize;

        private V4Layout(
                int version,
                long size,
                long[] hashing,
                long[] signing,
                long[] tree,
                long trailingOffset) {
            this.version = version;
            this.size = size;
            hashingOffset = hashing[0];
            hashingSize = hashing[1];
            signingOffset = signing[0];
            signingSize = signing[1];
            treeOffset = tree[0];
            treeSize = tree[1];
            this.trailingOffset = trailingOffset;
            trailingSize = size - trailingOffset;
        }

        private static V4Layout read(File input, Guard guard) throws Exception {
            if (!input.isFile() || input.length() > MAX_IDSIG_BYTES)
                throw new IOException(
                        "Detached signature unavailable or exceeds 8 MiB input budget");
            try (RandomAccessFile file = new RandomAccessFile(input, "r")) {
                long size = file.length();
                int version = Integer.reverseBytes(file.readInt());
                long[] hashing = chunk(file, size), signing = chunk(file, size);
                long[] tree =
                        file.getFilePointer() == size ? new long[] {-1, -1} : chunk(file, size);
                guard.check();
                return new V4Layout(version, size, hashing, signing, tree, file.getFilePointer());
            }
        }

        private static long[] chunk(RandomAccessFile file, long size) throws IOException {
            int length = Integer.reverseBytes(file.readInt());
            long offset = file.getFilePointer();
            // Upstream allocates new byte[length] before checking the remaining stream.
            if (length < 0 || length > size - offset)
                throw new IOException(
                        "Detached signature declares an invalid/out-of-file allocation length");
            file.seek(offset + length);
            return new long[] {offset, length};
        }

        JSONObject json() throws Exception {
            return new JSONObject()
                    .put("version", version)
                    .put("sizeBytes", size)
                    .put("hashingInfoOffset", hashingOffset)
                    .put("hashingInfoBytes", hashingSize)
                    .put("signingInfosOffset", signingOffset)
                    .put("signingInfosBytes", signingSize)
                    .put("verityTreeOffset", treeOffset < 0 ? JSONObject.NULL : treeOffset)
                    .put("verityTreeBytes", treeSize < 0 ? JSONObject.NULL : treeSize)
                    .put("trailingOffset", trailingOffset)
                    .put("trailingBytes", trailingSize)
                    .put(
                            "interpretation",
                            "Offsets are within the separately selected .idsig, not the APK. The"
                                + " pinned verifier accepts an omitted tree and ignores bytes after"
                                + " the optional tree; trailing bytes are retained in the input"
                                + " identity but are not verified by v4.");
        }
    }
}
