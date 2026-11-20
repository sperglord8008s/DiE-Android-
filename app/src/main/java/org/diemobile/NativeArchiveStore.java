// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Source-bound native evidence and independently checked derived logical member bytes. */
final class NativeArchiveStore {
    static final long MAX_INPUT = 1024L * 1024 * 1024;
    static final long MAX_MEMBER = 256L * 1024 * 1024;
    static final int MAX_REPORT = 24 * 1024 * 1024 + 4096;
    static final String REVISION = "03036ffa409a2aa1c5bbad35be2202641a040fda";

    static final class Member {
        final int index;
        final Long size;
        final String name, raw;
        final boolean directory, regular, linked, encrypted, validName;

        private Member(JSONObject value, int expectedIndex) throws Exception {
            if (number(value, "index") != expectedIndex)
                throw new IOException("Archive entry order changed");
            index = expectedIndex;
            raw = value.toString();
            size = value.isNull("sizeBytes") ? null : number(value, "sizeBytes");
            long type = value.isNull("fileType") ? 0 : number(value, "fileType");
            regular = type == 0100000;
            directory = type == 0040000;
            linked =
                    !value.isNull("hardlink")
                            || !value.isNull("symlink")
                            || !value.isNull("hardlinkUtf8")
                            || !value.isNull("symlinkUtf8");
            encrypted =
                    number(value, "encrypted") != 0
                            || number(value, "dataEncrypted") != 0
                            || number(value, "metadataEncrypted") != 0;
            JSONObject text = value.optJSONObject("pathnameUtf8");
            if (text == null) text = value.optJSONObject("pathname");
            name = text == null ? "[no returned name]" : text.getString("text");
            validName = text != null && flag(text, "utf8Valid");
        }

        boolean canAcquire() {
            return regular
                    && !linked
                    && !encrypted
                    && (size == null || size >= 0 && size <= MAX_MEMBER);
        }

        /** Tree navigation is virtual. Ambiguous names remain available in All entries. */
        boolean treeName() {
            if (!validName
                    || name.isEmpty()
                    || name.length() > 8192
                    || name.startsWith("/")
                    || name.indexOf('\\') >= 0
                    || name.matches("^[A-Za-z]:.*")) return false;
            for (String part : name.split("/", -1))
                if (part.equals(".") || part.equals("..")) return false;
            for (int i = 0; i < name.length(); i++)
                if (name.charAt(i) < 32 || name.charAt(i) == 127) return false;
            return true;
        }

        JSONObject json() throws Exception {
            return new JSONObject(raw);
        }
    }

    static final class Inventory {
        final InputSnapshot source;
        final long offset, length;
        final String raw, format, failure;
        final boolean complete;
        final List<Member> members;

