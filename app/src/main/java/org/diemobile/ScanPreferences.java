// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.content.Context;
import android.content.SharedPreferences;

/** One settings store for the primary scan and the desktop laboratory. */
final class ScanPreferences {
    private static final String STORE = "scan_configuration_v1";
    private ScanPreferences() {}

    static synchronized ScanOptions load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        if (!prefs.contains("flags")) {
            SharedPreferences lab = context.getSharedPreferences("desktop_scan", Context.MODE_PRIVATE);
            SharedPreferences legacy = context.getSharedPreferences("scan_options", Context.MODE_PRIVATE);
            int flags = ScanOptions.DEFAULT_FLAGS;
            if (lab.contains("flags")) flags = lab.getInt("flags", flags);
            else {
                flags = new ScanOptions(flags, 4, "", "").withBasicFlags(
                    (legacy.getBoolean("deep", true) ? ScanOptions.DEEP : 0) |
                    (legacy.getBoolean("heuristic", true) ? ScanOptions.HEURISTIC : 0) |
                    (legacy.getBoolean("all", false) ? ScanOptions.ALL_TYPES : 0) |
                    (legacy.getBoolean("recursive", false) ? ScanOptions.RECURSIVE : 0) |
                    (legacy.getBoolean("aggressive", false) ? ScanOptions.AGGRESSIVE : 0), 4).flags;
            }
            save(context, new ScanOptions(flags & 16383, Math.max(3, Math.min(64, legacy.getInt("min", 4))),
                lab.getString("include", ""), lab.getString("exclude", "")));
        }
        return new ScanOptions(prefs.getInt("flags", ScanOptions.DEFAULT_FLAGS), prefs.getInt("minimum", 4),
            prefs.getString("include", ""), prefs.getString("exclude", ""));
    }

    static synchronized void save(Context context, ScanOptions options) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putInt("flags", options.flags)
            .putInt("minimum", options.minimumStringLength).putString("include", options.includeTags)
            .putString("exclude", options.excludeTags).apply();
    }
}
