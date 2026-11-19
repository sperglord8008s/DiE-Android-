// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exact declaration relationships. Candidate matches never become installation/trust verdicts. */
final class PackageSetGraph {
    static final String REFERENCE_SHA =
            "7ebfe6d1f7704ff2d1fa7fa0587c62eff7be1aca75403048d2d5844da5dd54fb";
    static final int MAX_ROWS = 50000;
    static final int MAX_REFERENCES = 250000;
    static final long MAX_CHARACTERS = 32L * 1024 * 1024;
    final JSONObject tables = new JSONObject();
    static final String[] TABLES = {
        "summary", "groups", "dependencies", "split-types", "signing-comparisons", "diagnostics"
    };
    private final List<PackageSetDocument.Source> sources;
    private final BooleanSupplier stop;
    private final Map<Key, List<Integer>> definitions = new LinkedHashMap<>();
    private final Map<Group, List<Integer>> groups = new LinkedHashMap<>();
    private final Map<Integer, Integer> uniqueParents = new HashMap<>();
    private final Map<Group, Map<String, JSONArray>> typeProviders = new HashMap<>();
    private final Set<Group> incompleteTypeGroups = new HashSet<>();
    private int rows, references;
    private long characters;
    private boolean declarationCoverageComplete;
    private final long started = System.nanoTime();

    private static final class Group {
        final String pkg, version;

