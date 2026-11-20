// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.JSONObject;

import java.io.IOException;

/** Scope validation for the single reviewed upstream PDF file-check module. */
final class PhotoRecFileChecks {
    static final long MAX_READ = 8L * 1024 * 1024;

    static boolean enabled(JSONObject report) {
        return report.optString("adapter").equals("diemobile-photorec-4");
    }

    static void validateReport(JSONObject report, int attempted, int executed, int limited)
            throws Exception {
        if (!enabled(report)) {
            if (report.getBoolean("fileCheckCallbacksExecuted")
                    || attempted != 0
                    || executed != 0
                    || limited != 0)
                throw new IOException("Legacy PhotoRec capture cannot execute file checks");
            return;
        }
        if (!report.getString("fileCheckCallbackScope").equals("pdf-only/read-only-relative-stdio")
                || report.getLong("maximumCandidateFileReadBytes") != MAX_READ
                || report.getLong("fileCheckReadBufferBytes") != 65536
                || report.getInt("fileCheckCandidatesAttempted") != attempted
                || report.getInt("fileCheckCandidatesExecuted") != executed
                || report.getInt("fileCheckCandidatesLimited") != limited
                || report.getBoolean("fileCheckCallbacksExecuted") != (executed > 0))
            throw new IOException(
                    "PhotoRec PDF file-check scope or execution counts differ from its rows");
    }

    static void validateRow(JSONObject row, boolean enabled) throws Exception {
        boolean attempted = row.optBoolean("fileCheckAttempted");
        boolean executed = row.getBoolean("fileCheckExecuted");
        boolean failed = row.has("fileCheckFailureReason") && !row.isNull("fileCheckFailureReason");
        boolean expected =
                enabled
                        && row.getString("name").equals("pdf")
                        && row.getBoolean("headerAccepted")
                        && row.getBoolean("fileCheckAvailable");
        if (enabled && expected != attempted)
            throw new IOException(
                    "Reviewed PDF file-check attempt was omitted or added outside its scope");
        if (!attempted && !executed && !failed) return;
        if (!enabled
                || !attempted
                || !row.getString("name").equals("pdf")
                || !row.getBoolean("headerAccepted")
                || !row.getBoolean("fileCheckAvailable")
                || executed
                        && !row.getString("fileCheckMode")
                                .equals("upstream-pdf/read-only-relative-stdio")
                || !executed && !row.getString("fileCheckMode").equals("not-run")
                || row.getLong("fileCheckSourceBytesProcessed") < 0
                || row.getLong("fileCheckSourceBytesProcessed") > MAX_READ)
            throw new IOException("PhotoRec file check escaped its reviewed PDF/read-only scope");
        if ((!executed || failed) && !row.isNull("reportedSizeBytes"))
            throw new IOException(
                    "Failed/unexecuted PDF file check cannot establish a recovered extent");
    }

    static boolean supportsExtent(JSONObject row, long count) throws Exception {
        return row.getBoolean("fileCheckExecuted")
                && row.isNull("fileCheckFailureReason")
                && row.getLong("fileCheckSizeBytes") == count;
    }
}
