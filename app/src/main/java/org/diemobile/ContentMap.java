// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Presentation index over original evidence. A card never invents a recovered extent. */
final class ContentMap {
    static final int MAX_CARDS = 50000, MIN_TEXT_CODEPOINTS = 8, TEXT_PREVIEW_CODEPOINTS = 512;

    enum Kind {
        INPUT,
        IMAGE,
        AUDIO,
        VIDEO,
        ARCHIVE,
        DOCUMENT,
        TEXT,
        OTHER,
        UNASSIGNED
    }

    static final class Card {
        final Kind kind;
        final String label, format, preview;
        final long offset;
        final Long length;
        final int nativeRow;
        final boolean explicitInterval;

        Card(
                Kind kind,
                String label,
                String format,
                long offset,
                Long length,
                String preview,
                int nativeRow) {
            this(kind, label, format, offset, length, preview, nativeRow, false);
        }

        Card(
                Kind kind,
                String label,
                String format,
                long offset,
                Long length,
                String preview,
                int nativeRow,
                boolean explicitInterval) {
            this.explicitInterval = explicitInterval;
            this.kind = kind;
            this.label = label;
            this.format = format;
            this.offset = offset;
            this.length = length;
            this.preview = preview;
            this.nativeRow = nativeRow;
        }

        static Card inspection(Card associated, long sourceSize, long offset, long length)
                throws Exception {
            if (sourceSize < 0
                    || offset < 0
                    || length <= 0
                    || offset > sourceSize
                    || length > sourceSize - offset)
                throw new IOException(
                        "Choose a nonempty inspection interval within the captured input");
            FileRange range = new FileRange(sourceSize, offset, length);
            range.requireOperationBudget();
            return new Card(
                    Kind.OTHER,
                    "Inspection bytes [0x"
                            + Long.toHexString(offset)
                            + ", 0x"
                            + Long.toHexString(offset + length)
                            + ")",
                    "bytes",
                    offset,
                    length,
                    "",
                    associated.nativeRow,
                    true);
        }

        JSONObject json() throws Exception {
            return new JSONObject()
                    .put("kind", kind.name())
                    .put("label", label)
                    .put("format", format)
                    .put("physicalOffset", offset)
                    .put("sizeBytes", length == null ? JSONObject.NULL : length)
                    .put("nativeRowIndex", nativeRow < 0 ? JSONObject.NULL : nativeRow)
                    .put("textPreview", preview)
                    .put("explicitInspectionInterval", explicitInterval)
                    .put(
                            "nativeWitnessRelationship",
                            nativeRow < 0
                                    ? JSONObject.NULL
                                    : explicitInterval
                                            ? "Associated original finding; selected inspection"
                                                    + " bytes need not match its reported range"
                                            : "Original native finding for this card")
                    .put(
                            "extentMeaning",
                            explicitInterval
                                    ? "Explicit user-selected inspection interval; not an inferred"
                                            + " or native recovered-file boundary"
                                    : kind == Kind.INPUT
                                            ? "Exact selected input, not an inferred embedded-file"
                                                    + " boundary"
                                            : kind == Kind.TEXT
                                                    ? "Exact printable UTF-8 run outside reported"
                                                            + " bounded artifacts; candidate text,"
                                                            + " not a recovered text-file boundary"
                                                    : kind == Kind.UNASSIGNED
                                                            ? "Complement of reported bounded"
                                                                    + " artifacts; not proof of"
                                                                    + " meaningless data"
                                                            : length == null
                                                                    ? "Accepted native header only;"
                                                                            + " extent unknown, no"
                                                                            + " automatic carving"
                                                                    : nativeRow < 0
                                                                            ? "Explicit selected"
                                                                                  + " stored bytes;"
                                                                                  + " no native"
                                                                                  + " recovered-file"
                                                                                  + " boundary"
                                                                                  + " asserted"
                                                                            : "Native reported"
                                                                                    + " stored"
                                                                                    + " extent;"
                                                                                    + " full-file"
                                                                                    + " checks and"
                                                                                    + " complete"
                                                                                    + " decoding"
                                                                                    + " remain"
                                                                                    + " separate");
        }

        FileRange range(long sourceSize) {
            if (length == null)
                throw new IllegalArgumentException("This header has no reported extent");
            return new FileRange(sourceSize, offset, length);
        }
    }

