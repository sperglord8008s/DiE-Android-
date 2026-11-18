// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

/** Immutable tool parameters captured independently of mutable Android controls. */
final class ToolRequest {
    final int tool, mode, minimum;
    final long offset;
    final String selector;
    final boolean json, attSyntax;

    ToolRequest(int tool, int mode, long offset, int minimum, String selector, boolean json, boolean attSyntax) {
        if (tool < 0 || tool > 10 || mode < 0 || offset < -1 || minimum < 3 || minimum > 64) throw new IllegalArgumentException("Invalid tool parameters");
        if (selector == null || selector.length() > 4096 || selector.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid selector");
        if (tool == 0 && (mode > 1 || offset >= 4096)) throw new IllegalArgumentException("Invalid structure inventory/slice");
        this.tool = tool; this.mode = mode; this.offset = offset; this.minimum = minimum;
        this.selector = selector; this.json = (tool >= 0 && tool <= 6) || tool == 10 || json; this.attSyntax = attSyntax;
    }
    boolean records() { return tool >= 0 && tool <= 6 || tool == 10; }
    boolean sameAs(ToolRequest other) {
        return other != null && tool == other.tool && mode == other.mode && offset == other.offset && minimum == other.minimum
            && selector.equals(other.selector) && json == other.json && attSyntax == other.attSyntax;
    }
    JSONObject toJson() throws JSONException {
        return new JSONObject().put("toolId", tool).put("mode", mode).put("physicalOffset", tool == 0 ? -1 : offset).put("machOSliceIndex", tool == 0 ? offset : JSONObject.NULL)
            .put("minimumStringLength", minimum).put("selector", selector).put("nativeJson", json).put("attSyntax", attSyntax);
    }
    static ToolRequest fromJson(String value) throws JSONException {
        JSONObject json = new JSONObject(value);
        return new ToolRequest(json.getInt("toolId"), json.getInt("mode"), json.getInt("toolId") == 0 && json.has("machOSliceIndex") ? json.getLong("machOSliceIndex") : json.getLong("physicalOffset"),
            json.getInt("minimumStringLength"), json.getString("selector"), json.getBoolean("nativeJson"), json.getBoolean("attSyntax"));
    }
}
