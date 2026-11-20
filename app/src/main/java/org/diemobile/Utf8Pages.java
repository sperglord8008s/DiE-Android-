// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;

/** Exact bounded UTF-8 windows. Byte intervals partition text without dropping seam characters. */
final class Utf8Pages {
    static final int CHARACTERS = 16384, MAX_BYTES = 65536;

    static final class Page {
        final long offset, end, size;
        final String text;

        Page(long offset, long end, long size, String text) {
            this.offset = offset;
            this.end = end;
            this.size = size;
            this.text = text;
        }

        boolean more() {
            return end < size;
        }
    }

    /**
     * Bounded visited positions; source bytes remain available independently of navigation history.
     */
    static final class Cursor {
        static final int HISTORY_LIMIT = 512;
        long offset;
        boolean expired;
        private final ArrayDeque<Long> history = new ArrayDeque<>();

        void move(long target) {
            if (target == offset) return;
            if (history.size() == HISTORY_LIMIT) {
                history.removeFirst();
                expired = true;
            }
            history.addLast(offset);
            offset = target;
        }

        boolean canBack() {
            return !history.isEmpty();
        }

        long backTarget() {
            return history.isEmpty() ? offset : history.peekLast();
        }

        void back() {
            if (!history.isEmpty()) offset = history.removeLast();
        }

        long[] history() {
            long[] result = new long[history.size()];
            int i = 0;
            for (long value : history) result[i++] = value;
            return result;
        }

        static Cursor restore(long size, long offset, long[] history, boolean expired)
                throws IOException {
            if (size < 0
                    || offset < 0
                    || offset > size
                    || history != null && history.length > HISTORY_LIMIT)
                throw new IOException("Saved text positions exceed the source/navigation budget");
            Cursor cursor = new Cursor();
            cursor.offset = offset;
            cursor.expired = expired;
            if (history != null)
                for (long value : history) {
                    if (value < 0 || value > size)
                        throw new IOException("Saved text position is outside the captured input");
                    cursor.history.addLast(value);
                }
            return cursor;
        }
    }

    static Page read(File source, long size, long offset, int characters, BooleanSupplier stop)
            throws IOException {
        bounds(size, offset, characters);
        int count = (int) Math.min(size - offset, Math.min(MAX_BYTES, characters * 4 + 4));
        byte[] bytes = bytes(source, size, offset, count, stop);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer output = CharBuffer.allocate(characters);
        CoderResult result =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(input, output, offset + count == size);
        if (result.isError()) throw invalid(offset + input.position(), result);
        if (input.position() == 0 && offset != size)
            throw new IOException("UTF-8 page made no progress");
        EvidenceRows.check(stop);
        output.flip();
        return new Page(offset, offset + input.position(), size, output.toString());
    }

    /** Last bounded text window; displayed start is an actual complete-codepoint boundary. */
    static Page tail(File source, long size, int characters, BooleanSupplier stop)
            throws IOException {
        bounds(size, 0, characters);
        long start = Math.max(0, size - MAX_BYTES);
        byte[] bytes = bytes(source, size, start, (int) (size - start), stop);
        int skip = 0;
        if (start > 0)
            while (skip < 3 && skip < bytes.length && (bytes[skip] & 0xc0) == 0x80) skip++;
        String suffix;
        try {
            suffix =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip))
                            .toString();
        } catch (CharacterCodingException failure) {
            throw new IOException(
                    "The final UTF-8 window is invalid; use an explicit byte offset or Hex",
                    failure);
        }
        int first = Math.max(0, suffix.length() - characters);
        if (first > 0
                && first < suffix.length()
                && Character.isLowSurrogate(suffix.charAt(first))
                && Character.isHighSurrogate(suffix.charAt(first - 1))) first++;
        int prefixBytes = suffix.substring(0, first).getBytes(StandardCharsets.UTF_8).length;
        EvidenceRows.check(stop);
        return new Page(start + skip + prefixBytes, size, size, suffix.substring(first));
    }

    private static byte[] bytes(
            File source, long size, long offset, int count, BooleanSupplier stop)
            throws IOException {
        EvidenceRows.check(stop);
        if (!source.isFile() || source.length() != size)
            throw new IOException("Text snapshot expired or changed length");
        byte[] bytes = new byte[count];
        try (RandomAccessFile file = new RandomAccessFile(source, "r")) {
            if (file.length() != size) throw new IOException("Text snapshot length changed");
            file.seek(offset);
            file.readFully(bytes);
            if (file.length() != size)
                throw new IOException("Text snapshot length changed while reading");
        }
        if (source.length() != size)
            throw new IOException("Text snapshot expired or changed length");
        EvidenceRows.check(stop);
        return bytes;
    }

    private static IOException invalid(long offset, CoderResult result) {
        try {
            result.throwException();
        } catch (CharacterCodingException failure) {
            return new IOException(
                    "Invalid UTF-8 at byte "
                            + offset
                            + "; select a complete-character boundary or use Hex",
                    failure);
        }
        return new IOException("Invalid UTF-8 at byte " + offset);
    }

    private static void bounds(long size, long offset, int characters) throws IOException {
        if (size < 0 || offset < 0 || offset > size || characters < 2 || characters > CHARACTERS)
            throw new IOException(
                    "Text page requires a valid byte offset and 2–16384 UTF-16 units");
    }

    private Utf8Pages() {}
}