    final List<Card> cards;
    final String primaryType, composition;
    final int rawCount, acceptedCount, boundedCount, textCount;
    final long coveredBytes, textScannedBytes;

    private ContentMap(
            List<Card> cards,
            String primaryType,
            String composition,
            int rawCount,
            int acceptedCount,
            int boundedCount,
            int textCount,
            long coveredBytes,
            long textScannedBytes) {
        this.cards = Collections.unmodifiableList(cards);
        this.primaryType = primaryType;
        this.composition = composition;
        this.rawCount = rawCount;
        this.acceptedCount = acceptedCount;
        this.boundedCount = boundedCount;
        this.textCount = textCount;
        this.coveredBytes = coveredBytes;
        this.textScannedBytes = textScannedBytes;
    }

    static ContentMap build(
            InputSnapshot input, String name, EvidenceRows raw, BooleanSupplier stop)
            throws Exception {
        List<Card> cards = new ArrayList<>();
        List<FileRange> bounded = new ArrayList<>();
        byte[] head = new byte[(int) Math.min(4096, input.size)];
        try (RandomAccessFile source = new RandomAccessFile(input.file, "r")) {
            source.readFully(head);
        }
        String type = Inspector.identify(head, head.length);
        add(cards, new Card(Kind.INPUT, "Selected input · " + name, type, 0, input.size, "", -1));
        int accepted = 0;
        for (int i = 0; i < raw.size(); i++) {
            EvidenceRows.check(stop);
            EvidenceRows.Row row = raw.row(i, stop);
            JSONObject value = row.json();
            if (!value.getBoolean("parserAccepted")) continue;
            accepted++;
            String format = value.optString("reportedExtension", row.name);
            if (format.isEmpty()) format = row.name;
            add(
                    cards,
                    new Card(
                            kind(format),
                            format + (row.sizeBytes == null ? " · header only" : " · stored range"),
                            format,
                            row.physicalOffset,
                            row.sizeBytes,
                            "",
                            row.index));
            if (row.sizeBytes != null)
                bounded.add(new FileRange(input.size, row.physicalOffset, row.sizeBytes));
        }
        bounded.sort(Comparator.comparingLong(range -> range.offset));
        List<FileRange> gaps = new ArrayList<>();
        long end = 0, covered = 0;
        int separated = 0;
        for (FileRange range : bounded) {
            if (range.offset > end) gaps.add(new FileRange(input.size, end, range.offset - end));
            if (range.offset >= end) separated++;
            long next = Math.max(end, range.offset + range.length);
            covered += next - Math.max(end, range.offset);
            end = next;
        }
        if (end < input.size) gaps.add(new FileRange(input.size, end, input.size - end));
        int beforeText = cards.size();
        long textScanned = 0;
        try (RandomAccessFile source = new RandomAccessFile(input.file, "r")) {
            for (FileRange gap : gaps) {
                EvidenceRows.check(stop);
                add(
                        cards,
                        new Card(
                                Kind.UNASSIGNED,
                                "Unassigned bytes",
                                "unassigned",
                                gap.offset,
                                gap.length,
                                "",
                                -1));
                textScanned += gap.length;
                scanText(source, gap, cards, stop);
            }
        }
        int texts = 0;
        for (int i = beforeText; i < cards.size(); i++) if (cards.get(i).kind == Kind.TEXT) texts++;
        cards.subList(1, cards.size())
                .sort(
                        Comparator.comparingLong((Card card) -> card.offset)
                                .thenComparing(card -> card.kind == Kind.UNASSIGNED ? 1 : 0));
        String composition =
                separated > 1
                        ? "Multiple separate stored artifacts observed · "
                                + separated
                                + " ranges; overlaps/nested artifacts retained"
                        : bounded.isEmpty()
                                ? "Primary header: "
                                        + type
                                        + " · no bounded embedded-file layout established"
                                : covered == input.size
                                        ? "Reported artifact ranges cover this input · additional"
                                                + " nested/unknown content remains possible"
                                        : "Stored artifact evidence plus unassigned bytes · "
                                                + covered
                                                + " / "
                                                + input.size
                                                + " bytes covered";
        return new ContentMap(
                cards,
                type,
                composition,
                raw.size(),
                accepted,
                bounded.size(),
                texts,
                covered,
                textScanned);
    }

