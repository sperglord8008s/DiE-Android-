// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

/** Tool exports retain the complete model and the parameters that produced it. */
final class ToolReport {
    private ToolReport() {}
    static String encode(String name, String sha256, long size, String revision, ToolRequest request,
                         NativeTable table, String raw, long elapsedMs, String filter, int page) throws JSONException {
        JSONObject header = new JSONObject().put("schema", "org.diemobile.tool-report").put("schemaVersion", 1)
            .put("createdAt", java.time.Instant.now().toString())
            .put("input", new JSONObject().put("name", name).put("sizeBytes", size)
                .put("sha256", sha256 != null && sha256.matches("[0-9a-fA-F]{64}") ? sha256 : JSONObject.NULL)
                .put("identitySource", sha256 != null && sha256.matches("[0-9a-fA-F]{64}")
                    ? request.tool == 7 ? "whole-file hash acquisition, independently checked by native SHA-256" : "parent input inspection" : "not collected"))
            .put("request", request.toJson()).put("engineRevision", revision).put("signatureDatabaseUsed", false)
            .put("elapsedMs", elapsedMs).put("view", new JSONObject().put("filter", filter).put("page", page)
                .put("exportScope", "all returned rows; UI filter/page does not reduce the export"))
            .put("rawReport", table == null ? raw : JSONObject.NULL);
        // The table's original JSON is already validated; append it verbatim to
        // avoid allocating a second full DOM during export of large tables.
        String encoded = header.toString();
        return encoded.substring(0, encoded.length() - 1) + ",\"nativeRecords\":" + (table == null ? "null" : table.encoded()) + "}";
    }
    static String range(String name, String sha256, RangeAnalysis.Result result) throws JSONException {
        return new JSONObject().put("schema", "org.diemobile.range-report").put("schemaVersion", 1)
            .put("inputName", name).put("inputSha256", sha256 != null && sha256.matches("[0-9a-fA-F]{64}") ? sha256 : JSONObject.NULL)
            .put("fileSizeBytes", result.range.fileSize).put("physicalOffset", result.range.offset)
            .put("lengthBytes", result.range.length).put("rangeSha256", result.sha256).put("rangeCrc32", result.crc32)
            .put("entropyBitsPerByte", result.entropy)
            .put("interpretation", "Stored physical bytes only; high entropy does not establish encryption, packing or malicious behavior.").toString();
    }
}
