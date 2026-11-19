// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** File metadata and codec/container investigation through actual isolated libmpv. */
final class MpvBackend extends InvestigationBackend {
    private final File input, directory;
    private final String name;
    private final BinaryRunner runner;
    private MpvDocument document;
    private MpvTables tables;
    MpvBackend(File input, File directory, String name, BinaryRunner runner) {
        this.input = input; this.directory = directory; this.name = name; this.runner = runner;
    }
    static String request(String kind, String scope, int property, JSONArray path) throws Exception {
        return new JSONObject().put("kind",kind).put("scope",scope).put("property",property).put("path",path).toString();
    }
    private static String view(String kind, String scope) throws Exception { return request(kind,scope,-1,new JSONArray()); }
    private void acquire(BooleanSupplier stop) throws Exception {
        if (document != null) return;
        document = MpvDocument.read(input,directory,name,runner,stop);
        tables = new MpvTables(document);
    }
    @Override String initialRequest(BooleanSupplier stop) throws Exception { return view("properties","media"); }
    @Override EvidenceRows open(String encoded, BooleanSupplier stop) throws Exception {
        acquire(stop);
        JSONObject query = new JSONObject(encoded);
        switch (query.getString("kind")) {
            case "properties": return tables.properties(query.getString("scope"));
            case "node": return tables.nodes(query.getInt("property"),query.getJSONArray("path"));
            case "catalog": return tables.catalog();
            case "diagnostics": return tables.records("diagnostics");
            case "options": return tables.records("options");
            case "summary": return tables.summary();
            default: throw new IOException("Unknown MPV investigation view");
        }
    }
    @Override List<Link> roots(String encoded, BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        return Arrays.asList(Link.view("File / stream / chapter metadata",view("properties","media")),
            Link.view("Every attempted file/engine property",view("properties","all")),
            Link.view("Collected properties",view("properties","available")),
            Link.view("Unavailable properties and exact errors",view("properties","unavailable")),
            Link.view("Codec / container capabilities and engine versions",view("properties","capabilities")),
            Link.view("Complete property-name catalog / references",view("catalog","")),
            Link.view("Engine diagnostics",view("diagnostics","")),Link.view("Exact guarded initialization options",view("options","")),
            Link.view("Input / engine / process / coverage",view("summary","")));
    }
    @Override List<Link> related(String encoded, EvidenceRows source, EvidenceRows.Row row, BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        List<Link> links = new ArrayList<>();
        JSONObject fields = row.json();
        if (fields.optInt("propertyIndex",-1) < 0) return links;
        int property = fields.getInt("propertyIndex");
        if (!document.properties.getJSONObject(property).getString("status").equals("available")) return links;
        JSONArray path = fields.optJSONArray("nodePath");
        if (path == null) path = new JSONArray();
        JSONObject node = document.node(property,path);
        String target = request("node","",property,path);
        links.add(Link.view("Open complete typed value / children",target));
        if (node.getString("type").equals("string")) {
            links.add(Link.text("View / export complete decoded engine text",target,"mpv-value.txt"));
            links.add(Link.blob("Export exact engine-supplied UTF-8 value bytes",target,"mpv-value.utf8"));
        } else if (node.getString("type").equals("bytes")) links.add(Link.blob("Export complete engine byte-array value",target,"mpv-value.bin"));
        return links;
    }
    @Override void writeText(String encoded, Writer output, BooleanSupplier stop) throws Exception {
        JSONObject query = new JSONObject(encoded);
        JSONObject node = document.node(query.getInt("property"),query.getJSONArray("path"));
        if (!node.getString("type").equals("string")) throw new IOException("MPV node is not textual");
        String value = node.getString("value");
        for (int i = 0; i < value.length(); i += 4096) { EvidenceRows.check(stop); output.write(value,i,Math.min(4096,value.length()-i)); }
    }
    @Override byte[] binaryValue(String encoded, BooleanSupplier stop) throws Exception {
        JSONObject query = new JSONObject(encoded);
        JSONObject node = document.node(query.getInt("property"),query.getJSONArray("path"));
        String type = node.getString("type");
        if (!type.equals("string") && !type.equals("bytes")) throw new IOException("MPV node has no byte representation");
        return MpvDocument.decodeHex(node.getString(type.equals("string")?"utf8Hex":"dataHex"),stop);
    }
    @Override JSONObject provenance() throws Exception { return document.provenance(); }
    @Override File byteSource() { return document == null ? input : document.snapshot.file; }
    @Override String sourceName() { return name; }
    @Override String statusSummary() {
        return document != null && !document.header.optBoolean("fileProbeComplete")
                ? "UNSUPPORTED / INCOMPLETE MEDIA PROBE" : "";
    }
    @Override String help(String request) {
        return "Actual libmpv • file metadata, tracks, chapters, codec/container catalogs and exact unavailable-property errors. Search matches keys/value previews; open typed children or export JSON/text/bytes for complete values. Source-pinned technical references accompany properties. No physical tag offsets or complete decode verdict are inferred. Tap for engine/process/limits.";
    }
    @Override public void close() throws Exception { try { runner.close(); } finally { if (document != null) document.close(); } }
}