        private Inventory(InputSnapshot source, long offset, long length, String raw, int selected)
                throws Exception {
            requireScope(source, offset, length);
            if (raw == null || raw.length() > MAX_REPORT)
                throw new IOException("Archive report exceeds budget or is absent");
            JSONObject report = new JSONObject(raw);
            JSONObject transport = report.optJSONObject("worker");
            if (transport != null
                    && (!flag(transport, "isolatedUid")
                            || !"archive".equals(transport.getString("engine"))))
                throw new IOException("Archive capture has an invalid worker identity");
            this.source = source;
            this.offset = offset;
            this.length = length;
            this.raw = raw;
            boolean ok = flag(report, "ok");
            if (!report.has("schema")) {
                if (ok
                        || report.optBoolean("complete")
                        || report.has("entries")
                        || report.optString("error").isEmpty())
                    throw new IOException("Archive report has no supported schema");
                complete = false;
                failure = report.getString("error");
                format = "Unidentified / worker failure";
                members = Collections.emptyList();
                return;
            }
            if (!report.getString("schema").equals("org.diemobile.archive-evidence")
                    || number(report, "schemaVersion") != 1
                    || !report.getString("adapter").equals("diemobile-libarchive-1")
                    || !report.getString("sourceRevision").equals(REVISION)
                    || number(report, "fileSizeBytes") != source.size
                    || number(report, "scopeOffset") != offset
                    || number(report, "scopeSizeBytes") != length
                    || number(report, "selectedIndex") != selected
                    || flag(report, "entryDataReadRequested") != (selected >= 0)
                    || flag(report, "complete") != ok
                    || flag(report, "externalProgramsEnabled")
                    || flag(report, "filesystemReaderEnabled")
                    || flag(report, "pathExtractionEnabled")
                    || flag(report, "passphraseSupport")
                    || !flag(report, "callerDescriptorPositionPreserved")
                    || !flag(report, "ownedDescriptorDuplicate")
                    || number(report, "maximumInputBytes") != MAX_INPUT
                    || number(report, "maximumMemberBytes") != MAX_MEMBER
                    || number(report, "maximumEntries") != 20000
                    || number(report, "maximumSourceReadWorkBytes") != 4L * 1024 * 1024 * 1024
                    || number(report, "maximumTransformedStreamBytes") != 512L * 1024 * 1024
                    || number(report, "lzmaDecoderMemoryLimitBytes") != 64L * 1024 * 1024
                    || number(report, "zstdMaximumWindowBytes") != 64L * 1024 * 1024
                    || number(report, "cooperativeDeadlineSeconds") != 30
                    || ok && !report.isNull("error")
                    || selected < 0
                            && (!report.isNull("selectedEntry")
                                    || !report.isNull("acquiredSizeBytes")))
                throw new IOException("Archive report identity, scope or safety contract differs");
            JSONArray entries = report.getJSONArray("entries");
            if (entries.length() > 20000 || number(report, "entryCount") != entries.length())
                throw new IOException(
                        "Archive entry count differs from the complete returned list");
            List<Member> rows = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++)
                rows.add(new Member(entries.getJSONObject(i), i));
            members = Collections.unmodifiableList(rows);
            complete = ok;
            failure = report.isNull("error") ? "" : report.getString("error");
            if (!ok && failure.isEmpty())
                throw new IOException("Failed archive report omitted its diagnostic");
            format = report.isNull("formatName") ? "Unidentified" : report.getString("formatName");
        }

