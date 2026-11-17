// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.util.ArrayDeque;

/** Navigation stores parameters rather than retaining large native table models. */
final class ToolHistory {
    static final int LIMIT = 64;
    static final class Location {
        final ToolRequest request, controls;
        final String filter;
        final int page;
        Location(ToolRequest request, String filter, int page) {
            this(request, request, filter, page);
        }
        Location(ToolRequest request, ToolRequest controls, String filter, int page) {
            if (page < 0) throw new IllegalArgumentException("Negative page");
            if (request.tool != controls.tool) throw new IllegalArgumentException("Displayed and draft tools differ");
            this.request = request; this.controls = controls; this.filter = filter; this.page = page;
        }
    }
    private final ArrayDeque<Location> locations = new ArrayDeque<>();
    /** True means the oldest location expired; the UI must disclose the limit. */
    boolean push(Location location) {
        boolean expired = locations.size() == LIMIT;
        if (expired) locations.removeFirst();
        locations.addLast(location); return expired;
    }
    Location back() { return locations.pollLast(); }
    boolean hasBack() { return !locations.isEmpty(); }
}
