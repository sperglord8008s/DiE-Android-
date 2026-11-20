// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Lossless source-attributed community claims, separate from observations of an application. */
final class PackageKnowledge {
    static final String REVISION = "9fcad87bf78c52c4270504bc8880139d95a27a29";
    static final String PINNED_SHA256 =
            "69e3e7f33a64f165217a58bb426aada13385f5427a5123e34d31041416f593a6";
    static final String REPOSITORY =
            "https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation";
    static final String SOURCE =
            REPOSITORY + "/blob/" + REVISION + "/resources/assets/uad_lists.json";
    static final int MAX_BYTES = 8 * 1024 * 1024, MAX_PACKAGES = 10000;
    static final String MEANING =
            "Community-authored package descriptions and declared relationships. Exact package ID"
                + " association alone does not identify a vendor, verify this installed version's"
                + " behavior, establish dependencies on this device or supply a tested removal"
                + " recommendation.";
    final byte[] bytes;
    final String sha256;
    final List<Entry> entries;
    final List<Edge> edges;
    final Map<String, List<Entry>> byId;
    private final Map<String, List<Edge>> incoming = new HashMap<>();
    private final Set<List<String>> claims = new HashSet<>();

    static final class Entry {
        final int index;
        final String id, description, list, removal;
        final CatalogJson.Slice slice;
        final JSONObject value;
        final List<String> diagnostics = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();

        Entry(int index, CatalogJson.Slice slice) throws Exception {
            this.index = index;
            this.slice = slice;
            id = slice.key;
            value = slice.decoded instanceof JSONObject ? (JSONObject) slice.decoded : null;
            if (value == null)
                diagnostics.add(
                        "Original package record is not an object; its complete original value is"
                                + " retained.");
            description = text("description");
            list = text("list");
            removal = text("removal");
            if (list != null
                    && !Arrays.asList("Aosp", "Oem", "Google", "Carrier", "Misc").contains(list))
                diagnostics.add("Unrecognized upstream list category: " + list);
            if (removal != null
                    && !Arrays.asList("Recommended", "Advanced", "Expert", "Unsafe")
                            .contains(removal))
                diagnostics.add("Unrecognized upstream removal category: " + removal);
        }

        private String text(String field) throws Exception {
            if (value == null) return null;
            Object result = value.opt(field);
            if (result instanceof String) return (String) result;
            diagnostics.add(
                    field
                            + (result == null
                                    ? " is absent"
                                    : result == JSONObject.NULL
                                            ? " is null"
                                            : " has a non-text type")
                            + "; no value inferred.");
            return null;
        }
    }

    static final class Edge {
        final int index, occurrence;
        final Entry claimant;
        final String field, target;
        final Object original;

        Edge(int index, Entry claimant, String field, int occurrence, Object original) {
            this.index = index;
            this.claimant = claimant;
            this.field = field;
            this.occurrence = occurrence;
            this.original = original;
            target = original instanceof String ? (String) original : null;
        }

        String from() {
            return field.equals("dependencies") ? claimant.id : target;
        }

        String to() {
            return field.equals("dependencies") ? target : claimant.id;
        }
    }

