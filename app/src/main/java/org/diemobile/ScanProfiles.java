// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Immutable named request settings, retaining the complete imported catalog. */
final class ScanProfiles {
    static final int MAX_BYTES = 4 * 1024 * 1024, MAX_PROFILES = 256;
    static final String SCHEMA = "org.diemobile.scan-profiles";
    static final String OPTIONS_CONTRACT = "org.diemobile.cdie-options/v1";

    static final class Profile {
        final String id, name, description, original, sha256;
        final ScanOptions options;

        private Profile(CatalogJson.Slice slice) throws Exception {
            id = slice.key;
            try {
                if (!UUID.fromString(id).toString().equals(id))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) {
                throw new IOException("Profile ID must be a canonical lowercase UUID", error);
            }
            if (!(slice.decoded instanceof JSONObject) || slice.raw.length() > 256 * 1024)
                throw new IOException("Profile must be an object within 256 Ki-characters");
            JSONObject object = (JSONObject) slice.decoded;
            name = text(object, "name", 256, false);
            description = object.has("description") ? text(object, "description", 16384, true) : "";
            Object settings = object.get("options");
            if (!(settings instanceof JSONObject))
                throw new IOException("Profile options must be an object");
            JSONObject values = (JSONObject) settings;
            options =
                    new ScanOptions(
                            (int) CatalogJson.integer(values.get("flags"), 0, 16383),
                            (int) CatalogJson.integer(values.get("minimumStringLength"), 3, 64),
                            text(values, "includeTags", 4096, true),
                            text(values, "excludeTags", 4096, true));
            original = slice.raw;
            sha256 = digest(utf8(original));
        }

        private JSONObject copy(BooleanSupplier stop) throws Exception {
            CatalogJson copy =
                    new CatalogJson(
                            "{\"profiles\":{" + JSONObject.quote(id) + ":" + original + "}}",
                            stop,
                            "profiles",
                            1,
                            64,
                            "Profile");
            return (JSONObject) copy.records.get(0).decoded;
        }
    }

    final String original, sha256;
    final List<Profile> profiles;
    private final List<CatalogJson.Slice> metadata;
    private final byte[] bytes;

    private ScanProfiles(String original, BooleanSupplier stop) throws Exception {
        if (original.length() > MAX_BYTES) throw new IOException("Profile catalog exceeds 4 MiB");
        this.original = original;
        bytes = utf8(original);
        if (bytes.length > MAX_BYTES) throw new IOException("Profile catalog exceeds 4 MiB");
        sha256 = digest(bytes);
        CatalogJson parsed =
                new CatalogJson(original, stop, "profiles", MAX_PROFILES, 64, "Profile");
        metadata = Collections.unmodifiableList(new ArrayList<>(parsed.metadata));
        JSONObject fields = new JSONObject();
        for (CatalogJson.Slice field : metadata) fields.put(field.key, field.decoded);
        if (!text(fields, "schema", 128, false).equals(SCHEMA)
                || CatalogJson.integer(fields.get("schemaVersion"), 1, 1) != 1
                || !text(fields, "optionsContract", 128, false).equals(OPTIONS_CONTRACT))
            throw new IOException("Unsupported profile schema or desktop option contract");
        List<Profile> records = new ArrayList<>();
        for (CatalogJson.Slice slice : parsed.records) {
            EvidenceRows.check(stop);
            records.add(new Profile(slice));
        }
        profiles = Collections.unmodifiableList(records);
    }

    static ScanProfiles parse(String original, BooleanSupplier stop) throws Exception {
        return new ScanProfiles(original, stop);
    }

