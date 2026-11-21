// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Ordinary file outputs from checked logical archive members, never archive paths or links. */
final class ArchiveFiles {
    static final long MAX_EVIDENCE_BYTES = 32L * 1024 * 1024;
    static final long MAX_EXPORT_NANOS = 5L * 60 * 1000 * 1000 * 1000;

    static byte[] header(InputSnapshot input) throws IOException {
        byte[] bytes = new byte[(int) Math.min(4096, input.size)];
        try (DataInputStream in = new DataInputStream(new FileInputStream(input.file))) {
            in.readFully(bytes);
        }
        return bytes;
    }

    /** This is a presentation hint, not another boundary/parser assertion. */
    static String extension(NativeArchiveStore.Member member, byte[] bytes) {
        String type = Inspector.identify(bytes, bytes.length);
        if (type.equals("PNG")) return "png";
        if (type.equals("PDF")) return "pdf";
        for (String[] hint :
                new String[][] {
                    {"JPEG", "jpg"}, {"GIF", "gif"}, {"WebP", "webp"}, {"WAV", "wav"},
                    {"AVI", "avi"}, {"FLAC", "flac"}, {"Ogg", "ogg"}, {"AVIF", "avif"},
                    {"HEIF", "heic"}, {"MP4", "mp4"}, {"ZIP", "zip"}, {"7z", "7z"},
                    {"RAR", "rar"}, {"gzip", "gz"}, {"XZ", "xz"}, {"bzip2", "bz2"},
                    {"Zstandard", "zst"}, {"Microsoft CAB", "cab"}, {"TAR", "tar"}
                }) if (type.startsWith(hint[0])) return hint[1];
        int dot = member.name.lastIndexOf('.');
        String suffix = dot < 0 ? "" : member.name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (suffix.matches("[a-z0-9]{1,16}")) return suffix;
        return ContentPreview.looksLikeText(
                        bytes, member.size != null && bytes.length == member.size)
                ? "txt"
                : "bin";
    }

    static String filename(NativeArchiveStore.Member member, String extension) {
        // The exact original name stays in evidence; an index prefix preserves duplicate entries.
        String base = member.name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        StringBuilder safe = new StringBuilder();
        for (int at = 0; at < base.length() && safe.length() < 80; ) {
            int code = base.codePointAt(at);
            at += Character.charCount(code);
            if (Character.isLetterOrDigit(code) || code == '_' || code == '-' || code == ' ')
                safe.appendCodePoint(code);
            else safe.append('_');
        }
        String stem = safe.toString().trim();
        if (stem.isEmpty()) stem = "member";
        return String.format(Locale.ROOT, "entry-%06d-", member.index)
                + stem
                + "."
                + RecoveredFiles.extension(extension);
    }

    static PreparedExport file(
            NativeArchiveSession session,
            NativeArchiveStore.Member member,
            File directory,
            BooleanSupplier stop)
            throws Exception {
        try (NativeArchiveStore.Acquired acquired = session.acquire(member, directory, stop)) {
            String name = filename(member, extension(member, header(acquired.input)));
            return RecoveredFiles.namedRange(
                    acquired.input,
                    new FileRange(acquired.input.size, 0, acquired.input.size),
                    directory,
                    name,
                    stop);
        }
    }

    static PreparedExport zip(
            NativeArchiveSession session, File directory, BooleanSupplier cancelled)
            throws Exception {
        NativeArchiveSession.Location location = session.current();
        if (!location.inventory.complete)
            throw new IOException("Complete archive inventory required");
        List<NativeArchiveStore.Member> selection = ArchiveListing.selection(location);
        long declared = 0;
        int eligible = 0;
        for (NativeArchiveStore.Member member : selection) {
            EvidenceRows.check(cancelled);
            if (!member.canAcquire()) continue;
            if (++eligible > RecoveredFiles.MAX_FILES
                    || member.size != null
                            && member.size > RecoveredFiles.MAX_TOTAL_BYTES - declared)
                throw new IOException(
                        "Archive file ZIP exceeds 4096 files / 240 MiB; select a"
                                + " smaller folder/filter or save individual members");
            if (member.size != null) declared += member.size;
        }
        if (eligible == 0)
            throw new IOException("No eligible regular members in this folder/filter");
        long started = System.nanoTime();
        BooleanSupplier stop =
                () -> cancelled.getAsBoolean() || System.nanoTime() - started > MAX_EXPORT_NANOS;
        location.input.checkIdentity(stop);
        PreparedExport staged = null;
        boolean success = false;
        try {
            Bundle bundle = new Bundle(session, location, selection, directory, stop);
            staged =
                    PreparedExport.streamBytes(
                            directory, "archive-files.zip", "application/zip", bundle::write, stop);
            location.input.checkIdentity(stop);
            success = true;
            return staged;
        } finally {
            if (!success && staged != null) staged.discard();
        }
    }

