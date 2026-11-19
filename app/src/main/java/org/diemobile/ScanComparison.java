// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exact captured native-tree comparison, preserving original ordinals and duplicate ancestry. */
final class ScanComparison {
    static final String SCHEMA = "org.diemobile.scan-comparison";
    static final int VERSION = 2, MAX_ROWS = 20_000, MAX_KEY_CHARACTERS = 8 * 1024 * 1024;
    static final String[] TABLES = {
        "differences", "matched", "left-only", "right-only", "uncompared", "all"
    };
    private static final long MAX_NANOS = 30_000_000_000L;

    static final class Record {
        final String kind;
        final int leftIndex, rightIndex;

        Record(String kind, int leftIndex, int rightIndex) {
            this.kind = kind;
            this.leftIndex = leftIndex;
            this.rightIndex = rightIndex;
        }
    }

    private static final class Input {
        final String context;
        final List<String> rows;
        final int[] paths, depths;
        final boolean ok;
        int duplicateOccurrences;

        Input(String context, List<String> rows, int[] paths, int[] depths, boolean ok) {
            this.context = context;
            this.rows = Collections.unmodifiableList(rows);
            this.paths = paths;
            this.depths = depths;
            this.ok = ok;
        }
    }

    /** Parent identities and exact canonical strings are interned, never hash-only matched. */
    private static final class Paths {
        final Map<Integer, Map<String, List<Integer>>> parents = new HashMap<>();
        int next = 1;

        int intern(int parent, String fields, int occurrence) {
            List<Integer> matches =
                    parents.computeIfAbsent(parent, ignored -> new HashMap<>())
                            .computeIfAbsent(fields, ignored -> new ArrayList<>());
            if (occurrence == matches.size()) matches.add(next++);
            return matches.get(occurrence);
        }
    }

    private static final class Budget {
        final BooleanSupplier stop;
        final long started = System.nanoTime();
        int characters, values;

        Budget(BooleanSupplier stop) {
            this.stop = stop;
        }

        void check() throws IOException {
            EvidenceRows.check(stop);
            if (System.nanoTime() - started > MAX_NANOS)
                throw new IOException("Scan comparison exceeds its 30-second cooperative budget");
        }
    }

    private final Input left, right;
    private final List<Record> records;
    private final String capturedContext;
    private final String comparedAt = java.time.Instant.now().toString();
    final boolean performed;
    final int matched, leftOnly, rightOnly, uncompared;

    private ScanComparison(
            Input left,
            Input right,
            List<Record> records,
            String context,
            int matched,
            int leftOnly,
            int rightOnly,
            int uncompared) {
        this.left = left;
        this.right = right;
        this.records = Collections.unmodifiableList(records);
        capturedContext = context;
        performed = left.ok && right.ok;
        this.matched = matched;
        this.leftOnly = leftOnly;
        this.rightOnly = rightOnly;
        this.uncompared = uncompared;
    }

    static ScanComparison compare(
            JSONObject leftReport, JSONObject rightReport, JSONObject context, BooleanSupplier stop)
            throws Exception {
        Budget budget = new Budget(stop);
        Paths paths = new Paths();
        Input left = capture(leftReport, paths, budget),
                right = capture(rightReport, paths, budget);
        List<Record> records = new ArrayList<>();
        int matched = 0, onlyLeft = 0, onlyRight = 0, uncompared = 0;
        if (left.ok && right.ok) {
            Map<Integer, Integer> rightPaths = new HashMap<>();
            for (int i = 0; i < right.paths.length; i++) {
                budget.check();
                rightPaths.put(right.paths[i], i);
            }
            BitSet used = new BitSet(right.paths.length);
            for (int i = 0; i < left.paths.length; i++) {
                budget.check();
                Integer other = rightPaths.get(left.paths[i]);
                if (other == null) {
                    records.add(new Record("left-only", i, -1));
                    onlyLeft++;
                } else {
                    records.add(new Record("matched", i, other));
                    used.set(other);
                    matched++;
                }
            }
            for (int i = 0; i < right.paths.length; i++)
                if (!used.get(i)) {
                    budget.check();
                    records.add(new Record("right-only", -1, i));
                    onlyRight++;
                }
            if (used.cardinality() != matched
                    || matched + onlyLeft != left.rows.size()
                    || matched + onlyRight != right.rows.size())
                throw new IOException("Native comparison occurrence accounting failed");
        } else {
            // A failed scan is unknown coverage, not evidence that all detections disappeared.
            for (int i = 0; i < left.rows.size(); i++) {
                budget.check();
                records.add(new Record("uncompared-left", i, -1));
                uncompared++;
            }
            for (int i = 0; i < right.rows.size(); i++) {
                budget.check();
                records.add(new Record("uncompared-right", -1, i));
                uncompared++;
            }
        }
        budget.check();
        return new ScanComparison(
                left,
                right,
                records,
                encode(context, stop),
                matched,
                onlyLeft,
                onlyRight,
                uncompared);
    }

