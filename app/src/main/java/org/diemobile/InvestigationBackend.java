// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Android-independent provider contract shared by investigation viewers. */
abstract class InvestigationBackend implements AutoCloseable {
    static final class Link {
        final String label, request, textName;
        final int row;
        final boolean binary;
        final boolean input;

        private Link(String label, String request, int row, String textName) {
            this(label, request, row, textName, false);
        }

        private Link(String label, String request, int row, String textName, boolean binary) {
            this(label, request, row, textName, binary, false);
        }

        private Link(
                String label,
                String request,
                int row,
                String textName,
                boolean binary,
                boolean input) {
            this.label = label;
            this.request = request;
            this.row = row;
            this.textName = textName;
            this.binary = binary;
            this.input = input;
        }

        static Link view(String label, String request) {
            return new Link(label, request, -1, null);
        }

        static Link jump(String label, String request, int row) {
            return new Link(label, request, row, null);
        }

        static Link text(String label, String request, String name) {
            return new Link(label, request, -1, name);
        }

        static Link blob(String label, String request, String name) {
            return new Link(label, request, -1, name, true);
        }

        static Link input(String label, String request) {
            return new Link(label, request, -1, null, false, true);
        }
    }

    abstract String initialRequest(BooleanSupplier cancelled) throws Exception;

    abstract EvidenceRows open(String request, BooleanSupplier cancelled) throws Exception;

    abstract List<Link> roots(String request, BooleanSupplier cancelled) throws Exception;

    abstract List<Link> related(
            String request, EvidenceRows source, EvidenceRows.Row row, BooleanSupplier cancelled)
            throws Exception;

    abstract JSONObject provenance() throws Exception;

    abstract File byteSource();

    abstract String sourceName();

    abstract String help(String request);

    /** Factual failures/coverage belong outside optional definitions. */
    String statusSummary() { return ""; }

    void writeText(String request, Writer output, BooleanSupplier cancelled) throws Exception {
        throw new IOException("No text renderer for this scope");
    }

    byte[] binaryValue(String request, BooleanSupplier cancelled) throws Exception {
        throw new IOException("No binary value for this scope");
    }

    void acceptInput(String request, InputStream input, String name, BooleanSupplier cancelled)
            throws Exception {
        throw new IOException("This provider accepts no supplemental input");
    }

    boolean inputRequiresDocument(String request) {
        return true;
    }

    /** Private lifecycle state, captured on the worker; never included in an evidence export. */
    String retentionState() throws Exception {
        return null;
    }

    void close(boolean retainInputsForRecreation) throws Exception {
        close();
    }

    @Override
    public void close() throws Exception {}
}