    /** Streams one selected logical scope; no second complete payload/report tree is retained. */
    private static final class Bundle {
        private final NativeArchiveSession session;
        private final NativeArchiveSession.Location location;
        private final List<NativeArchiveStore.Member> selection;
        private final File directory;
        private final BooleanSupplier stop;
        private final JSONArray files = new JSONArray(), excluded = new JSONArray();
        private long payloadBytes, evidenceBytes;

        Bundle(
                NativeArchiveSession session,
                NativeArchiveSession.Location location,
                List<NativeArchiveStore.Member> selection,
                File directory,
                BooleanSupplier stop) {
            this.session = session;
            this.location = location;
            this.selection = selection;
            this.directory = directory;
            this.stop = stop;
        }

        void write(OutputStream output) throws Exception {
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (NativeArchiveStore.Member member : selection) {
                    EvidenceRows.check(stop);
                    if (member.canAcquire()) writeMember(zip, member);
                    else
                        excluded.put(
                                new JSONObject()
                                        .put("entryIndex", member.index)
                                        .put("originalName", member.name)
                                        .put("entryRawJson", member.raw)
                                        .put(
                                                "reason",
                                                "Not a regular unlinked unencrypted member within"
                                                    + " the individual acquisition budget"));
                }
                entry(zip, "_evidence/archive-trail.json");
                writeEvidence(zip, out -> session.writeCapturedEvidence(out, stop));
                zip.closeEntry();
                entry(zip, "_archive.json");
                writeEvidence(zip, out -> EvidenceJson.write(out, manifest(), stop));
                zip.closeEntry();
            }
        }

        private void writeMember(ZipOutputStream zip, NativeArchiveStore.Member member)
                throws Exception {
            // Acquisition revalidates the full directory, index, source and output bytes.
            try (NativeArchiveStore.Acquired acquired = session.acquire(member, directory, stop)) {
                InputSnapshot input = acquired.input;
                if (input.size > RecoveredFiles.MAX_TOTAL_BYTES - payloadBytes)
                    throw new IOException("Actual archive payloads exceed 240 MiB");
                payloadBytes += input.size;
                String format = extension(member, header(input));
                String name = filename(member, format);
                entry(zip, name);
                input.checkIdentity(stop);
                RangeAnalysis.copy(input.file, new FileRange(input.size, 0, input.size), zip, stop);
                input.checkIdentity(stop);
                zip.closeEntry();
                String reportName = "_evidence/entry-" + member.index + ".json";
                entry(zip, reportName);
                writeEvidence(zip, out -> out.write(acquired.extractionReport));
                zip.closeEntry();
                files.put(
                        new JSONObject()
                                .put("file", name)
                                .put("mime", RecoveredFiles.mime(format))
                                .put("entryIndex", member.index)
                                .put("originalName", member.name)
                                .put("sizeBytes", input.size)
                                .put("sha256", input.sha256)
                                .put("crc32", acquired.crc32)
                                .put("physicalOffset", JSONObject.NULL)
                                .put("acquisitionEvidence", reportName));
            }
        }

        private JSONObject manifest() throws Exception {
            return new JSONObject()
                    .put("schema", "org.diemobile.archive-files")
                    .put("schemaVersion", 1)
                    .put("sourceSha256", location.input.sha256)
                    .put("sourceSizeBytes", location.input.size)
                    .put("parsingOffset", location.inventory.offset)
                    .put("parsingLength", location.inventory.length)
                    .put("allEntries", location.allEntries)
                    .put("folder", location.prefix)
                    .put("filter", location.filter)
                    .put("allMatchingPages", true)
                    .put("displayedFoldersIncludeDescendants", true)
                    .put("files", files)
                    .put("excluded", excluded)
                    .put(
                            "meaning",
                            "Files contain checked decoded logical member bytes. Index-prefixed"
                                + " flat names preserve duplicates without extracting paths or"
                                + " links. Original names and complete acquisition/current-chain"
                                + " evidence remain in this ZIP. Physical member offsets are"
                                + " unknown.");
        }

        private void writeEvidence(OutputStream zip, PreparedExport.TextProducer producer)
                throws Exception {
            OutputStream counted =
                    new FilterOutputStream(zip) {
                        @Override
                        public void write(int value) throws IOException {
                            write(new byte[] {(byte) value}, 0, 1);
                        }

                        @Override
                        public void write(byte[] data, int offset, int length) throws IOException {
                            if (length > MAX_EVIDENCE_BYTES - evidenceBytes)
                                throw new IOException(
                                        "Archive file ZIP evidence exceeds 32 MiB; narrow the"
                                            + " selection");
                            EvidenceRows.check(stop);
                            out.write(data, offset, length);
                            evidenceBytes += length;
                        }
                    };
            // Do not close this writer: it borrows the current ZIP entry's output stream.
            Writer writer = new OutputStreamWriter(counted, StandardCharsets.UTF_8);
            producer.write(writer);
            writer.flush();
        }
    }

    private static void entry(ZipOutputStream zip, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
    }

    private ArchiveFiles() {}
}