    private static Input capture(JSONObject report, Paths paths, Budget budget) throws Exception {
        budget.check();
        if (!"org.diemobile.scan-report".equals(report.getString("schema"))
                || report.getInt("schemaVersion") != 1)
            throw new IOException("Unsupported captured scan-report schema");
        JSONObject nativeScan = report.getJSONObject("scan");
        JSONArray declaredRows = nativeScan.optJSONArray("rows");
        if (declaredRows != null && declaredRows.length() > MAX_ROWS)
            throw new IOException("Scan comparison exceeds 20,000 rows per native tree");
        ScanEvidence evidence = ScanEvidence.parse(nativeScan.toString());
        if (evidence.rows.size() > MAX_ROWS)
            throw new IOException("Scan comparison exceeds 20,000 rows per native tree");
        JSONObject metadata = new JSONObject();
        for (Iterator<String> keys = nativeScan.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!key.equals("rows")) metadata.put(key, nativeScan.get(key));
        }
        JSONObject context =
                new JSONObject()
                        .put("createdAt", report.opt("createdAt"))
                        .put("source", report.getJSONObject("source"))
                        .put("options", report.getJSONObject("options"))
                        .put("scanTarget", report.opt("scanTarget"))
                        .put("provenance", report.getJSONObject("provenance"))
                        .put("engineError", report.opt("engineError"))
                        .put("nativeMetadata", metadata);
        List<String> originals = new ArrayList<>();
        int[] ids = new int[evidence.rows.size()], depths = new int[ids.length];
        Map<Integer, Map<String, Integer>> occurrences = new HashMap<>();
        Input captured =
                new Input(encode(context, budget.stop), originals, ids, depths, evidence.ok);
        JSONArray rows = nativeScan.optJSONArray("rows");
        for (ScanEvidence.Row row : evidence.rows) {
            budget.check();
            JSONObject raw = rows.getJSONObject(row.index);
            int parent = row.parent < 0 ? 0 : ids[row.parent];
            StringWriter encodedFields = new StringWriter();
            Writer canonical =
                    new Writer() {
                        @Override
                        public void write(char[] chars, int offset, int count) throws IOException {
                            budget.check();
                            if (count > MAX_KEY_CHARACTERS - budget.characters)
                                throw new IOException(
                                        "Scan comparison exceeds 8 Mi-characters of canonical row"
                                                + " fields; no partial comparison");
                            budget.characters += count;
                            encodedFields.write(chars, offset, count);
                        }

                        @Override
                        public void write(String value, int offset, int count) throws IOException {
                            budget.check();
                            if (count > MAX_KEY_CHARACTERS - budget.characters)
                                throw new IOException(
                                        "Scan comparison exceeds 8 Mi-characters of canonical row"
                                                + " fields; no partial comparison");
                            budget.characters += count;
                            encodedFields.write(value, offset, count);
                        }

                        @Override
                        public void write(int value) throws IOException {
                            budget.check();
                            if (budget.characters == MAX_KEY_CHARACTERS)
                                throw new IOException(
                                        "Scan comparison exceeds 8 Mi-characters of canonical row"
                                                + " fields; no partial comparison");
                            budget.characters++;
                            encodedFields.write(value);
                        }

                        @Override
                        public void flush() {}

                        @Override
                        public void close() {}
                    };
            canonical(raw, canonical, budget, 0, true);
            String fields = encodedFields.toString();
            Map<String, Integer> siblings =
                    occurrences.computeIfAbsent(parent, ignored -> new HashMap<>());
            int occurrence = siblings.getOrDefault(fields, 0);
            siblings.put(fields, occurrence + 1);
            if (occurrence > 0) captured.duplicateOccurrences++;
            ids[row.index] = paths.intern(parent, fields, occurrence);
            depths[row.index] = row.depth;
            originals.add(encode(raw, budget.stop));
        }
        return captured;
    }

    /**
     * Object-key order is irrelevant; array order/types/UTF-16/unknown fields remain significant.
     */
    private static void canonical(
            Object value, Writer output, Budget budget, int depth, boolean rowRoot)
            throws Exception {
        budget.check();
        if (depth > 64 || ++budget.values > 1_000_000)
            throw new IOException("Scan comparison exceeds canonical nesting/node budget");
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            for (Iterator<String> iterator = object.keys(); iterator.hasNext(); ) {
                String key = iterator.next();
                if (!rowRoot || !key.equals("parent")) keys.add(key);
            }
            Collections.sort(keys);
            output.write('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) output.write(',');
                String key = keys.get(i);
                EvidenceJson.quoted(output, key, budget.stop);
                output.write(':');
                canonical(object.get(key), output, budget, depth + 1, false);
            }
            output.write('}');
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            output.write('[');
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) output.write(',');
                canonical(array.get(i), output, budget, depth + 1, false);
            }
            output.write(']');
        } else EvidenceJson.write(output, value, budget.stop);
    }

    private static String encode(Object value, BooleanSupplier stop) throws Exception {
        StringWriter writer = new StringWriter();
        EvidenceJson.write(writer, value, stop);
        return writer.toString();
    }

    List<Record> table(String name) throws IOException {
        if (!Arrays.asList(TABLES).contains(name))
            throw new IOException("Unknown scan comparison table");
        List<Record> selected = new ArrayList<>();
        for (Record record : records)
            if (name.equals("all")
                    || name.equals("differences")
                            && (record.kind.equals("left-only") || record.kind.equals("right-only"))
                    || name.equals(record.kind)
                    || name.equals("uncompared") && record.kind.startsWith("uncompared-"))
                selected.add(record);
        return Collections.unmodifiableList(selected);
    }

    JSONObject record(Record record) throws Exception {
        return new JSONObject()
                .put("kind", record.kind)
                .put("leftRowIndex", record.leftIndex < 0 ? JSONObject.NULL : record.leftIndex)
                .put("rightRowIndex", record.rightIndex < 0 ? JSONObject.NULL : record.rightIndex)
                .put(
                        "leftDepth",
                        record.leftIndex < 0 ? JSONObject.NULL : left.depths[record.leftIndex])
                .put(
                        "rightDepth",
                        record.rightIndex < 0 ? JSONObject.NULL : right.depths[record.rightIndex])
                .put(
                        "leftNativeRow",
                        record.leftIndex < 0
                                ? JSONObject.NULL
                                : new JSONObject(left.rows.get(record.leftIndex)))
                .put(
                        "rightNativeRow",
                        record.rightIndex < 0
                                ? JSONObject.NULL
                                : new JSONObject(right.rows.get(record.rightIndex)));
    }

    JSONObject metadata() throws Exception {
        JSONObject leftContext = new JSONObject(left.context),
                rightContext = new JSONObject(right.context);
        JSONObject leftSource = leftContext.getJSONObject("source"),
                rightSource = rightContext.getJSONObject("source");
        JSONObject leftEngine = leftContext.getJSONObject("provenance"),
                rightEngine = rightContext.getJSONObject("provenance");
        JSONObject leftOptions = leftContext.getJSONObject("options"),
                rightOptions = rightContext.getJSONObject("options");
        boolean selectorsResolved =
                leftOptions.get("typeId") != JSONObject.NULL
                        && rightOptions.get("typeId") != JSONObject.NULL;
        boolean sameOtherNativeOptions =
                leftOptions.getInt("flags") == rightOptions.getInt("flags")
                        && leftOptions
                                .getString("includeTags")
                                .equals(rightOptions.getString("includeTags"))
                        && leftOptions
                                .getString("excludeTags")
                                .equals(rightOptions.getString("excludeTags"));
        boolean sameNativeOptions =
                selectorsResolved
                        && effectiveNativeOptions(leftOptions)
                                .equals(effectiveNativeOptions(rightOptions));
        boolean samePreviewMinimum =
                leftOptions.getInt("minimumStringLength")
                        == rightOptions.getInt("minimumStringLength");
        boolean leftCustomEnabled = (leftOptions.getInt("flags") & ScanOptions.CUSTOM_DB) != 0;
        boolean rightCustomEnabled = (rightOptions.getInt("flags") & ScanOptions.CUSTOM_DB) != 0;
        boolean sameEngine =
                leftEngine
                        .getString("engineRevision")
                        .equals(rightEngine.getString("engineRevision"));
        boolean sameSignatures =
                leftEngine
                        .getString("signatureRevision")
                        .equals(rightEngine.getString("signatureRevision"));
        boolean sameCustom =
                leftEngine
                        .getJSONObject("customDatabase")
                        .getString("archiveSha256")
                        .equals(
                                rightEngine
                                        .getJSONObject("customDatabase")
                                        .getString("archiveSha256"));
        boolean sameEffectiveCustom =
                leftCustomEnabled == rightCustomEnabled && (!leftCustomEnabled || sameCustom);
        boolean knownConfigurationDifference =
                !sameOtherNativeOptions || !sameEngine || !sameSignatures || !sameEffectiveCustom;
        Object nativeEquality =
                !sameOtherNativeOptions
                        ? false
                        : selectorsResolved ? sameNativeOptions : JSONObject.NULL;
        Object configurationDifference =
                knownConfigurationDifference
                        ? true
                        : selectorsResolved ? !sameNativeOptions : JSONObject.NULL;
        return new JSONObject()
                .put("schema", SCHEMA)
                .put("schemaVersion", VERSION)
                .put("algorithm", "exact-native-tree-paths/v1")
                .put("comparedAt", comparedAt)
                .put("comparisonPerformed", performed)
                .put("left", leftContext)
                .put("right", rightContext)
                .put("capturedReportIdentity", new JSONObject(capturedContext))
                .put("leftRows", left.rows.size())
                .put("rightRows", right.rows.size())
                .put("matched", matched)
                .put("leftOnly", leftOnly)
                .put("rightOnly", rightOnly)
                .put("uncompared", uncompared)
                .put(
                        "duplicateSiblingOccurrences",
                        new JSONObject()
                                .put("left", left.duplicateOccurrences)
                                .put("right", right.duplicateOccurrences))
                .put(
                        "conditions",
                        new JSONObject()
                                .put(
                                        "sameCapturedInputIdentity",
                                        leftSource.getLong("sizeBytes")
                                                        == rightSource.getLong("sizeBytes")
                                                && leftSource
                                                        .getString("sha256")
                                                        .equals(rightSource.getString("sha256")))
                                .put("inputBytesReRead", false)
                                .put(
                                        "sameEffectiveOptions",
                                        samePreviewMinimum ? nativeEquality : false)
                                .put("nativeSelectorsResolved", selectorsResolved)
                                .put("sameNativeOptions", nativeEquality)
                                .put("samePreviewMinimum", samePreviewMinimum)
                                .put("sameEngineRevision", sameEngine)
                                .put("sameSignatureRevision", sameSignatures)
                                .put("sameCustomArchive", sameCustom)
                                .put("sameEffectiveCustomDatabase", sameEffectiveCustom)
                                .put("configurationDiffers", configurationDifference))
                .put(
                        "limits",
                        new JSONObject()
                                .put("rowsPerNativeTree", MAX_ROWS)
                                .put("canonicalRowCharacters", MAX_KEY_CHARACTERS)
                                .put("canonicalNodes", 1_000_000)
                                .put("canonicalDepth", 64)
                                .put("processingSeconds", 30))
                .put(
                        "limitations",
                        new JSONArray()
                                .put(
                                        "Only captured native tree records are compared. Generic"
                                                + " previews, APK analysis, facts, logs and source"
                                                + " bytes are not semantically diffed.")
                                .put(
                                        "Every field except the root row's numeric parent position"
                                            + " participates in exact matching. Object-key order is"
                                            + " ignored; array order and unknown fields remain"
                                            + " significant.")
                                .put(
                                        "Paths include parent identity and occurrence order among"
                                            + " identical siblings. Duplicate group"
                                            + " insertion/reordering can change child alignment;"
                                            + " occurrence matching is deterministic, not semantic"
                                            + " identity.")
                                .put(
                                        "A failed native scan has unknown coverage: successful-side"
                                                + " records remain uncompared, never labeled"
                                                + " disappeared/added.")
                                .put(
                                        "Left-only/right-only means absent from the other captured"
                                            + " tree, not absent from that file or proof of"
                                            + " behavior. Changed options/rules/engines can explain"
                                            + " differences.")
                                .put(
                                        "No physical offsets, vulnerability/safety verdict, causal"
                                                + " attribution or full Qt/heuristic equivalence is"
                                                + " inferred."));
    }

    private static String effectiveNativeOptions(JSONObject options) throws Exception {
        return options.getInt("flags")
                + ":"
                + (options.get("typeId") == JSONObject.NULL
                        ? "unresolved"
                        : options.getInt("typeId"))
                + ":"
                + encode(options.getString("includeTags"), () -> false)
                + ":"
                + encode(options.getString("excludeTags"), () -> false);
    }

    void writeReport(Writer output, BooleanSupplier stop) throws Exception {
        String header = encode(metadata(), stop);
        output.write(header, 0, header.length() - 1);
        output.write(",\"records\":[");
        for (int i = 0; i < records.size(); i++) {
            EvidenceRows.check(stop);
            if (i > 0) output.write(',');
            EvidenceJson.write(output, record(records.get(i)), stop);
        }
        EvidenceRows.check(stop);
        output.write("]}");
    }
}
