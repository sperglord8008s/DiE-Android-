// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

/** Versioned export envelope. The input digest identifies exactly which bytes were analyzed. */
final class ScanReport {
    private ScanReport() {}

    static JSONObject create(String name, Inspector.Result input, ScanOptions options,
                             ScanEvidence evidence, JSONObject facts, String engineRevision,
                             String signatureRevision, long elapsedMs, String guide, String apk, String engineError) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("schema", "org.diemobile.scan-report").put("schemaVersion", 1);
        result.put("createdAt", java.time.Instant.now().toString());
        result.put("source", new JSONObject().put("name", name).put("sizeBytes", input.size)
            .put("sha256", input.sha256).put("headerIdentification", input.type));
        JSONArray enabled = new JSONArray();
        for (int i = 0; i < ScanOptions.LABELS.length; i++) if ((options.flags & (1 << i)) != 0) enabled.put(ScanOptions.LABELS[i]);
        result.put("options", new JSONObject().put("flags", options.flags).put("enabled", enabled)
            .put("includeTags", options.includeTags).put("excludeTags", options.excludeTags)
            .put("typeId", 0).put("minimumStringLength", options.minimumStringLength));
        result.put("provenance", new JSONObject().put("engine", "CDiE desktop backend")
            .put("engineRevision", engineRevision).put("signatureRevision", signatureRevision)
            .put("elapsedMs", elapsedMs).put("networkUsed", false));
        result.put("scan", evidence == null ? new JSONObject().put("ok", false).put("error", "No native evidence collected") : evidence.toJson());
        result.put("engineError", engineError);
        result.put("facts", facts == null ? JSONObject.NULL : facts);
        JSONArray blocks = new JSONArray(); for (double value : input.blocks) blocks.put(value);
        result.put("inspection", new JSONObject().put("entropyBitsPerByte", input.entropy)
            .put("blockEntropy", blocks).put("asciiStringsPreview", input.strings).put("hexPreview", input.hex));
        result.put("explanations", guide).put("apkAnalysis", apk);
        result.put("limitations", new JSONArray().put("Static observations do not establish execution or safety.")
            .put("CDiE differs from Qt DiE; full signature and heuristic parity is unverified.")
            .put("Native parsing runs in the app process; worker isolation is pending.")
            .put("Archive expansion may skip unsupported or resource-limited members upstream; empty results are not exhaustive coverage.")
            .put("ASCII, hex and block-entropy previews are bounded; native tools expose further analysis."));
        return result;
    }
}
