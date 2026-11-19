// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Upstream property mechanisms/units/limitations, available without a network. */
final class MpvReference {
    private static JSONObject reference;
    private static synchronized JSONObject reference() throws Exception {
        if (reference == null) {
            try (InputStream input = MpvReference.class.getResourceAsStream("/mpv-properties.json")) {
                if (input == null) throw new IOException("Bundled MPV property reference is missing");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] block = new byte[8192];
                int count;
                while ((count = input.read(block)) != -1) {
                    if (count > 2*1024*1024-bytes.size()) throw new IOException("MPV reference exceeds 2 MiB");
                    bytes.write(block,0,count);
                }
                reference = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                if (!reference.getString("sourceRevision").equals(MpvDocument.SOURCE)) throw new IOException("MPV reference revision differs from the Android engine");
            }
        }
        return reference;
    }
    static JSONObject context(String property) throws Exception {
        JSONObject source = reference();
        String description = source.getJSONObject("properties").optString(property,"");
        return new JSONObject().put("sourceRevision",source.getString("sourceRevision"))
            .put("sourcePath",source.getString("sourcePath")).put("sourceSha256",source.getString("sourceSha256"))
            .put("license",source.getString("license")).put("property",property)
            .put("documentationAvailable",!description.isEmpty()).put("upstreamReferenceRst",description)
            .put("interpretation","This manual describes the pinned Android engine. Runtime availability/errors and exact typed nodes are authoritative; an independently installed host test runtime can differ.");
    }
    static String brief(String property) throws Exception {
        String text = context(property).getString("upstreamReferenceRst");
        int end = text.indexOf("\n\n");
        if (end >= 0) text = text.substring(0,end);
        return text.replaceAll("\\s+"," ").trim();
    }
    private MpvReference() {}
}
