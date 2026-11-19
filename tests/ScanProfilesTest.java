// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Actual private-file transactions and lossless request-profile round trips. */
public final class ScanProfilesTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface Work {
        void run() throws Exception;
    }

    private static void rejected(Work work, String context) throws Exception {
        try {
            work.run();
            throw new AssertionError(context + " was accepted");
        } catch (IOException | IllegalArgumentException | org.json.JSONException expected) {
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        File directory = root.resolve("profiles").toFile();
        ScanProfileStore store = new ScanProfileStore(directory);
        ScanProfiles empty = store.load(() -> false);
        require(
                empty.profiles.isEmpty() && !directory.exists(),
                "Reading absent profiles mutated storage");
        ScanOptions options = new ScanOptions(16383, 64, " compiler ; packer ", " archive ");
        ScanProfiles first =
                store.save(
                        empty.sha256,
                        null,
                        "Expert 研究",
                        "All supported flag bits",
                        options,
                        () -> false);
        ScanProfiles.Profile saved = first.profiles.get(0);
        require(
                saved.options.sameAs(options)
                        && saved.id.length() == 36
                        && saved.sha256.length() == 64,
                "Saved settings lost identity or effective values");
        ScanProfiles second =
                store.save(
                        first.sha256,
                        null,
                        "Expert 研究",
                        "A separate profile with the same name",
                        new ScanOptions(0, 3, "", ""),
                        () -> false);
        require(
                second.profiles.size() == 2 && !second.profiles.get(1).id.equals(saved.id),
                "Duplicate display names collapsed distinct IDs");
        byte[] stable = Files.readAllBytes(directory.toPath().resolve("catalog.json"));
        rejected(
                () -> store.save(first.sha256, saved.id, "Stale edit", "", options, () -> false),
                "Stale revision");
        require(
                Arrays.equals(
                        stable, Files.readAllBytes(directory.toPath().resolve("catalog.json"))),
                "Stale writer overwrote a newer catalog");
        ScanProfileStore failure =
                new ScanProfileStore(
                        directory,
                        (temporary, destination) -> {
                            throw new IOException("Injected pre-publication failure");
                        });
        rejected(
                () -> failure.delete(second.sha256, saved.id, () -> false),
                "Failed atomic publication");
        require(
                Arrays.equals(
                        stable, Files.readAllBytes(directory.toPath().resolve("catalog.json"))),
                "Publication failure changed prior bytes");
        require(
                Arrays.equals(directory.list(), new String[] {"catalog.json"}),
                "Publication failure retained its stage");
        rejected(
                () -> store.save(second.sha256, null, "Cancelled", "", options, () -> true),
                "Cancelled save");
        require(
                Arrays.equals(
                        stable, Files.readAllBytes(directory.toPath().resolve("catalog.json"))),
                "Cancellation changed the catalog");

        String id = "12345678-1234-4234-8234-123456789abc";
        String source =
                "{\"schema\":\"org.diemobile.scan-profiles\",\"schemaVersion\":1e0,\"optionsContract\":\"org.diemobile.cdie-options/v1\","
                    + "\"unknownRoot\":{\"huge\":1e400,\"number\":123456789012345678901234567890},\"profiles\":{\""
                        + id
                        + "\":{\"name\":\"Imported 研究\",\"description\":\"Original metadata"
                        + " retained\",\"custom\":{\"surrogate\":\"\\uD800-\\uDFFF\",\"n\":-0.000e+999},\"options\":{\"flags\":8.192e3,\"minimumStringLength\":4.0,\"includeTags\":\""
                        + " compiler \",\"excludeTags\":\"\",\"future\":1e400}}}}";
        ScanProfiles imported =
                ScanProfiles.read(
                        new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),
                        () -> false);
        require(
                imported.find(id).options.flags == 8192
                        && imported.find(id).options.includeTags.equals("compiler"),
                "Exact integral controls or effective tag normalization differ");
        ScanProfiles replaced = store.replace(second.sha256, imported, () -> false);
        require(
                replaced.original.equals(source)
                        && Arrays.equals(
                                source.getBytes(StandardCharsets.UTF_8),
                                Files.readAllBytes(directory.toPath().resolve("catalog.json"))),
                "Explicit catalog replacement changed original imported bytes");
        ScanProfiles edited =
                store.save(
                        replaced.sha256,
                        id,
                        "Edited 研究",
                        "Changed known fields",
                        options,
                        () -> false);
        StringWriter exported = new StringWriter();
        edited.write(exported, () -> false);
        String result = exported.toString();
        require(
                result.contains("\"huge\":1e400")
                        && result.contains("123456789012345678901234567890")
                        && result.contains("\"future\":1e400")
                        && result.contains("-0.000e+999")
                        && result.contains("\\uD800-\\uDFFF"),
                "Editing known settings erased unknown fields, number tokens or UTF-16 units");
        require(
                store.load(() -> false).sha256.equals(edited.sha256)
                        && edited.find(id).options.sameAs(options),
                "Disk reload changed the published catalog/settings");
        ScanProfiles removed = store.delete(edited.sha256, id, () -> false);
        require(
                removed.profiles.isEmpty() && removed.original.contains("\"huge\":1e400"),
                "Delete lost unrelated root metadata");

        for (String invalid :
                new String[] {
                    source.replace("\"schemaVersion\":1e0", "\"schemaVersion\":2"),
                    source.replace("\"schemaVersion\":1e0", "\"schemaVersion\":\"1\""),
                    source.replace("8.192e3", "16384"),
                    source.replace("8.192e3", "8192.5"),
                    source.replace("8.192e3", "1e400"),
                    source.replace("4.0", "65"),
                    source.replace("8.192e3", "\"8192\""),
                    source.replace(id, "1-1-1-1-1"),
                    source.replace("\"flags\":8.192e3", "\"flags\":1,\"\\u0066lags\":8192"),
                    source.replace("\"name\":\"Imported 研究\"", "\"name\":false"),
                    source.replace("\"profiles\":{", "\"profiles\":[")
                }) {
            rejected(() -> ScanProfiles.parse(invalid, () -> false), "Malformed profile input");
        }
        rejected(
                () ->
                        ScanProfiles.read(
                                new ByteArrayInputStream(new byte[] {(byte) 0xc0, (byte) 0xaf}),
                                () -> false),
                "Invalid UTF-8");
        rejected(
                () ->
                        ScanProfiles.read(
                                new ByteArrayInputStream(new byte[ScanProfiles.MAX_BYTES + 1]),
                                () -> false),
                "Oversized profile input");
        rejected(() -> ScanProfiles.parse(source, () -> true), "Cancelled parse");
        StringBuilder many =
                new StringBuilder(
                        "{\"schema\":\"org.diemobile.scan-profiles\",\"schemaVersion\":1,\"optionsContract\":\"org.diemobile.cdie-options/v1\",\"profiles\":{");
        for (int i = 0; i < 257; i++) {
            if (i > 0) many.append(',');
            many.append(JSONObject.quote(new UUID(0, i).toString()))
                    .append(
                            ":{\"name\":\"Entry\",\"options\":{\"flags\":0,\"minimumStringLength\":4,\"includeTags\":\"\",\"excludeTags\":\"\"}}");
        }
        many.append("}}");
        rejected(() -> ScanProfiles.parse(many.toString(), () -> false), "Over-count catalog");
        String tooDeep = source.replace("1e400", "[".repeat(66) + "0" + "]".repeat(66));
        rejected(() -> ScanProfiles.parse(tooDeep, () -> false), "Over-depth unknown metadata");
        String hugeControl = source.replace("8.192e3", "1".repeat(129));
        rejected(
                () -> ScanProfiles.parse(hugeControl, () -> false),
                "Oversized integral-control token");
        require(
                store.load(() -> false).sha256.equals(removed.sha256),
                "Rejected imports changed persisted profiles");
        System.out.println(
                "Scan profiles passed: all flag bits, exact integral controls, immutable"
                    + " IDs/options, duplicate names, source SHA/unknown numeric/UTF-16 metadata,"
                    + " real atomic publication, failed/stale/cancelled writes and strict bounded"
                    + " imports.");
    }
}
