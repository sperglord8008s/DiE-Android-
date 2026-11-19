// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Package-set declarations from the existing Google/wire model, never PackageManager guesses. */
final class PackageManifest {
    static final String ANDROID = "http://schemas.android.com/apk/res/android";
    static final int MAX_ATTRIBUTES = 50000, MAX_USES_SPLITS = 256;
    static final int MAX_NODES = 4096, MAX_COPIED_CHARACTERS = 16 * 1024 * 1024;
    static final int MAX_ID_ASSOCIATIONS = 100000;
    static final String REFERENCE_SHA =
            "72f6bb9037f2f3f62d137647a9770ec3644ef32015fe0949a53d761af8fd10d5";
    final Map<String, Scalar> fields = new LinkedHashMap<>();
    final List<Scalar> usesSplits = new ArrayList<>();
    final JSONArray nodes = new JSONArray(), diagnostics = new JSONArray();
    final JSONObject source;
    private final Map<Integer, List<JSONObject>> attributes = new HashMap<>();
    private final Map<String, List<Long>> resourceIds = new HashMap<>();
    private final BooleanSupplier stop;
    private long copiedCharacters;
    private int idAssociations;

    static final class Scalar {
        final String key, status, kind;
        final Object value;
        final JSONArray original;
        final boolean defaultApplied;

        Scalar(
                String key,
                String kind,
                String status,
                Object value,
                JSONArray original,
                boolean defaultApplied) {
            this.key = key;
            this.kind = kind;
            this.status = status;
            this.value = value;
            this.original = original;
            this.defaultApplied = defaultApplied;
        }

        boolean known() {
            return status.equals("literal") || status.equals("absent-default");
        }

        String text() {
            return known() && value instanceof String ? (String) value : null;
        }

        Long unsigned() {
            return known() && value instanceof Long ? (Long) value : null;
        }

        Boolean flag() {
            return known() && value instanceof Boolean ? (Boolean) value : null;
        }

        JSONObject json() throws Exception {
            return new JSONObject()
                    .put("key", key)
                    .put("kind", kind)
                    .put("status", status)
                    .put("value", value == null ? JSONObject.NULL : value)
                    .put("defaultApplied", defaultApplied)
                    .put("originalAttributeRecords", original);
        }
    }

