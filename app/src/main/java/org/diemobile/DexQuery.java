// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.util.Arrays;
import org.json.*;

/** A small, stable location; it never retains a large decoded table in Back state. */
final class DexQuery {
    static final String[] ROOT_KINDS = {"classes", "method-refs", "field-refs", "types", "strings", "protos", "method-handles", "call-sites", "map", "header"};
    static final String[] ROOT_TITLES = {"Class definitions", "Method references", "Field references", "Type descriptors", "String pool", "Method prototypes", "Method handles", "Call sites", "DEX map", "Raw DEX header"};
    final int entry, owner, member, referenceType, referenceIndex;
    final String kind;
    DexQuery(int entry, String kind, int owner, int member, int referenceType, int referenceIndex) {
        if (entry < 0 || entry >= DexDocument.MAX_ENTRIES || owner < -1 || member < -1 || referenceType < -1 || referenceIndex < -1
            || !(Arrays.asList(ROOT_KINDS).contains(kind) || Arrays.asList("fields", "methods", "code", "debug", "tries", "array", "switch", "xrefs", "classified-strings", "classifier-rules").contains(kind))) {
            throw new IllegalArgumentException("Invalid DEX query");
        }
        this.entry = entry; this.kind = kind; this.owner = owner; this.member = member;
        this.referenceType = referenceType; this.referenceIndex = referenceIndex;
    }
    static DexQuery root(int entry, String kind) { return new DexQuery(entry, kind, -1, -1, -1, -1); }
    DexQuery child(String kind, int owner, int member) { return new DexQuery(entry, kind, owner, member, -1, -1); }
    DexQuery references(int type, int index) { return new DexQuery(entry, "xrefs", -1, -1, type, index); }
    JSONObject json() throws JSONException {
        return new JSONObject().put("entry", entry).put("kind", kind).put("owner", owner).put("member", member)
            .put("referenceType", referenceType).put("referenceIndex", referenceIndex);
    }
    static DexQuery parse(String text) throws JSONException {
        JSONObject value = new JSONObject(text);
        return new DexQuery(value.getInt("entry"), value.getString("kind"), value.getInt("owner"), value.getInt("member"),
            value.getInt("referenceType"), value.getInt("referenceIndex"));
    }
}
