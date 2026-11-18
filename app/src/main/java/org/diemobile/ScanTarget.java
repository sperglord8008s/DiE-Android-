// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Input-specific native interpretation, separate from portable option profiles. */
final class ScanTarget {
    static final String SCHEMA = "org.diemobile.scan-target";
    static final Request AUTOMATIC = new Request("");

    static final class Request {
        final String formatName;

        Request(String name) {
            if (name == null || name.length() > 4096 || name.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid native interpretation name");
            formatName = name;
        }

        boolean automatic() {
            return formatName.isEmpty();
        }

        String label() {
            return automatic() ? "Automatic" : formatName;
        }

        boolean sameAs(Request other) {
            return other != null && formatName.equals(other.formatName);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("selectionMode", automatic() ? "automatic" : "compatible-name")
                    .put("formatName", formatName);
        }

        static Request restore(String encoded) throws JSONException {
            if (encoded == null || encoded.length() > 32768)
                throw new JSONException("Invalid saved interpretation");
            JSONObject json = new JSONObject(encoded);
            String name = json.getString("formatName"), mode = json.getString("selectionMode");
            if (!(mode.equals("automatic") && name.isEmpty()
                    || mode.equals("compatible-name") && !name.isEmpty()))
                throw new JSONException("Interpretation mode/name mismatch");
            try {
                return new Request(name);
            } catch (IllegalArgumentException e) {
                throw new JSONException(e.getMessage());
            }
        }
    }

    static final class Choice {
        final int id;
        final String name;

        Choice(int id, String name) {
            this.id = id;
            this.name = name;
        }

        Request request() {
            return id == 0 ? AUTOMATIC : new Request(name);
        }

        @Override
        public String toString() {
            return name + " • backend selector " + id;
        }
    }

    static final class Catalog {
        final boolean ok;
        final String error;
        final List<Choice> choices;
        private final String encoded;

        private Catalog(String encoded, boolean ok, String error, List<Choice> choices) {
            this.encoded = encoded;
            this.ok = ok;
            this.error = error;
            this.choices = Collections.unmodifiableList(choices);
        }

        static Catalog parse(String encoded) throws JSONException {
            if (encoded == null || encoded.length() > 4 * 1024 * 1024)
                throw new JSONException("Compatible-format catalog exceeds 4 Mi characters");
            JSONObject json = new JSONObject(encoded);
            if (!(json.get("ok") instanceof Boolean))
                throw new JSONException("Invalid format catalog status");
            if (!json.getBoolean("ok"))
                return new Catalog(
                        encoded,
                        false,
                        json.optString("error", "Compatible formats unavailable"),
                        new ArrayList<>());
            JSONArray array = json.getJSONArray("types");
            if (array.length() == 0 || array.length() > 4096)
                throw new JSONException("Format catalog count/limit mismatch");
            List<Choice> choices = new ArrayList<>();
            boolean automatic = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                int id = selector(item.get("id"));
                String name = item.getString("name");
                if (name.isEmpty() || name.length() > 4096 || name.indexOf('\0') >= 0)
                    throw new JSONException("Invalid compatible format label");
                if (id == 0) {
                    if (!name.equals("Automatic"))
                        throw new JSONException("Invalid automatic selector label");
                    automatic = true;
                }
                choices.add(new Choice(id, name));
            }
            if (!automatic) throw new JSONException("Native catalog omitted Automatic");
            return new Catalog(encoded, true, "", choices);
        }

        static Catalog unavailable(String error) throws JSONException {
            return parse(new JSONObject().put("ok", false).put("error", error).toString());
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject(encoded);
        }
    }

    static final class Capture {
        final Request request;
        final Catalog catalog;
        final Integer nativeTypeId;
        final String error, sha256, engineRevision;
        final long size;