    private PackageKnowledge(byte[] bytes, BooleanSupplier stop) throws Exception {
        this.bytes = bytes;
        sha256 = Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String text =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
        String prefix = "{\"packages\":";
        CatalogJson parsed =
                new CatalogJson(
                        prefix + text + "}",
                        stop,
                        "packages",
                        MAX_PACKAGES,
                        1024,
                        "Package knowledge",
                        true);
        List<Entry> found = new ArrayList<>();
        Map<String, List<Entry>> ids = new LinkedHashMap<>();
        for (CatalogJson.Slice slice : parsed.records) {
            EvidenceRows.check(stop);
            CatalogJson.Slice original =
                    new CatalogJson.Slice(
                            slice.key,
                            text,
                            slice.start - prefix.length(),
                            slice.end - prefix.length(),
                            slice.decoded);
            Entry entry = new Entry(found.size(), original);
            found.add(entry);
            ids.computeIfAbsent(entry.id, key -> new ArrayList<>()).add(entry);
        }
        entries = Collections.unmodifiableList(found);
        for (Map.Entry<String, List<Entry>> group : ids.entrySet()) {
            if (group.getValue().size() > 1)
                for (Entry entry : group.getValue())
                    entry.diagnostics.add(
                            "Duplicate exact package ID: "
                                    + group.getValue().size()
                                    + " original source records; no preferred record selected.");
            group.setValue(Collections.unmodifiableList(group.getValue()));
        }
        byId = Collections.unmodifiableMap(ids);
        List<Edge> links = new ArrayList<>();
        for (Entry entry : entries) {
            for (String field : new String[] {"dependencies", "neededBy", "labels"}) {
                EvidenceRows.check(stop);
                Object value = entry.value == null ? null : entry.value.opt(field);
                if (!(value instanceof JSONArray)) {
                    entry.diagnostics.add(field + " is absent or not an array; coverage unknown.");
                    continue;
                }
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    EvidenceRows.check(stop);
                    Object item = array.get(i);
                    if (field.equals("labels")) {
                        if (!(item instanceof String))
                            entry.diagnostics.add(
                                    "labels[" + i + "] is not text; original retained.");
                        continue;
                    }
                    if (links.size() >= 50000)
                        throw new IOException(
                                "Package relationship budget: 50,000 declared occurrences");
                    Edge edge = new Edge(links.size(), entry, field, i, item);
                    links.add(edge);
                    entry.edges.add(edge);
                    if (edge.target == null || edge.target.isEmpty())
                        entry.diagnostics.add(
                                field
                                        + "["
                                        + i
                                        + "] is not a nonempty package ID; unresolved original"
                                        + " retained.");
                    else {
                        incoming.computeIfAbsent(edge.target, key -> new ArrayList<>()).add(edge);
                        claims.add(Arrays.asList(entry.id, field, edge.target));
                        if (!byId.containsKey(edge.target))
                            entry.diagnostics.add(
                                    field
                                            + "["
                                            + i
                                            + "] references an ID absent from this catalog: "
                                            + edge.target);
                    }
                }
            }
        }
        edges = Collections.unmodifiableList(links);
    }

    static PackageKnowledge read(InputStream source, BooleanSupplier stop) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[32768];
        int count;
        while ((count = source.read(block)) >= 0) {
            EvidenceRows.check(stop);
            if (count > MAX_BYTES - bytes.size())
                throw new IOException("Package knowledge exceeds 8 MiB");
            bytes.write(block, 0, count);
        }
        EvidenceRows.check(stop);
        return new PackageKnowledge(bytes.toByteArray(), stop);
    }

    JSONObject provenance() throws Exception {
        return new JSONObject()
                .put("schema", "org.diemobile.package-knowledge")
                .put("schemaVersion", 1)
                .put(
                        "inputKind",
                        "offline community knowledge catalog; no device behavior observation")
                .put("snapshotSha256", sha256)
                .put("snapshotSizeBytes", bytes.length)
                .put("matchesPinnedUadBytes", sha256.equals(PINNED_SHA256))
                .put("referenceRevision", REVISION)
                .put("referenceSource", SOURCE)
                .put("license", "GPL-3.0; bundled original UAD-LICENSE.txt")
                .put("packageRecords", entries.size())
                .put("uniquePackageIds", byId.size())
                .put(
                        "duplicatePackageIds",
                        byId.values().stream().filter(group -> group.size() > 1).count())
                .put("declaredRelationshipOccurrences", edges.size())
                .put(
                        "recordsWithDiagnostics",
                        entries.stream().filter(e -> !e.diagnostics.isEmpty()).count())
                .put(
                        "matching",
                        "Exact case-sensitive package IDs. No aliases, vendor/signature/version"
                            + " authentication, automatic removal or runtime behavior inference.")
                .put("meaning", MEANING);
    }

    private JSONObject identity(Entry entry) throws Exception {
        return new JSONObject()
                .put("packageId", entry.id)
                .put("sourceRecordIndex", entry.index)
                .put("exactIdRecordCount", byId.get(entry.id).size())
                .put("sourceValueStartUtf16", entry.slice.start)
                .put("sourceValueEndUtf16", entry.slice.end)
                .put(
                        "sourceCoordinateMeaning",
                        "UTF-16 units in the exact decoded source JSON; not file byte offsets")
                .put("diagnostics", new JSONArray(entry.diagnostics));
    }

    EvidenceRows catalog(String category, String removal, boolean diagnostics) {
        List<Entry> selected = new ArrayList<>();
        for (Entry entry : entries)
            if ((category.isEmpty() || category.equals(entry.list))
                    && (removal.isEmpty() || removal.equals(entry.removal))
                    && (!diagnostics || !entry.diagnostics.isEmpty())) selected.add(entry);
        return new EvidenceRows(
                "package-knowledge-catalog",
                "Community package descriptions / " + selected.size() + " records") {
            int size() {
                return selected.size();
            }

            Row row(int index, BooleanSupplier stop) throws Exception {
                EvidenceRows.check(stop);
                Entry entry = selected.get(index);
                JSONObject fields =
                        identity(entry)
                                .put(
                                        "communityList",
                                        entry.list == null ? JSONObject.NULL : entry.list)
                                .put(
                                        "communityRemovalCategory",
                                        entry.removal == null ? JSONObject.NULL : entry.removal)
                                .put("descriptionPreview", preview(entry.description, 8192))
                                .put(
                                        "descriptionUtf16Units",
                                        entry.description == null
                                                ? JSONObject.NULL
                                                : entry.description.length())
                                .put(
                                        "descriptionPreviewTruncated",
                                        entry.description != null
                                                && entry.description.length() > 8192)
                                .put("declaredRelationshipOccurrences", entry.edges.size())
                                .put("meaning", MEANING);
                return new Row(
                        index,
                        preview(entry.id, 256),
                        preview(entry.description, 512),
                        null,
                        null,
                        fields);
            }

            JSONObject metadata() throws Exception {
                return provenance()
                        .put("listFilter", category)
                        .put("removalFilter", removal)
                        .put("diagnosticsOnly", diagnostics);
            }

            @Override
            boolean matches(Row row, String query, BooleanSupplier stop) throws Exception {
                Entry entry = selected.get(row.index);
                return entry.id.toLowerCase(Locale.ROOT).contains(query)
                        || entry.slice.raw.toLowerCase(Locale.ROOT).contains(query)
                        || containsDecoded(entry.slice.decoded, query, 0, new int[] {0}, stop)
                        || super.matches(row, query, stop);
            }

            @Override
            void writeSelectedRow(Writer out, Row row, BooleanSupplier stop) throws Exception {
                Entry entry = selected.get(row.index);
                out.write(row.encoded, 0, row.encoded.length() - 1);
                out.write(",\"fullOriginalRecord\":");
                raw(out, entry.slice.raw, stop);
                out.write('}');
            }
        };
    }

    Entry record(String id, int index) throws IOException {
        if (index >= 0) {
            if (index >= entries.size() || !entries.get(index).id.equals(id))
                throw new IOException("Original record index belongs to a different package ID");
            return entries.get(index);
        }
        List<Entry> matches = byId.get(id);
        if (matches == null) throw new IOException("No exact package ID match");
        if (matches.size() != 1)
            throw new IOException("Ambiguous package ID: select an original source record index");
        return matches.get(0);
    }

    EvidenceRows card(String id) throws Exception {
        return card(id, -1);
    }

    EvidenceRows card(String id, int recordIndex) throws Exception {
        List<Entry> matched =
                recordIndex < 0
                        ? byId.getOrDefault(id, Collections.emptyList())
                        : Collections.singletonList(record(id, recordIndex));
        List<Entry> owners = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        for (Entry entry : matched) {
            List<String> keys = new ArrayList<>();
            if (entry.value != null) {
                for (String key :
                        new String[] {
                            "description", "list", "removal", "dependencies", "neededBy", "labels"
                        }) if (entry.value.has(key)) keys.add(key);
                TreeSet<String> rest = new TreeSet<>();
                entry.value.keys().forEachRemaining(rest::add);
                rest.removeAll(keys);
                keys.addAll(rest);
            }
            if (keys.isEmpty()) {
                owners.add(entry);
                fields.add(null);
            } else
                for (String key : keys) {
                    owners.add(entry);
                    fields.add(key);
                }
        }
        return new EvidenceRows(
                "package-knowledge-card",
                "What does "
                        + preview(id, 256)
                        + " do? / "
                        + matched.size()
                        + " original community record(s)") {
            int size() {
                return owners.isEmpty() ? 1 : owners.size();
            }

            Row row(int index, BooleanSupplier stop) throws Exception {
                EvidenceRows.check(stop);
                if (owners.isEmpty())
                    return new Row(
                            0,
                            "No exact package ID match",
                            "This snapshot contains no record for "
                                    + preview(id, 512)
                                    + ". Absence supplies no behavior or removal conclusion.",
                            null,
                            null,
                            new JSONObject()
                                    .put("requestedPackageId", id)
                                    .put("exactMatch", false));
                Entry entry = owners.get(index);
                String field = fields.get(index);
                if (field == null)
                    return new Row(
                            index,
                            "Unsupported/empty original record #" + entry.index,
                            "Complete raw record remains available",
                            null,
                            null,
                            identity(entry));
                Object value = entry.value.get(field);
                StringWriter json = new StringWriter();
                EvidenceJson.write(json, value, stop);
                String display = value instanceof String ? (String) value : json.toString();
                String title =
                        field.equals("description")
                                ? "What does it do? / upstream community description"
                                : field.equals("removal")
                                        ? "Upstream removal category / unverified on this device"
                                        : field;
                if (byId.get(id).size() > 1) title += " / source record #" + entry.index;
                return new Row(
                        index,
                        title,
                        preview(display, 512),
                        null,
                        null,
                        identity(entry)
                                .put("sourceField", field)
                                .put("fieldMeaning", fieldMeaning(field))
                                .put("valuePreview", preview(display, 8192))
                                .put("valueUtf16Units", display.length())
                                .put("previewTruncated", display.length() > 8192)
                                .put("valueType", type(value))
                                .put("meaning", MEANING));
            }

            JSONObject metadata() throws Exception {
                return provenance()
                        .put("requestedPackageId", id)
                        .put("exactMatch", !matched.isEmpty())
                        .put(
                                "exactIdRecordCount",
                                byId.getOrDefault(id, Collections.emptyList()).size())
                        .put("selectedSourceRecordIndex", recordIndex)
                        .put(
                                "ambiguity",
                                "Every selected source occurrence is retained; no preferred"
                                        + " description or category inferred.");
            }

            @Override
            boolean matches(Row row, String query, BooleanSupplier stop) throws Exception {
                if (owners.isEmpty()) return super.matches(row, query, stop);
                Entry entry = owners.get(row.index);
                String field = fields.get(row.index);
                return field != null
                                && (field.toLowerCase(Locale.ROOT).contains(query)
                                        || containsDecoded(
                                                entry.value.get(field),
                                                query,
                                                0,
                                                new int[] {0},
                                                stop))
                        || containsDecoded(entry.slice.decoded, query, 0, new int[] {0}, stop)
                                && field == null
                        || super.matches(row, query, stop);
            }

            @Override
            void writeSelectedRow(Writer out, Row row, BooleanSupplier stop) throws Exception {
                out.write(row.encoded, 0, row.encoded.length() - 1);
                if (!owners.isEmpty() && fields.get(row.index) != null) {
                    out.write(",\"fullOriginalFieldValue\":");
                    EvidenceJson.write(
                            out, owners.get(row.index).value.get(fields.get(row.index)), stop);
                }
                out.write('}');
            }
        };
    }

    EvidenceRows relationships(String id) {
        List<Edge> selected = new ArrayList<>();
        for (Entry entry : byId.getOrDefault(id, Collections.emptyList()))
            selected.addAll(entry.edges);
        for (Edge edge : incoming.getOrDefault(id, Collections.emptyList()))
            if (!edge.claimant.id.equals(id)) selected.add(edge);
        return new EvidenceRows(
                "package-knowledge-relationships",
                "Original and reverse-indexed relationship claims for " + preview(id, 256)) {
            int size() {
                return selected.size();
            }

            Row row(int index, BooleanSupplier stop) throws Exception {
                EvidenceRows.check(stop);
                Edge edge = selected.get(index);
                boolean reciprocal =
                        edge.target != null
                                && claims.contains(
                                        Arrays.asList(
                                                edge.target,
                                                edge.field.equals("dependencies")
                                                        ? "neededBy"
                                                        : "dependencies",
                                                edge.claimant.id));
                JSONObject fields =
                        identity(edge.claimant)
                                .put("claimIndex", edge.index)
                                .put("declaredField", edge.field)
                                .put("arrayOccurrence", edge.occurrence)
                                .put(
                                        "targetPackageId",
                                        edge.target == null ? JSONObject.NULL : edge.target)
                                .put("targetRecordPresent", byId.containsKey(edge.target))
                                .put(
                                        "targetRecordCount",
                                        byId.getOrDefault(edge.target, Collections.emptyList())
                                                .size())
                                .put("reciprocalClaimRecorded", reciprocal)
                                .put(
                                        "dependencyFrom",
                                        edge.from() == null ? JSONObject.NULL : edge.from())
                                .put(
                                        "dependencyTo",
                                        edge.to() == null ? JSONObject.NULL : edge.to())
                                .put(
                                        "meaning",
                                        "A declared upstream relationship occurrence. Reverse"
                                            + " indexing is computed from exact IDs; missing"
                                            + " reciprocity and absent records remain unknown, not"
                                            + " verified device dependencies.");
                return new Row(
                        index,
                        preview(edge.claimant.id, 256)
                                + " / "
                                + edge.field
                                + "["
                                + edge.occurrence
                                + "]",
                        edge.target == null
                                ? "Non-text unresolved target; original retained"
                                : preview(edge.target, 512)
                                        + (byId.containsKey(edge.target)
                                                ? " / catalog record"
                                                : " / no catalog record"),
                        null,
                        null,
                        fields);
            }

            JSONObject metadata() throws Exception {
                return provenance()
                        .put("requestedPackageId", id)
                        .put(
                                "scope",
                                "Every own declaration and every exact incoming reference;"
                                        + " duplicates retained");
            }

            @Override
            void writeSelectedRow(Writer out, Row row, BooleanSupplier stop) throws Exception {
                out.write(row.encoded, 0, row.encoded.length() - 1);
                out.write(",\"originalTargetValue\":");
                EvidenceJson.write(out, selected.get(row.index).original, stop);
                out.write('}');
            }
        };
    }

    static void raw(Writer out, String raw, BooleanSupplier stop) throws Exception {
        for (int at = 0; at < raw.length(); at += 4096) {
            EvidenceRows.check(stop);
            out.write(raw, at, Math.min(4096, raw.length() - at));
        }
    }

    static String preview(String text, int maximum) {
        if (text == null) return "Not supplied as text";
        return text.length() <= maximum
                ? text
                : text.substring(0, maximum) + "… [preview; full value/export available]";
    }

    private static String fieldMeaning(String field) {
        switch (field) {
            case "description":
                return "Original community-authored practical description, conditionals and"
                           + " references; not an observation of this installed version's runtime"
                           + " behavior.";
            case "list":
                return "Upstream grouping: Aosp refers to Android Open Source Project, Oem to a"
                           + " manufacturer category; Google, Carrier and Misc are further"
                           + " community groups. A package ID does not authenticate origin or"
                           + " vendor.";
            case "removal":
                return "Original upstream guidance label (Recommended, Advanced, Expert or Unsafe)."
                           + " This viewer does not test removal, determine device-specific safety"
                           + " or change installed packages.";
            case "dependencies":
                return "Packages this source record claims the package requires. Exact directed"
                           + " declarations, incomplete coverage and all duplicate occurrences are"
                           + " retained.";
            case "neededBy":
                return "Packages this source record claims require this package. This is a"
                           + " separately authored declaration; the other record may not contain a"
                           + " reciprocal claim.";
            case "labels":
                return "Original upstream labels retained without inventing a local"
                           + " interpretation.";
            default:
                return "Original source field retained; no local semantics have been assigned.";
        }
    }

    private static String type(Object value) {
        return value == JSONObject.NULL
                ? "null"
                : value instanceof JSONObject
                        ? "object"
                        : value instanceof JSONArray
                                ? "array"
                                : value instanceof String
                                        ? "string"
                                        : value instanceof Boolean
                                                ? "boolean"
                                                : value instanceof EvidenceJson.RawNumber
                                                        ? "number"
                                                        : "unknown";
    }
}
