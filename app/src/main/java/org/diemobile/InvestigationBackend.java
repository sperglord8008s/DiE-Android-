// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Android-independent provider contract shared by investigation viewers. */
abstract class InvestigationBackend implements AutoCloseable {
    static final class Link {
        final String label, request, textName;
        final int row;
        private Link(String label, String request, int row, String textName) { this.label = label; this.request = request; this.row = row; this.textName = textName; }
        static Link view(String label, String request) { return new Link(label, request, -1, null); }
        static Link jump(String label, String request, int row) { return new Link(label, request, row, null); }
        static Link text(String label, String request, String name) { return new Link(label, request, -1, name); }
    }
    abstract String initialRequest(BooleanSupplier cancelled) throws Exception;
    abstract EvidenceRows open(String request, BooleanSupplier cancelled) throws Exception;
    abstract List<Link> roots(String request, BooleanSupplier cancelled) throws Exception;
    abstract List<Link> related(String request, EvidenceRows source, EvidenceRows.Row row, BooleanSupplier cancelled) throws Exception;
    abstract JSONObject provenance() throws Exception;
    abstract File byteSource();
    abstract String sourceName();
    abstract String help(String request);
    void writeText(String request, Writer output, BooleanSupplier cancelled) throws Exception { throw new IOException("No text renderer for this scope"); }
    @Override public void close() throws Exception {}
}
