// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

/** Immutable desktop-backend options, captured before scheduling a scan. */
final class ScanOptions {
    static final int DEEP = 1, HEURISTIC = 2, ALL_TYPES = 4, RECURSIVE = 8;
    static final int VERBOSE = 16, AGGRESSIVE = 32, OVERLAYS = 64, RESOURCES = 128;
    static final int ARCHIVES = 256, PROFILING = 512, HIDE_UNKNOWN = 1024;
    static final int CUSTOM_DB = 2048, SORT = 4096, CACHE = 8192;
    static final int BASIC_MASK = DEEP | HEURISTIC | ALL_TYPES | RECURSIVE | AGGRESSIVE;
    static final int DEFAULT_FLAGS = DEEP | HEURISTIC | VERBOSE | OVERLAYS | SORT;
    static final String[] LABELS = {"Deep", "Heuristic", "All formats", "Recursive", "Verbose",
        "Aggressive", "Overlays", "Resources", "Archives", "Profiling", "Hide unknown",
        "Custom DB", "Sort", "Result cache"};

    final int flags, minimumStringLength;
    final String includeTags, excludeTags;

    ScanOptions(int flags, int minimumStringLength, String includeTags, String excludeTags) {
        if ((flags & ~16383) != 0 || flags < 0) throw new IllegalArgumentException("Unsupported scan flags");
        if (minimumStringLength < 3 || minimumStringLength > 64) throw new IllegalArgumentException("String minimum must be 3–64");
        this.flags = flags;
        this.minimumStringLength = minimumStringLength;
        this.includeTags = tags(includeTags);
        this.excludeTags = tags(excludeTags);
    }

    private static String tags(String value) {
        if (value == null) return "";
        if (value.length() > 4096 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Tag filter is invalid or exceeds 4096 characters");
        return value.trim();
    }

    ScanOptions withBasicFlags(int basic, int minimum) {
        return new ScanOptions((flags & ~BASIC_MASK) | (basic & BASIC_MASK), minimum, includeTags, excludeTags);
    }

    boolean sameAs(ScanOptions other) {
        return other != null && flags == other.flags && minimumStringLength == other.minimumStringLength
            && includeTags.equals(other.includeTags) && excludeTags.equals(other.excludeTags);
    }
}
