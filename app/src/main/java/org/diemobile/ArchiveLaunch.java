// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.JSONObject;

import java.io.IOException;
import java.io.Writer;

/** Source-bound parsing request. An observed start never becomes an inferred recovered extent. */
final class ArchiveLaunch {
    static final int MAX_CHARACTERS = 64 * 1024;
    final String raw, sha256;
    final long size, offset, length;
    final String findingKind;
    final Integer findingIndex;

    private ArchiveLaunch(String raw) throws Exception {
        if (raw == null || raw.length() > MAX_CHARACTERS)
            throw new IOException(
                    "Archive launch context exceeds 64 Ki-characters; use an explicit interval in"
                            + " the archive browser");
        JSONObject value = new JSONObject(raw);
        if (!value.getString("schema").equals("org.diemobile.archive-launch")
                || number(value, "schemaVersion") != 1)
            throw new IOException("Unsupported archive launch context");
        sha256 = value.getString("sourceSha256");
        size = number(value, "sourceSizeBytes");
        offset = number(value, "scopeOffset");
        length = number(value, "scopeSizeBytes");
        if (!sha256.matches("[0-9a-f]{64}")
                || size < 0
                || size > NativeArchiveStore.MAX_INPUT
                || offset < 0
                || offset > size
                || length < 0
                || length > size - offset
                || !value.isNull("recoveredExtentBytes"))
            throw new IOException("Invalid source-bound archive parsing interval");
        if (!value.isNull("findingKind")) {
            findingKind = value.getString("findingKind");
            if (!findingKind.equals("binwalk-candidates")
                    && !findingKind.equals("photorec-candidates"))
                throw new IOException("Archive launch does not identify a supported finding scope");
            JSONObject record = new JSONObject(value.getString("findingRecordRaw"));
            long index = number(record, "index");
            if (index < 0
                    || index > Integer.MAX_VALUE
                    || number(record, "physicalOffset") != offset
                    || offset == size
                    || length != size - offset)
                throw new IOException("Finding identity differs from its explicit parsing tail");
            findingIndex = (int) index;
            JSONObject input =
                    new JSONObject(value.getString("findingProvenanceRaw")).getJSONObject("input");
            if (!input.getString("sha256").equals(sha256) || number(input, "sizeBytes") != size)
                throw new IOException("Finding provenance belongs to another captured input");
        } else {
            findingKind = null;
            findingIndex = null;
        }
        this.raw = raw;
    }

    static ArchiveLaunch forInput(String sha, long size, long offset, long length)
            throws Exception {
        return encode(base(sha, size, offset, length).put("findingKind", JSONObject.NULL));
    }

    static ArchiveLaunch fromFinding(
            InputSnapshot source, String kind, EvidenceRows.Row row, JSONObject provenance)
            throws Exception {
        if (row.physicalOffset == null
                || row.physicalOffset < 0
                || row.physicalOffset >= source.size)
            throw new IOException("Finding has no observed physical start within this input");
        return encode(
                base(
                                source.sha256,
                                source.size,
                                row.physicalOffset,
                                source.size - row.physicalOffset)
                        .put("findingKind", kind)
                        .put("findingRecordRaw", row.encoded)
                        .put("findingProvenanceRaw", encodeValue(provenance)));
    }

    static ArchiveLaunch parse(String raw) throws Exception {
        return new ArchiveLaunch(raw);
    }

    void requireSource(InputSnapshot input) throws IOException {
        if (input.size != size || !input.sha256.equals(sha256))
            throw new IOException(
                    "Archive input differs from the requested captured source SHA-256/size");
        NativeArchiveStore.requireScope(input, offset, length);
    }

    JSONObject json() throws Exception {
        return new JSONObject(raw);
    }

    String summary() {
        return findingKind == null
                ? "Explicit source-bound archive interval"
                : "Archive hypothesis from "
                        + findingKind
                        + " row #"
                        + findingIndex
                        + "; original finding extent is unchanged";
    }

    private static JSONObject base(String sha, long size, long offset, long length)
            throws Exception {
        return new JSONObject()
                .put("schema", "org.diemobile.archive-launch")
                .put("schemaVersion", 1)
                .put("sourceSha256", sha)
                .put("sourceSizeBytes", size)
                .put("scopeOffset", offset)
                .put("scopeSizeBytes", length)
                .put("recoveredExtentBytes", JSONObject.NULL)
                .put(
                        "meaning",
                        "Explicit parsing interval, not a recovered archive extent. A finding start"
                                + " can define a parsing tail while its original reported length"
                                + " remains null. No bytes are automatically carved from this"
                                + " interval.");
    }

    private static ArchiveLaunch encode(JSONObject value) throws Exception {
        return new ArchiveLaunch(encodeValue(value));
    }

    private static String encodeValue(JSONObject value) throws Exception {
        StringBuilder encoded = new StringBuilder();
        try (Writer output =
                new Writer() {
                    private void room(int count) throws IOException {
                        if (count > MAX_CHARACTERS - encoded.length())
                            throw new IOException(
                                    "Archive launch context exceeds 64 Ki-characters; use an"
                                        + " explicit interval in the archive browser");
                    }

                    @Override
                    public void write(char[] chars, int start, int count) throws IOException {
                        room(count);
                        encoded.append(chars, start, count);
                    }

                    @Override
                    public void write(String text, int start, int count) throws IOException {
                        room(count);
                        encoded.append(text, start, start + count);
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                }) {
            EvidenceJson.write(output, value, () -> false);
        }
        return encoded.toString();
    }

    private static long number(JSONObject value, String key) throws Exception {
        Object token = value.get(key);
        if (!(token instanceof Long) && !(token instanceof Integer))
            throw new IOException("Archive launch field is not an exact integer: " + key);
        return ((Number) token).longValue();
    }
}