    PackageManifest(AndroidResourceDocument document, BooleanSupplier stop) throws Exception {
        this.stop = stop;
        EvidenceRows.check(stop);
        if (!document.header.getString("kind").equals("binary-xml"))
            throw new IOException("APK manifest is not binary XML");
        source = document.provenance();
        JSONArray rawAttributes = document.tables.getJSONArray("attributes"),
                events = document.tables.getJSONArray("xml-events"),
                maps = document.tables.getJSONArray("resource-map");
        if (rawAttributes.length() > MAX_ATTRIBUTES)
            throw new IOException("Package manifest attribute budget exceeded");
        for (int i = 0; i < rawAttributes.length(); i++) {
            EvidenceRows.check(stop);
            JSONObject attribute = rawAttributes.getJSONObject(i);
            attributes
                    .computeIfAbsent(
                            attribute.getInt("elementChunkIndex"), key -> new ArrayList<>())
                    .add(attribute);
        }
        for (int i = 0; i < maps.length(); i++) {
            EvidenceRows.check(stop);
            JSONObject map = maps.getJSONObject(i);
            resourceIds
                    .computeIfAbsent(
                            map.getInt("poolChunkIndex") + ":" + map.getInt("stringIndex"),
                            key -> new ArrayList<>())
                    .add(map.getLong("resourceId"));
        }
        List<JSONObject> roots = new ArrayList<>();
        for (int i = 0; i < events.length(); i++) {
            EvidenceRows.check(stop);
            JSONObject event = events.getJSONObject(i);
            if (event.has("structuralDiagnostic"))
                diagnostic(
                        "xml-structure",
                        event.getString("structuralDiagnostic"),
                        event.getInt("chunkIndex"));
            if (event.getInt("chunkType") == 0x102 && event.isNull("parentElementChunkIndex"))
                roots.add(event);
        }
        if (roots.size() != 1
                || !roots.get(0).optString("elementName").equals("manifest")
                || !roots.get(0).optString("namespace").isEmpty())
            throw new IOException(
                    "Package manifest needs one unambiguous top-level <manifest> element");
        JSONObject root = roots.get(0);
        int rootIndex = root.getInt("chunkIndex");
        node(root);
        field("package", rootIndex, "", "package", null, "string", null);
        field("split", rootIndex, "", "split", null, "string", null);
        field("configForSplit", rootIndex, "", "configForSplit", null, "string", null);
        field("versionCode", rootIndex, ANDROID, "versionCode", 0x0101021bL, "uint32", 0L);
        field(
                "versionCodeMajor",
                rootIndex,
                ANDROID,
                "versionCodeMajor",
                0x01010576L,
                "uint32",
                0L);
        field("revisionCode", rootIndex, ANDROID, "revisionCode", 0x010104d5L, "uint32", 0L);
        field("versionName", rootIndex, ANDROID, "versionName", 0x0101021cL, "string", null);
        field(
                "isFeatureSplit",
                rootIndex,
                ANDROID,
                "isFeatureSplit",
                0x0101055bL,
                "boolean",
                false);
        field(
                "isSplitRequired",
                rootIndex,
                ANDROID,
                "isSplitRequired",
                0x01010591L,
                "boolean",
                false);
        field(
                "isolatedSplits",
                rootIndex,
                ANDROID,
                "isolatedSplits",
                0x0101054bL,
                "boolean",
                false);
        field(
                "requiredSplitTypes",
                rootIndex,
                ANDROID,
                "requiredSplitTypes",
                0x0101064eL,
                "string",
                null);
        field("splitTypes", rootIndex, ANDROID, "splitTypes", 0x0101064fL, "string", null);
        int sdkNodes = 0, applicationNodes = 0;
        for (int i = 0; i < events.length(); i++) {
            EvidenceRows.check(stop);
            JSONObject event = events.getJSONObject(i);
            if (event.getInt("chunkType") != 0x102
                    || event.isNull("parentElementChunkIndex")
                    || event.getInt("parentElementChunkIndex") != rootIndex
                    || !event.optString("namespace").isEmpty()) continue;
            String element = event.getString("elementName");
            int chunk = event.getInt("chunkIndex");
            if (element.equals("uses-split")) {
                if (usesSplits.size() >= MAX_USES_SPLITS)
                    throw new IOException("Package manifest exceeds 256 declared uses-split nodes");
                node(event);
                usesSplits.add(
                        scalar(
                                "uses-split/" + usesSplits.size(),
                                chunk,
                                ANDROID,
                                "name",
                                0x01010003L,
                                "string",
                                null));
            } else if (element.equals("uses-sdk")) {
                node(event);
                sdkNodes++;
                if (sdkNodes == 1) {
                    field(
                            "minSdkVersion",
                            chunk,
                            ANDROID,
                            "minSdkVersion",
                            0x0101020cL,
                            "sdk-literal",
                            null);
                    field(
                            "targetSdkVersion",
                            chunk,
                            ANDROID,
                            "targetSdkVersion",
                            0x01010270L,
                            "sdk-literal",
                            null);
                }
            } else if (element.equals("application")) {
                node(event);
                applicationNodes++;
                if (applicationNodes == 1)
                    field(
                            "applicationHasCode",
                            chunk,
                            ANDROID,
                            "hasCode",
                            0x0101000cL,
                            "boolean",
                            null);
            }
        }
        if (usesSplits.size() > 1)
            diagnostic(
                    "repeated-uses-split",
                    "Every node is retained; pinned AOSP lite parsing considers only the first",
                    rootIndex);
        if (sdkNodes > 1) {
            invalidate("minSdkVersion");
            invalidate("targetSdkVersion");
            diagnostic(
                    "repeated-uses-sdk",
                    "Multiple direct SDK nodes; no effective SDK value selected",
                    rootIndex);
        }
        if (applicationNodes > 1) {
            invalidate("applicationHasCode");
            diagnostic(
                    "repeated-application",
                    "Multiple direct application nodes; no effective flag selected",
                    rootIndex);
        }
        for (Scalar scalar : fields.values())
            if (!scalar.known() && !scalar.status.equals("absent"))
                diagnostic("unresolved-field", scalar.key + ": " + scalar.status, rootIndex);
        for (Scalar scalar : usesSplits)
            if (!scalar.known() || scalar.text() == null || scalar.text().isEmpty())
                diagnostic("unresolved-uses-split", scalar.key + ": " + scalar.status, rootIndex);
        if (packageName() == null || packageName().isEmpty())
            diagnostic(
                    "missing-package",
                    "One nonempty literal package declaration is required for package-name"
                            + " grouping",
                    rootIndex);
    }

    private void invalidate(String key) {
        Scalar previous = fields.get(key);
        if (previous != null)
            fields.put(
                    key,
                    new Scalar(
                            key,
                            previous.kind,
                            "ambiguous-elements",
                            null,
                            previous.original,
                            false));
    }

    private void field(
            String key,
            int chunk,
            String namespace,
            String name,
            Long id,
            String kind,
            Object fallback)
            throws Exception {
        fields.put(key, scalar(key, chunk, namespace, name, id, kind, fallback));
    }

