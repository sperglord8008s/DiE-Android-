// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Searchable captured batch outcomes and original native trees, with per-input byte ownership. */
final class BatchScanBackend extends InvestigationBackend {
    private final File root, snapshots;
    private final String id;
    private BatchScanSession session;
    private InputSnapshot index;
    private File active;
    private String activeName = "Batch journal";
    private int selectedSource = -1;

    BatchScanBackend(File root, File snapshots, String id) {
        this.root = root;
        this.snapshots = snapshots;
        this.id = id;
    }

    private static String request(String table, int source) throws JSONException {
        return new JSONObject().put("table", table).put("source", source).toString();
    }

    private void ensure(BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        if (session != null) return;
        BatchScanSession loaded = BatchScanSession.open(root, id, stop);
        InputSnapshot captured =
                InputSnapshot.acquire(
                        new ByteArrayInputStream(loaded.journalBytes()),
                        snapshots,
                        2L * 1024 * 1024,
                        stop);
        session = loaded;
        index = captured;
        active = index.file;
    }

    @Override
    String initialRequest(BooleanSupplier stop) throws Exception {
        ensure(stop);
        return request("inputs", -1);
    }

    @Override
    EvidenceRows open(String value, BooleanSupplier stop) throws Exception {
        ensure(stop);
        JSONObject query = new JSONObject(value);
        String table = query.getString("table");
        int source = query.optInt("source", -1);
        selectedSource = -1;
        active = index.file;
        activeName = "Captured batch journal";
        List<JSONObject> records = new ArrayList<>();
        List<String> names = new ArrayList<>(), descriptions = new ArrayList<>();
        Long range = null;
        if (table.equals("inputs")) {
            for (BatchScanSession.Entry entry : session.entries) {
                records.add(entry.json());
                names.add("#" + entry.index + " " + entry.name);
                descriptions.add(entry.status + " • " + entry.stage + " • " + entry.error);
            }
        } else {
            BatchScanSession.Entry entry = session.entry(source);
            if (table.equals("input")) {
                active = session.input(entry, stop);
                activeName = entry.name;
                selectedSource = entry.index;
                records.add(
                        entry.json().put("coordinateDomain", "Exact captured whole-file bytes"));
                names.add(entry.name);
                descriptions.add("Owned input • SHA-256 " + entry.sha256);
                range = entry.size;
            } else if (table.equals("detections")) {
                JSONObject report = session.report(entry, stop);
                // Reading the report does not require its input to remain available. Byte actions
                // do.
                ScanEvidence evidence = ScanEvidence.parse(report.getJSONObject("scan").toString());
                JSONArray rows = evidence.toJson().optJSONArray("rows");
                for (ScanEvidence.Row row : evidence.rows) {
                    EvidenceRows.check(stop);
                    records.add(
                            new JSONObject()
                                    .put("sourceIndex", source)
                                    .put("inputSha256", entry.sha256)
                                    .put("nativeRow", rows.getJSONObject(row.index))
                                    .put("nativeIndex", row.index)
                                    .put("depth", row.depth));
                    names.add(row.text);
                    descriptions.add(
                            "parent=" + row.parent + " • " + row.category + " • " + row.signature);
                }
                if (!evidence.ok) {
                    records.add(
                            new JSONObject()
                                    .put("sourceIndex", source)
                                    .put("error", evidence.error));
                    names.add("Native scan failed");
                    descriptions.add(evidence.error);
                }
            } else throw new IOException("Unknown batch evidence scope");
        }
        JSONObject metadata =
                session.metadata()
                        .put("table", table)
                        .put("selectedSourceIndex", source < 0 ? JSONObject.NULL : source);
        Long inputRange = range;
        return new EvidenceRows("desktop-batch-" + table, "Desktop batch • " + table) {
            @Override
            int size() {
                return records.size();
            }

            @Override
            JSONObject metadata() {
                return metadata;
            }

            @Override
            Row row(int position, BooleanSupplier cancelled) throws Exception {
                EvidenceRows.check(cancelled);
                if (position < 0 || position >= records.size())
                    throw new IOException("Batch evidence row outside scope");
                return new Row(
                        position,
                        names.get(position),
                        descriptions.get(position),
                        inputRange == null ? null : 0L,
                        inputRange,
                        new JSONObject(records.get(position).toString()));
            }
        };
    }

    @Override
    List<Link> roots(String value, BooleanSupplier stop) throws Exception {
        ensure(stop);
        List<Link> links = new ArrayList<>();
        links.add(Link.view("Every planned input / outcome", request("inputs", -1)));
        links.add(
                Link.text(
                        "Preview / export complete batch JSON",
                        "complete-batch",
                        "die-desktop-batch.json"));
        return links;
    }

    @Override
    List<Link> related(String value, EvidenceRows rows, EvidenceRows.Row row, BooleanSupplier stop)
            throws Exception {
        EvidenceRows.check(stop);
        JSONObject record = row.json();
        int source = record.getInt("sourceIndex");
        BatchScanSession.Entry entry = session.entry(source);
        List<Link> links = new ArrayList<>();
        if (!entry.snapshotName.isEmpty())
            links.add(
                    Link.view("Exact owned input / hex / range export", request("input", source)));
        if (!entry.reportName.isEmpty()) {
            links.add(Link.view("Original native detection tree", request("detections", source)));
            links.add(
                    Link.text(
                            "Preview / export complete original scan report",
                            "report:" + source,
                            "die-batch-input-" + source + ".json"));
        }
        return links;
    }

    @Override
    void writeText(String value, Writer output, BooleanSupplier stop) throws Exception {
        ensure(stop);
        if (value.equals("complete-batch")) session.writeReport(output, stop);
        else if (value.startsWith("report:"))
            EvidenceJson.write(
                    output,
                    session.report(session.entry(Integer.parseInt(value.substring(7))), stop),
                    stop);
        else throw new IOException("Unknown batch report scope");
    }

    @Override
    JSONObject provenance() throws Exception {
        JSONObject result = session.metadata();
        if (selectedSource >= 0) result.put("byteSource", session.entry(selectedSource).json());
        else
            result.put(
                    "byteSource",
                    new JSONObject()
                            .put("name", "Captured batch journal")
                            .put("sizeBytes", index.size)
                            .put("sha256", index.sha256)
                            .put(
                                    "coordinateDomain",
                                    "Journal JSON bytes; aggregate rows have no input-file"
                                            + " offsets"));
        return result;
    }

    @Override
    File byteSource() {
        return active;
    }

    @Override
    String sourceName() {
        return activeName;
    }

    @Override
    String help(String request) {
        return "Every selected document has an independent outcome. Complete means the native"
                + " request returned; inspect its raw tree, diagnostics, bounds and exact"
                + " options. Duplicate names remain separate ordinal inputs. Detection rows have"
                + " no invented physical offset. Select the owned input view for its bytes. A"
                + " persisted running/planned journal is incomplete; jobs are never silently"
                + " resumed. A match alone does not establish execution, safety or malicious"
                + " behavior.";
    }

    @Override
    public void close() {
        if (index != null) index.close();
    }
}