        Group(String pkg, String version) {
            this.pkg = pkg;
            this.version = version;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Group)) return false;
            Group group = (Group) other;
            return Objects.equals(pkg, group.pkg) && Objects.equals(version, group.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pkg, version);
        }
    }

    private static final class Key {
        final Group group;
        final String split;

        Key(Group group, String split) {
            this.group = group;
            this.split = split;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return group.equals(key.group) && Objects.equals(split, key.split);
        }

        @Override
        public int hashCode() {
            return Objects.hash(group, split);
        }
    }

    PackageSetGraph(PackageSetDocument document, BooleanSupplier stop) throws Exception {
        this.sources = document.sources;
        this.stop = stop;
        for (String table : TABLES) tables.put(table, new JSONArray());
        int acquired = 0, manifests = 0, signed = 0;
        boolean declarationsComplete = true;
        for (PackageSetDocument.Source source : sources) {
            EvidenceRows.check(stop);
            if (source.apk != null) acquired++;
            if (source.verified()) signed++;
            for (int i = 0; i < source.errors.length(); i++)
                diagnostic(source.index, "source-scope-failure", source.errors.getJSONObject(i));
            PackageManifest manifest = source.manifest;
            if (manifest == null) {
                declarationsComplete = false;
                continue;
            }
            manifests++;
            for (int i = 0; i < manifest.diagnostics.length(); i++)
                diagnostic(
                        source.index,
                        "manifest-declaration",
                        manifest.diagnostics.getJSONObject(i));
            declarationsComplete &= manifest.diagnostics.length() == 0;
            Group group = group(manifest);
            if (group == null || !manifest.splitKnown()) {
                declarationsComplete = false;
                continue;
            }
            groups.computeIfAbsent(group, key -> new ArrayList<>()).add(source.index);
            definitions
                    .computeIfAbsent(new Key(group, manifest.splitName()), key -> new ArrayList<>())
                    .add(source.index);
        }
        declarationCoverageComplete = declarationsComplete;
        for (Map.Entry<Group, List<Integer>> entry : groups.entrySet()) {
            EvidenceRows.check(stop);
            Group group = entry.getKey();
            JSONArray duplicates = new JSONArray();
            for (Map.Entry<Key, List<Integer>> definition : definitions.entrySet())
                if (definition.getKey().group.equals(group) && definition.getValue().size() > 1)
                    duplicates.put(
                            new JSONObject()
                                    .put("splitName", nullable(definition.getKey().split))
                                    .put("sourceIndices", new JSONArray(definition.getValue())));
            List<Integer> bases =
                    definitions.getOrDefault(new Key(group, null), Collections.emptyList());
            add(
                    "groups",
                    new JSONObject()
                            .put("name", group.pkg + " / version bits " + group.version)
                            .put("packageName", group.pkg)
                            .put("versionCode64UnsignedDecimal", group.version)
                            .put("sourceIndices", new JSONArray(entry.getValue()))
                            .put("baseSourceIndices", new JSONArray(bases))
                            .put("duplicateSplitIdentities", duplicates)
                            .put(
                                    "scope",
                                    "Exact package/version/split literals; names and version bits"
                                            + " are not platform acceptance or signing identity"));
            if (bases.size() != 1)
                diagnostic(
                        -1,
                        "base-candidate-count",
                        new JSONObject()
                                .put("packageName", group.pkg)
                                .put("versionCode64UnsignedDecimal", group.version)
                                .put("baseSourceIndices", new JSONArray(bases)));
            if (duplicates.length() > 0)
                diagnostic(
                        -1,
                        "duplicate-split-identities",
                        new JSONObject()
                                .put("packageName", group.pkg)
                                .put("declarations", duplicates));
        }
        indexProvidedTypes();
        for (PackageSetDocument.Source source : sources) {
            EvidenceRows.check(stop);
            PackageManifest manifest = source.manifest;
            Group group = manifest == null ? null : group(manifest);
            if (group == null || !manifest.splitKnown()) continue;
            if (manifest.splitName() != null) {
                dependency(
                        source,
                        group,
                        null,
                        "package-base-candidate",
                        false,
                        -1,
                        "Same-package/version base file candidate; not a direct classloader edge");
                Boolean feature = manifest.fields.get("isFeatureSplit").flag();
                if (Boolean.TRUE.equals(feature)) {
                    if (manifest.usesSplits.isEmpty())
                        dependency(
                                source,
                                group,
                                null,
                                "feature-default-parent",
                                true,
                                -1,
                                "Pinned AOSP dependency loader defaults a feature without"
                                        + " uses-split to base");
                } else if (Boolean.FALSE.equals(feature)) {
                    PackageManifest.Scalar target = manifest.fields.get("configForSplit");
                    if (target.status.equals("absent"))
                        dependency(
                                source,
                                group,
                                null,
                                "configuration-target",
                                false,
                                -1,
                                "No configForSplit declaration: base configuration candidate");
                    else if (target.text() != null)
                        dependency(
                                source,
                                group,
                                target.text(),
                                "configuration-target",
                                false,
                                -1,
                                "Literal configForSplit target; an empty string is not normalized"
                                        + " to base by the pinned loader");
                    else diagnostic(source.index, "unresolved-configuration-target", target.json());
                } else
                    diagnostic(
                            source.index,
                            "unresolved-split-kind",
                            manifest.fields.get("isFeatureSplit").json());
            }
            for (int ordinal = 0; ordinal < manifest.usesSplits.size(); ordinal++) {
                PackageManifest.Scalar target = manifest.usesSplits.get(ordinal);
                if (target.text() == null) {
                    diagnostic(source.index, "unresolved-dependency", target.json());
                    continue;
                }
                boolean effective =
                        ordinal == 0
                                && manifest.splitName() != null
                                && Boolean.TRUE.equals(
                                        manifest.fields.get("isFeatureSplit").flag());
                dependency(
                        source,
                        group,
                        target.text(),
                        ordinal == 0 ? "uses-split" : "additional-uses-split-declaration",
                        effective,
                        ordinal,
                        effective
                                ? "First feature uses-split declaration; conditional AOSP"
                                        + " relationship, not a measured runtime loader"
                                : "Original declaration retained; no effective platform dependency"
                                        + " inferred for this position/source kind");
            }
            typeRequirements(source, group);
        }
        cycles();
        signingComparisons();
        add(
                "summary",
                new JSONObject()
                        .put(
                                "name",
                                declarationsComplete
                                        ? "Captured package declarations"
                                        : "PARTIAL captured package declarations")
                        .put("explicitInputs", sources.size())
                        .put("acquiredInputs", acquired)
                        .put("decodedManifests", manifests)
                        .put("verifiedEmbeddedApks", signed)
                        .put("declarationAcquisitionComplete", declarationsComplete)
                        .put("signingVerificationComplete", signed == sources.size())
                        .put("groupCount", groups.size())
                        .put("diagnosticCount", tables.getJSONArray("diagnostics").length())
                        .put("acquisition", document.acquisition())
                        .put(
                                "interpretation",
                                "Relationships among captured bytes only. Missing/ambiguous/failed"
                                        + " inputs remain unknown; no whole installed-package"
                                        + " completeness, installability, trust or runtime"
                                        + " class/resource loading is verified."));
    }

    private Group group(PackageManifest manifest) {
        String pkg = manifest.packageName(), version = manifest.versionCode64();
        return pkg == null || pkg.isEmpty() || version == null ? null : new Group(pkg, version);
    }

    private void dependency(
            PackageSetDocument.Source source,
            Group group,
            String target,
            String kind,
            boolean effective,
            int ordinal,
            String mechanism)
            throws Exception {
        List<Integer> candidates =
                definitions.getOrDefault(new Key(group, target), Collections.emptyList());
        JSONObject row =
                new JSONObject()
                        .put(
                                "name",
                                source.name
                                        + " → "
                                        + (target == null ? "<base>" : target)
                                        + " / "
                                        + kind)
                        .put("sourceIndex", source.index)
                        .put("targetSplitName", nullable(target))
                        .put("kind", kind)
                        .put("declarationOrdinal", ordinal < 0 ? JSONObject.NULL : ordinal)
                        .put("targetSourceIndices", new JSONArray(candidates))
                        .put(
                                "candidateStatus",
                                candidates.isEmpty()
                                        ? "no candidate in captured declarations"
                                        : candidates.size() == 1
                                                ? "one exact declaration candidate"
                                                : "ambiguous exact declaration candidates")
                        .put("observedCandidateCount", candidates.size())
                        .put("declarationScopeComplete", declarationCoverageComplete)
                        .put(
                                "candidateScope",
                                "Observed literal declarations only; incomplete fields/inputs can"
                                        + " hide additional candidates")
                        .put("conditionalFirstParent", effective)
                        .put("mechanism", mechanism)
                        .put("targetFeatureKinds", new JSONArray());
        reference(candidates.size());
        for (int index : candidates)
            row.getJSONArray("targetFeatureKinds")
                    .put(
                            new JSONObject()
                                    .put("sourceIndex", index)
                                    .put(
                                            "isFeatureSplit",
                                            nullable(
                                                    sources.get(index)
                                                            .manifest
                                                            .fields
                                                            .get("isFeatureSplit")
                                                            .flag())));
        if (effective && candidates.size() == 1) uniqueParents.put(source.index, candidates.get(0));
        if (candidates.size() != 1)
            diagnostic(
                    source.index,
                    "dependency-candidates",
                    new JSONObject()
                            .put("kind", kind)
                            .put("targetSplitName", nullable(target))
                            .put("targetSourceIndices", new JSONArray(candidates)));
        if (kind.equals("configuration-target") && target != null)
            for (int index : candidates)
                if (!Boolean.TRUE.equals(
                        sources.get(index).manifest.fields.get("isFeatureSplit").flag()))
                    diagnostic(
                            source.index,
                            "configuration-target-not-known-feature",
                            new JSONObject().put("targetSourceIndex", index));
        add("dependencies", row);
    }

    private void typeRequirements(PackageSetDocument.Source source, Group group) throws Exception {
        List<String> requested = tokens(source, "requiredSplitTypes");
        if (requested == null) return;
        for (int ordinal = 0; ordinal < requested.size(); ordinal++) {
            EvidenceRows.check(stop);
            String token = requested.get(ordinal);
            JSONArray providers = token.isEmpty() ? null : typeProviders.get(group).get(token);
            if (providers == null) providers = new JSONArray();
            reference(providers.length());
            add(
                    "split-types",
                    new JSONObject()
                            .put("name", source.name + " requires type declaration " + token)
                            .put("sourceIndex", source.index)
                            .put("requiredTokenOrdinal", ordinal)
                            .put("normalizedToken", token)
                            .put("providerDeclarations", providers)
                            .put("emptyToken", token.isEmpty())
                            .put(
                                    "providerDeclarationScopeComplete",
                                    declarationCoverageComplete
                                            && !incompleteTypeGroups.contains(group))
                            .put(
                                    "interpretation",
                                    "Exact trimmed comma-token associations within captured"
                                        + " package/version declarations. Every duplicate/token is"
                                        + " retained; Android name validation, device targeting and"
                                        + " actual requirement fulfillment are not verified."));
        }
    }

    private void indexProvidedTypes() throws Exception {
        for (Map.Entry<Group, List<Integer>> group : groups.entrySet()) {
            Map<String, JSONArray> index = new HashMap<>();
            typeProviders.put(group.getKey(), index);
            for (int source : group.getValue()) {
                List<String> values = tokens(sources.get(source), "splitTypes");
                if (values == null) {
                    if (!sources.get(source)
                            .manifest
                            .fields
                            .get("splitTypes")
                            .status
                            .equals("absent")) incompleteTypeGroups.add(group.getKey());
                    continue;
                }
                for (int position = 0; position < values.size(); position++) {
                    EvidenceRows.check(stop);
                    String value = values.get(position);
                    if (value.isEmpty()) {
                        incompleteTypeGroups.add(group.getKey());
                        continue;
                    }
                    index.computeIfAbsent(value, key -> new JSONArray())
                            .put(
                                    new JSONObject()
                                            .put("sourceIndex", source)
                                            .put("providedTokenOrdinal", position));
                }
            }
        }
    }

    private List<String> tokens(PackageSetDocument.Source source, String field) throws Exception {
        PackageManifest.Scalar scalar = source.manifest.fields.get(field);
        String text = scalar.text();
        if (text == null) {
            if (!scalar.status.equals("absent"))
                diagnostic(source.index, "unresolved-type-list", scalar.json());
            return null;
        }
        if (text.isEmpty()) return Collections.emptyList();
        int count = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == ',') count++;
        if (text.length() > 8192 || count > 256) {
            diagnostic(
                    source.index,
                    "type-list-budget",
                    new JSONObject()
                            .put("field", field)
                            .put("characterCount", text.length())
                            .put("tokenCount", count));
            return null;
        }
        List<String> result = new ArrayList<>();
        for (String token : text.split(",", -1)) result.add(token.trim());
        return result;
    }

    private void cycles() throws Exception {
        Set<String> reported = new HashSet<>();
        for (int origin : uniqueParents.keySet()) {
            EvidenceRows.check(stop);
            List<Integer> path = new ArrayList<>();
            Map<Integer, Integer> visited = new HashMap<>();
            Integer current = origin;
            while (current != null) {
                Integer previous = visited.put(current, path.size());
                if (previous != null) {
                    List<Integer> cycle = new ArrayList<>(path.subList(previous, path.size()));
                    List<Integer> key = new ArrayList<>(cycle);
                    Collections.sort(key);
                    if (reported.add(key.toString()))
                        diagnostic(
                                origin,
                                "conditional-first-parent-cycle",
                                new JSONObject()
                                        .put("sourceIndices", new JSONArray(cycle))
                                        .put(
                                                "meaning",
                                                "Cycle among unambiguous first-parent declaration"
                                                    + " candidates; not a runtime observation"));
                    break;
                }
                path.add(current);
                if (path.size() > sources.size())
                    throw new IOException("Package dependency traversal exceeded source count");
                current = uniqueParents.get(current);
            }
        }
    }

    private void signingComparisons() throws Exception {
        Map<String, List<Integer>> byPackage = new LinkedHashMap<>();
        for (PackageSetDocument.Source source : sources)
            if (source.manifest != null
                    && source.manifest.packageName() != null
                    && !source.manifest.packageName().isEmpty())
                byPackage
                        .computeIfAbsent(source.manifest.packageName(), key -> new ArrayList<>())
                        .add(source.index);
        for (List<Integer> members : byPackage.values()) {
            List<Integer> bases = new ArrayList<>();
            for (int index : members) {
                PackageManifest manifest = sources.get(index).manifest;
                if (manifest.splitKnown() && manifest.splitName() == null) bases.add(index);
            }
            List<Integer> references =
                    bases.isEmpty() ? Collections.singletonList(members.get(0)) : bases;
            for (int index : members)
                for (int reference : references) {
                    EvidenceRows.check(stop);
                    PackageSetDocument.Source source = sources.get(index),
                            base = sources.get(reference);
                    Set<String> current = source.signerDer(), baseline = base.signerDer();
                    boolean known =
                            source.verified()
                                    && base.verified()
                                    && !current.isEmpty()
                                    && !baseline.isEmpty();
                    String left = source.manifest.versionCode64(),
                            right = base.manifest.versionCode64();
                    add(
                            "signing-comparisons",
                            new JSONObject()
                                    .put("name", source.name + " / reference " + base.name)
                                    .put("sourceIndex", index)
                                    .put("referenceSourceIndex", reference)
                                    .put(
                                            "referenceRole",
                                            bases.isEmpty()
                                                    ? "first captured package-name member; no known"
                                                            + " base"
                                                    : "every captured base declaration, without"
                                                            + " choosing among duplicates/versions")
                                    .put(
                                            "exactVerifiedCertificateDerSetsEqual",
                                            known ? current.equals(baseline) : JSONObject.NULL)
                                    .put("sourceVerifiedSignerSha256", source.signerHashes())
                                    .put("referenceVerifiedSignerSha256", base.signerHashes())
                                    .put(
                                            "versionBitsEqual",
                                            left == null || right == null
                                                    ? JSONObject.NULL
                                                    : left.equals(right))
                                    .put("sourceVersionBits", nullable(left))
                                    .put("referenceVersionBits", nullable(right))
                                    .put(
                                            "interpretation",
                                            "Exact verified APK signer DER-set comparison in each"
                                                + " manifest-minimum-to-API-35 verification range."
                                                + " Parsed certificate/lineage overlap,"
                                                + " platform-specific rotations, device update"
                                                + " policy and publisher trust are separate"
                                                + " questions."));
                }
        }
    }

    private static Object nullable(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    private void diagnostic(int index, String kind, JSONObject detail) throws Exception {
        add(
                "diagnostics",
                new JSONObject()
                        .put("name", kind)
                        .put("sourceIndex", index < 0 ? JSONObject.NULL : index)
                        .put("kind", kind)
                        .put("detail", detail));
    }

    private void reference(int count) throws Exception {
        if (count < 0 || count > MAX_REFERENCES - references)
            throw new IOException(
                    "Package relationship report exceeds 250,000 association references; no"
                            + " silently shortened report");
        references += count;
    }

    private void add(String table, JSONObject row) throws Exception {
        EvidenceRows.check(stop);
        if (System.nanoTime() - started > 30_000_000_000L)
            throw new java.io.InterruptedIOException(
                    "Package relationship cooperative 30-second deadline exceeded");
        if (++rows > MAX_ROWS)
            throw new IOException(
                    "Package relationship report exceeds 50,000 rows; no silently shortened"
                            + " report");
        int size = row.toString().length();
        if (size > MAX_CHARACTERS - characters)
            throw new IOException(
                    "Package relationship report exceeds 32 Mi-characters; no silently shortened"
                            + " report");
        characters += size;
        tables.getJSONArray(table).put(row);
    }
}