    private Scalar scalar(
            String key,
            int chunk,
            String namespace,
            String name,
            Long expectedId,
            String kind,
            Object fallback)
            throws Exception {
        JSONArray originals = new JSONArray();
        boolean mapMismatch = false;
        for (JSONObject attribute : attributes.getOrDefault(chunk, Collections.emptyList())) {
            EvidenceRows.check(stop);
            if (!namespace.equals(attribute.getString("namespace"))
                    || !name.equals(attribute.getString("name"))) continue;
            JSONObject original = attributeCopy(attribute);
            List<Long> ids =
                    resourceIds.getOrDefault(
                            attribute.getInt("poolChunkIndex")
                                    + ":"
                                    + attribute.getInt("nameStringIndex"),
                            Collections.emptyList());
            original.put(
                            "expectedAttributeResourceId",
                            expectedId == null ? JSONObject.NULL : expectedId)
                    .put(
                            "selection",
                            "Original namespace/name; optional compiled attribute identity"
                                    + " cross-check");
            if (expectedId != null
                    && !ids.isEmpty()
                    && (ids.size() != 1 || !ids.get(0).equals(expectedId))) mapMismatch = true;
            originals.put(original);
        }
        if (originals.length() == 0)
            return new Scalar(
                    key,
                    kind,
                    fallback == null ? "absent" : "absent-default",
                    fallback,
                    originals,
                    fallback != null);
        if (originals.length() != 1)
            return new Scalar(key, kind, "ambiguous-attributes", null, originals, false);
        if (mapMismatch)
            return new Scalar(
                    key, kind, "compiled-attribute-identity-mismatch", null, originals, false);
        JSONObject value = originals.getJSONObject(0).getJSONObject("value");
        int type = value.getInt("typeCode");
        Object decoded = null;
        if (kind.equals("string") && type == 3) decoded = value.getString("text");
        else if (kind.equals("uint32") && (type == 16 || type == 17))
            decoded = value.getLong("dataUnsigned");
        else if (kind.equals("boolean") && type == 18) decoded = value.getBoolean("decodedBoolean");
        else if (kind.equals("sdk-literal")) {
            if (type == 3) decoded = value.getString("text");
            else if (type == 16 || type == 17) decoded = value.getLong("dataUnsigned");
        }
        return new Scalar(
                key,
                kind,
                decoded == null ? "unresolved-typed-value" : "literal",
                decoded,
                originals,
                false);
    }

    private void node(JSONObject event) throws Exception {
        if (nodes.length() >= MAX_NODES)
            throw new IOException("Package manifest relevant-element budget exceeded");
        JSONArray all = new JSONArray();
        for (JSONObject attribute :
                attributes.getOrDefault(event.getInt("chunkIndex"), Collections.emptyList())) {
            EvidenceRows.check(stop);
            all.put(attributeCopy(attribute));
        }
        nodes.put(
                copy(event)
                        .put("originalAttributes", all)
                        .put(
                                "coordinateDomain",
                                "Uncompressed AndroidManifest.xml, not parent APK bytes"));
    }

    private JSONObject copy(JSONObject original) throws Exception {
        EvidenceRows.check(stop);
        String encoded = original.toString();
        copiedCharacters += encoded.length();
        if (copiedCharacters > MAX_COPIED_CHARACTERS)
            throw new IOException("Package manifest copied-declaration character budget exceeded");
        return new JSONObject(encoded);
    }

    private JSONObject attributeCopy(JSONObject attribute) throws Exception {
        List<Long> ids =
                resourceIds.getOrDefault(
                        attribute.getInt("poolChunkIndex")
                                + ":"
                                + attribute.getInt("nameStringIndex"),
                        Collections.emptyList());
        if (ids.size() > MAX_ID_ASSOCIATIONS - idAssociations)
            throw new IOException("Package manifest attribute-ID association budget exceeded");
        idAssociations += ids.size();
        return copy(attribute).put("mappedAttributeResourceIds", new JSONArray(ids));
    }

    private void diagnostic(String kind, String message, int chunk) throws Exception {
        diagnostics.put(
                new JSONObject()
                        .put("kind", kind)
                        .put("message", message)
                        .put("manifestChunkIndex", chunk));
    }

    String packageName() {
        return fields.get("package").text();
    }

    boolean splitKnown() {
        Scalar split = fields.get("split");
        return split.status.equals("absent") || split.known();
    }

    String splitName() {
        String value = fields.get("split").text();
        return value == null || value.isEmpty() ? null : value;
    }

    String versionCode64() {
        Long low = fields.get("versionCode").unsigned(),
                high = fields.get("versionCodeMajor").unsigned();
        return low == null || high == null
                ? null
                : BigInteger.valueOf(high).shiftLeft(32).add(BigInteger.valueOf(low)).toString();
    }

    JSONObject json() throws Exception {
        JSONObject values = new JSONObject();
        for (Scalar field : fields.values()) values.put(field.key, field.json());
        JSONArray uses = new JSONArray();
        for (Scalar field : usesSplits) uses.put(field.json());
        return new JSONObject()
                .put("packageName", packageName() == null ? JSONObject.NULL : packageName())
                .put("splitIdentityKnown", splitKnown())
                .put("splitName", splitName() == null ? JSONObject.NULL : splitName())
                .put(
                        "splitEmptyStringNormalization",
                        "Absent or literal empty split denotes base in pinned AOSP lite parsing;"
                                + " original presence/bytes remain in fields")
                .put(
                        "versionCode64UnsignedDecimal",
                        versionCode64() == null ? JSONObject.NULL : versionCode64())
                .put("fields", values)
                .put("usesSplits", uses)
                .put("nodes", nodes)
                .put("diagnostics", diagnostics)
                .put("source", source)
                .put("referenceSourceSha256", REFERENCE_SHA)
                .put(
                        "scope",
                        "Original literal compiled-manifest declarations; no resource resolution,"
                                + " Android name validation, package installation or platform"
                                + " acceptance is implied");
    }
}
