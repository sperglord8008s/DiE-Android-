// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.JSONObject;

import java.io.IOException;

/** Actual serialized CDiE/facts/whole-input inspection, using one captured database snapshot. */
final class DesktopBatchAnalyzer implements BatchScanSession.Analyzer {
    private final EngineDatabase database;
    private final ScanOptions options;

    DesktopBatchAnalyzer(EngineDatabase database, ScanOptions options) {
        this.database = database;
        this.options = options;
    }

    @Override
    public JSONObject analyze(InputSnapshot input, String name, BatchScanSession.Control stop)
            throws Exception {
        long start = System.nanoTime();
        Inspector.Result inspection = Inspector.scan(input.file, options.minimumStringLength, stop);
        if (inspection.size != input.size || !inspection.sha256.equals(input.sha256))
            throw new IOException("Inspection identity differs from acquired input");
        Engine.Cancellation cancellation = stop.beginScan();
        ScanEvidence evidence;
        try {
            evidence =
                    ScanEvidence.parse(
                            Engine.desktop(
                                    input.file.getAbsolutePath(),
                                    database.main.getAbsolutePath(),
                                    database.custom.getAbsolutePath(),
                                    1,
                                    options.flags,
                                    0,
                                    options.includeTags,
                                    options.excludeTags,
                                    "",
                                    "",
                                    cancellation));
        } finally {
            stop.endScan(cancellation);
        }
        EvidenceRows.check(stop);
        JSONObject facts =
                new JSONObject(
                        Engine.desktop(
                                input.file.getAbsolutePath(),
                                database.main.getAbsolutePath(),
                                database.custom.getAbsolutePath(),
                                5,
                                options.flags,
                                0,
                                options.includeTags,
                                options.excludeTags,
                                "",
                                ""));
        EvidenceRows.check(stop);
        return ScanReport.create(
                name,
                inspection,
                options,
                evidence,
                facts,
                database.engineRevision,
                database.signatureRevision,
                database.customDigest,
                (System.nanoTime() - start) / 1_000_000,
                "Desktop batch: native signature tree, raw native facts, complete input identity"
                    + " and bounded generic previews. A signature describes a static observation;"
                    + " inspect the exact rule and diagnostics before interpreting behavior.",
                "Android-specific analysis was not run in this desktop batch.",
                evidence.ok ? "" : evidence.error);
    }
}
