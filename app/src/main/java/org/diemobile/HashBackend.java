// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Typed whole-file identities, separate from parser-specific/signature hashes. */
final class HashBackend extends InvestigationBackend {
    private final File input,directory;
    private final String name;
    private final BinaryRunner runner;
    private HashDocument document;
    HashBackend(File input,File directory,String name,BinaryRunner runner) {
        this.input=input;this.directory=directory;this.name=name;this.runner=runner;
    }
    @Override String initialRequest(BooleanSupplier stop) { return "{}"; }
    @Override EvidenceRows open(String request,BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        if(document==null) document=HashDocument.read(input,directory,name,runner,stop);
        return document.rows();
    }
    @Override List<Link> roots(String request,BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);return Collections.singletonList(Link.view("All eight whole-file digests","{}"));
    }
    @Override List<Link> related(String request,EvidenceRows rows,EvidenceRows.Row row,BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        return Collections.singletonList(Link.text("View / export this complete digest value",new JSONObject().put("index",row.index).toString(),row.name.toLowerCase(Locale.ROOT)+".txt"));
    }
    @Override void writeText(String request,Writer output,BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);int index=new JSONObject(request).getInt("index");
        if(document==null||index<0||index>=HashDocument.ALGORITHMS.size()) throw new IOException("Hash value selector expired");
        output.write(document.digests.getJSONObject(index).getString("hex"));output.write('\n');
    }
    @Override JSONObject provenance() throws Exception { return document.provenance(); }
    @Override File byteSource() { return document==null?input:document.snapshot.file; }
    @Override String sourceName() { return name; }
    @Override String help(String request) {
        return "Complete whole-file CRC32 / MD4 / MD5 / SHA1 / SHA224 / SHA256 / SHA384 / SHA512. Search algorithm or exact hex. Each row explains its mechanism, implementation and security limits. SHA-256 is independently cross-checked against acquisition. These are raw-file digests, not reputation, package signing or fuzzy hashes. Tap for source/process/coverage details.";
    }
    @Override public void close() throws Exception {
        try { runner.close(); } finally { if(document!=null) document.close(); }
    }
}