    static Kind kind(String format) {
        String f = format.toLowerCase(Locale.ROOT);
        if (Arrays.asList(
                        "jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff", "ico", "heic",
                        "avif", "psd")
                .contains(f)) return Kind.IMAGE;
        if (Arrays.asList("wav", "flac", "mp3", "ogg", "aif", "aiff", "aac", "m4a", "mid")
                .contains(f)) return Kind.AUDIO;
        if (Arrays.asList(
                        "mp4", "mov", "avi", "mkv", "webm", "mpg", "mpeg", "m2ts", "3gp", "asf",
                        "flv")
                .contains(f)) return Kind.VIDEO;
        if (Arrays.asList(
                        "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "zst", "cab", "iso", "cpio",
                        "lz4")
                .contains(f)) return Kind.ARCHIVE;
        if (Arrays.asList("pdf", "rtf", "doc", "docx", "odt", "epub", "xls", "ppt").contains(f))
            return Kind.DOCUMENT;
        return Kind.OTHER;
    }

    private static void add(List<Card> cards, Card value) throws IOException {
        if (cards.size() == MAX_CARDS)
            throw new IOException(
                    "Content map exceeds 50,000 cards; no partial map published. Raw"
                            + " PhotoRec/strings tools retain separate budgets.");
        cards.add(value);
    }

    /** Strict UTF-8 run scanner, bounded preview, exact original byte extent across IO seams. */
    private static void scanText(
            RandomAccessFile source, FileRange gap, List<Card> cards, BooleanSupplier stop)
            throws Exception {
        source.seek(gap.offset);
        byte[] buffer = new byte[65536];
        TextRun run = new TextRun(cards);
        long position = gap.offset, remaining = gap.length;
        int need = 0, code = 0, minimum = 0;
        long sequenceAt = 0;
        while (remaining > 0) {
            EvidenceRows.check(stop);
            int count = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new EOFException("Content text source shortened");
            for (int i = 0; i < count; i++, position++) {
                int b = buffer[i] & 255;
                if (need > 0 && (b & 0xc0) != 0x80) {
                    run.flush(sequenceAt);
                    need = 0;
                }
                if (need == 0) {
                    if (b < 0x80) {
                        if (printable(b)) run.codepoint(b, position);
                        else run.flush(position);
                    } else if (b >= 0xc2 && b <= 0xf4) {
                        sequenceAt = position;
                        need = b < 0xe0 ? 1 : b < 0xf0 ? 2 : 3;
                        code = b & (need == 1 ? 31 : need == 2 ? 15 : 7);
                        minimum = need == 1 ? 0x80 : need == 2 ? 0x800 : 0x10000;
                    } else run.flush(position);
                } else {
                    code = code << 6 | b & 63;
                    if (--need == 0) {
                        if (code >= minimum
                                && code <= 0x10ffff
                                && !(code >= 0xd800 && code <= 0xdfff)
                                && printable(code)) run.codepoint(code, sequenceAt);
                        else run.flush(sequenceAt);
                    }
                }
            }
            remaining -= count;
        }
        run.flush(need == 0 ? position : sequenceAt);
    }

    private static boolean printable(int code) {
        return code == '\n'
                || code == '\r'
                || code == '\t'
                || !Character.isISOControl(code)
                        && code != 0x7f
                        && code != 0xfeff
                        && Character.getType(code) != Character.UNASSIGNED;
    }

    private static final class TextRun {
        final List<Card> cards;
        final StringBuilder preview = new StringBuilder();
        long start;
        int count;
        boolean nonWhitespace;

        TextRun(List<Card> cards) {
            this.cards = cards;
        }

        void codepoint(int code, long at) {
            if (count == 0) start = at;
            if (count < TEXT_PREVIEW_CODEPOINTS) preview.appendCodePoint(code);
            count++;
            nonWhitespace |= !Character.isWhitespace(code);
        }

        void flush(long end) throws IOException {
            if (count >= MIN_TEXT_CODEPOINTS && nonWhitespace)
                add(
                        cards,
                        new Card(
                                Kind.TEXT,
                                "UTF-8 text candidate",
                                "UTF-8",
                                start,
                                end - start,
                                preview.toString()
                                        + (count > TEXT_PREVIEW_CODEPOINTS
                                                ? "… [bounded preview; full range retained]"
                                                : ""),
                                -1));
            preview.setLength(0);
            count = 0;
            nonWhitespace = false;
        }
    }
}
