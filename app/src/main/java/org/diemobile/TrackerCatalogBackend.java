// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Catalog acquisition owns exact imported bytes independently of subsequent replacement. */
final class TrackerCatalogBackend extends InvestigationBackend {
    interface Source { InputStream open() throws Exception; }
    private final Source source;
    private final File directory;
    private File owned;
    private TrackerCatalog catalog;
    TrackerCatalogBackend(Source source,File directory) { this.source=source;this.directory=directory; }
    private void acquire(BooleanSupplier stop) throws Exception {
        if(catalog!=null) return;
        if(!directory.isDirectory()&&!directory.mkdirs()) throw new IOException("Cannot create private tracker snapshot directory");
        TrackerCatalog candidate;try(InputStream input=source.open()) { candidate=TrackerCatalog.read(input,stop); }
        File file=File.createTempFile("tracker-catalog-",".json",directory);boolean success=false;
        try {
            try(OutputStream output=new FileOutputStream(file)) { output.write(candidate.bytes); }
            EvidenceRows.check(stop);owned=file;catalog=candidate;success=true;
        } finally { if(!success) file.delete(); }
    }
    @Override String initialRequest(BooleanSupplier stop) { return "rules"; }
    @Override EvidenceRows open(String request,BooleanSupplier stop) throws Exception {
        acquire(stop);if(!Arrays.asList("rules","diagnostics","metadata").contains(request)) throw new IOException("Unknown tracker catalog scope");
        return catalog.rows(request.equals("diagnostics"),request.equals("metadata"));
    }
    @Override List<Link> roots(String request,BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);return Arrays.asList(Link.view("Every imported tracker record","rules"),Link.view("Unsupported / short signature diagnostics","diagnostics"),
            Link.view("Original snapshot version / source / other metadata","metadata"),Link.blob("Export exact imported JSON snapshot","snapshot","exodus-"+catalog.sha256.substring(0,12)+".json"));
    }
    @Override List<Link> related(String request,EvidenceRows rows,EvidenceRows.Row row,BooleanSupplier stop) { return Collections.emptyList(); }
    @Override byte[] binaryValue(String request,BooleanSupplier stop) throws Exception {
        acquire(stop);EvidenceRows.check(stop);if(!request.equals("snapshot")) throw new IOException("Unknown tracker byte export");return catalog.bytes.clone();
    }
    @Override JSONObject provenance() throws Exception { return catalog.provenance(); }
    @Override File byteSource() { return owned; }
    @Override String sourceName() { return "Imported Exodus tracker definitions"; }
    @Override String help(String request) { return "Complete imported tracker signatures and metadata • search original field values; unsupported/short signatures stay visible. JSON exports retain exact original record values and unknown fields; exact-byte snapshot export preserves the full import. Catalog definitions are separate from observed class matches in APK / SDKs. Tap for source identity, matching contract and limits."; }
    @Override public void close() { if(owned!=null) owned.delete(); }
}