        JSONObject json() throws Exception {
            return new JSONObject(raw);
        }
    }

    static final class AcquisitionException extends IOException {
        final String report;

        AcquisitionException(String report, Exception cause) {
            super(cause.getMessage(), cause);
            this.report = report;
        }
    }

    static final class Acquired implements AutoCloseable {
        final InputSnapshot input;
        final String crc32, extractionReport;

        Acquired(InputSnapshot input, String crc32, String extractionReport) {
            this.input = input;
            this.crc32 = crc32;
            this.extractionReport = extractionReport;
        }

        @Override
        public void close() {
            input.close();
        }
    }

    static Inventory inspect(
            InputSnapshot input,
            long offset,
            long length,
            ArchiveRunner runner,
            BooleanSupplier stop)
            throws Exception {
        requireScope(input, offset, length);
        input.checkIdentity(stop);
        String raw = runner.inspect(input.file, offset, length, stop);
        input.checkIdentity(stop);
        return restore(input, offset, length, raw);
    }

    static Inventory restore(InputSnapshot input, long offset, long length, String raw)
            throws Exception {
        return new Inventory(input, offset, length, raw, -1);
    }

    static Acquired acquire(
            Inventory inventory,
            Member member,
            File directory,
            ArchiveRunner runner,
            BooleanSupplier stop)
            throws Exception {
        EvidenceRows.check(stop);
        if (!inventory.complete
                || member.index < 0
                || member.index >= inventory.members.size()
                || inventory.members.get(member.index) != member
                || !member.canAcquire())
            throw new IOException(
                    "Only indexed regular, unlinked, unencrypted members of a complete inventory"
                            + " can be acquired");
        inventory.source.checkIdentity(stop);
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create private member directory");
        File output = File.createTempFile("analysis-input-", ".snapshot", directory);
        boolean transferred = false;
        try {
            String raw =
                    runner.extract(
                            inventory.source.file,
                            output,
                            inventory.offset,
                            inventory.length,
                            member.index,
                            stop);
            inventory.source.checkIdentity(stop);
            long size = output.length();
            try {
                validateAcquisition(inventory, member, size, raw);
            } catch (Exception failure) {
                throw new AcquisitionException(raw, failure);
            }
            RangeAnalysis.Result identity =
                    RangeAnalysis.inspect(output, new FileRange(size, 0, size), stop);
            InputSnapshot child =
                    InputSnapshot.restore(output, directory, size, identity.sha256, stop);
            EvidenceRows.check(stop);
            transferred = true;
            return new Acquired(child, identity.crc32, raw);
        } finally {
            if (!transferred) output.delete();
        }
    }

    static void validateAcquisition(Inventory inventory, Member member, long size, String raw)
            throws Exception {
        Inventory result =
                new Inventory(
                        inventory.source, inventory.offset, inventory.length, raw, member.index);
        JSONObject report = result.json();
        if (!result.complete)
            throw new IOException("Archive acquisition failed: " + result.failure);
        if (!stableEntries(inventory.json().getJSONArray("entries"), report.getJSONArray("entries"))
                || !stableMember(member.json(), report.getJSONObject("selectedEntry"))
                || !same(inventory.json().get("formatCode"), report.get("formatCode"))
                || !same(inventory.json().get("formatName"), report.get("formatName")))
            throw new IOException(
                    "Archive acquisition metadata differs from the captured inventory");
        if (size < 0
                || size > MAX_MEMBER
                || number(report, "acquiredSizeBytes") != size
                || member.size != null && size != member.size)
            throw new IOException("Archive output differs from its exact acquired/declared size");
    }

    static void requireScope(InputSnapshot input, long offset, long length) throws IOException {
        if (input == null
                || input.size < 0
                || input.size > MAX_INPUT
                || offset < 0
                || length < 0
                || offset > input.size
                || length > input.size - offset)
            throw new IOException(
                    "Archive parsing interval exceeds the exact input / 1 GiB budget");
    }

    private static long number(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw new IOException("Archive field is not an exact integer: " + key);
        return ((Number) value).longValue();
    }

    private static boolean flag(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Boolean))
            throw new IOException("Archive field is not a boolean: " + key);
        return (Boolean) value;
    }

    private static boolean stableEntries(JSONArray before, JSONArray after) throws Exception {
        if (before.length() != after.length()) return false;
        for (int i = 0; i < before.length(); i++)
            if (!stableMember(before.getJSONObject(i), after.getJSONObject(i))) return false;
        return true;
    }

    private static boolean stableMember(JSONObject before, JSONObject after) throws Exception {
        JSONObject a = new JSONObject(before.toString()), b = new JSONObject(after.toString());
        // CAB's upstream getter changes when data is read versus skipped. Preserve both raw
        // reports, but never treat that stream position as identity or a physical byte offset.
        a.remove("headerPositionUncompressedStream");
        b.remove("headerPositionUncompressedStream");
        return same(a, b);
    }

    private static boolean same(Object a, Object b) throws Exception {
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject left = (JSONObject) a, right = (JSONObject) b;
            if (left.length() != right.length()) return false;
            Iterator<String> keys = left.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!right.has(key) || !same(left.get(key), right.get(key))) return false;
            }
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray left = (JSONArray) a, right = (JSONArray) b;
            if (left.length() != right.length()) return false;
            for (int i = 0; i < left.length(); i++)
                if (!same(left.get(i), right.get(i))) return false;
            return true;
        }
        return a == null ? b == null : a.equals(b);
    }

    private NativeArchiveStore() {}
}