        private Capture(
                Request request,
                Catalog catalog,
                Integer type,
                String error,
                String sha256,
                long size,
                String engineRevision) {
            if (request == null
                    || sha256 == null
                    || !sha256.matches("[0-9a-f]{64}")
                    || size < 0
                    || engineRevision == null)
                throw new IllegalArgumentException("Invalid captured format/source context");
            this.request = request;
            this.catalog = catalog;
            nativeTypeId = type;
            this.error = error;
            this.sha256 = sha256;
            this.size = size;
            this.engineRevision = engineRevision;
        }

        static Capture resolve(
                Request request, Catalog catalog, String sha256, long size, String engineRevision) {
            if (request.automatic())
                return new Capture(request, catalog, 0, "", sha256, size, engineRevision);
            Integer chosen = null;
            String error =
                    catalog == null ? "Compatible-format catalog was not collected" : catalog.error;
            if (catalog != null && catalog.ok) {
                for (Choice choice : catalog.choices)
                    if (choice.name.equals(request.formatName) && choice.id != 0) {
                        if (chosen != null && chosen != choice.id)
                            return new Capture(
                                    request,
                                    catalog,
                                    null,
                                    "Requested format name has ambiguous native selectors",
                                    sha256,
                                    size,
                                    engineRevision);
                        chosen = choice.id;
                    }
                if (chosen == null)
                    error =
                            "Requested interpretation is not available for this input: "
                                    + request.formatName;
            }
            return new Capture(
                    request,
                    catalog,
                    chosen,
                    chosen == null ? error : "",
                    sha256,
                    size,
                    engineRevision);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("schema", SCHEMA)
                    .put("schemaVersion", 1)
                    .put("request", request.toJson())
                    .put("resolvedTypeId", nativeTypeId == null ? JSONObject.NULL : nativeTypeId)
                    .put("resolutionError", error)
                    .put("compatibleFormats", catalog == null ? JSONObject.NULL : catalog.toJson())
                    .put("source", new JSONObject().put("sha256", sha256).put("sizeBytes", size))
                    .put("engineRevision", engineRevision)
                    .put(
                            "scope",
                            "This input; explicit names are resolved against its current native"
                                + " compatible-format catalog. Backend IDs are not combo-box"
                                + " indexes or byte offsets. New inputs default to Automatic. Saved"
                                + " flag/tag profiles and batches are separate.");
        }

        /** Attach only to the exact source/engine whose compatible catalog was read. */
        void attach(JSONObject report) throws JSONException {
            JSONObject source = report.getJSONObject("source");
            if (!source.getString("sha256").equals(sha256)
                    || exactLength(source.get("sizeBytes")) != size)
                throw new JSONException("Format selection belongs to a different input identity");
            String engine = report.getJSONObject("provenance").getString("engineRevision");
            if (!engineRevision.isEmpty() && !engine.equals(engineRevision))
                throw new JSONException("Format selection belongs to a different engine revision");
            JSONObject scan = report.getJSONObject("scan");
            if (scan.optBoolean("ok")
                    && (nativeTypeId == null
                            || !scan.has("typeId")
                            || selector(scan.get("typeId")) != nativeTypeId))
                throw new JSONException("Native scan selector differs from captured request");
            report.getJSONObject("options")
                    .put("typeId", nativeTypeId == null ? JSONObject.NULL : nativeTypeId);
            report.put("scanTarget", toJson());
        }
    }

    private static long exactLength(Object value) throws JSONException {
        if (!(value instanceof Number) || !value.toString().matches("(?:0|[1-9][0-9]{0,18})"))
            throw new JSONException("Invalid exact source size");
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            throw new JSONException("Source size exceeds signed 64 bits");
        }
    }

    private static int selector(Object value) throws JSONException {
        if (!(value instanceof Number) || !value.toString().matches("(?:0|[1-9][0-9]{0,9})"))
            throw new JSONException("Invalid native type selector");
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new JSONException("Native type selector exceeds signed 32 bits");
        }
    }

    private ScanTarget() {}
}