    static ScanProfiles read(InputStream input, BooleanSupplier stop) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[65536];
        int count;
        while ((count = input.read(block)) >= 0) {
            EvidenceRows.check(stop);
            if (count > MAX_BYTES - bytes.size())
                throw new IOException("Profile catalog exceeds 4 MiB");
            bytes.write(block, 0, count);
        }
        EvidenceRows.check(stop);
        String text =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray()))
                        .toString();
        return parse(text, stop);
    }

    static ScanProfiles empty() throws Exception {
        return parse(
                "{\"schema\":\""
                        + SCHEMA
                        + "\",\"schemaVersion\":1,\"optionsContract\":\""
                        + OPTIONS_CONTRACT
                        + "\",\"profiles\":{}}",
                () -> false);
    }

    Profile find(String id) throws IOException {
        for (Profile entry : profiles) if (entry.id.equals(id)) return entry;
        throw new IOException("Profile is absent from this captured catalog");
    }

    ScanProfiles save(
            String id, String name, String description, ScanOptions options, BooleanSupplier stop)
            throws Exception {
        boolean adding = id == null;
        if (adding && profiles.size() >= MAX_PROFILES)
            throw new IOException("Profile catalog already has 256 profiles");
        String identity = adding ? UUID.randomUUID().toString() : id;
        JSONObject object = adding ? new JSONObject() : find(identity).copy(stop);
        object.put("name", name).put("description", description);
        JSONObject settings = object.optJSONObject("options");
        if (settings == null) settings = new JSONObject();
        settings.put("flags", options.flags)
                .put("minimumStringLength", options.minimumStringLength)
                .put("includeTags", options.includeTags)
                .put("excludeTags", options.excludeTags);
        object.put("options", settings);
        String now = java.time.Instant.now().toString();
        if (adding) object.put("createdAt", now);
        object.put("updatedAt", now);
        StringWriter encoded = new StringWriter();
        EvidenceJson.write(encoded, object, stop);
        LinkedHashMap<String, String> entries = entries();
        entries.put(identity, encoded.toString());
        return compose(entries, stop);
    }

    ScanProfiles delete(String id, BooleanSupplier stop) throws Exception {
        find(id);
        LinkedHashMap<String, String> entries = entries();
        entries.remove(id);
        return compose(entries, stop);
    }

    void write(Writer output, BooleanSupplier stop) throws Exception {
        for (int at = 0; at < original.length(); at += 65536) {
            EvidenceRows.check(stop);
            output.write(original, at, Math.min(65536, original.length() - at));
        }
        EvidenceRows.check(stop);
    }

    byte[] bytes() {
        return bytes.clone();
    }

    private LinkedHashMap<String, String> entries() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Profile entry : profiles) result.put(entry.id, entry.original);
        return result;
    }

    private ScanProfiles compose(LinkedHashMap<String, String> entries, BooleanSupplier stop)
            throws Exception {
        StringWriter output = new StringWriter();
        output.write('{');
        for (CatalogJson.Slice field : metadata) {
            EvidenceRows.check(stop);
            EvidenceJson.quoted(output, field.key, stop);
            output.write(':');
            output.write(field.raw);
            output.write(',');
        }
        output.write("\"profiles\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            EvidenceRows.check(stop);
            if (!first) output.write(',');
            first = false;
            EvidenceJson.quoted(output, entry.getKey(), stop);
            output.write(':');
            output.write(entry.getValue());
        }
        output.write("}}");
        return parse(output.toString(), stop);
    }

    private static String text(JSONObject object, String key, int maximum, boolean allowEmpty)
            throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String))
            throw new IOException("Profile field " + key + " must be text");
        String text = (String) value;
        if (text.length() > maximum || !allowEmpty && text.trim().isEmpty())
            throw new IOException("Profile field " + key + " is empty or exceeds its length limit");
        return text;
    }

    private static byte[] utf8(String text) throws Exception {
        ByteBuffer encoded =
                StandardCharsets.UTF_8
                        .newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static String digest(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : hash) {
            hex.append("0123456789abcdef".charAt((value >>> 4) & 15));
            hex.append("0123456789abcdef".charAt(value & 15));
        }
        return hex.toString();
    }
}
