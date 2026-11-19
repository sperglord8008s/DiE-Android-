// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.JSONObject;

import java.io.IOException;

/** Actual serialized CDiE/facts/whole-input inspection, using one captured database snapshot. */
final class DesktopBatchAnalyzer implements BatchScanSession.Analyzer {
    private final EngineDatabase database;
    private final ScanOptions options;
    private final ScanTarget.Request interpretation;

    DesktopBatchAnalyzer(EngineDatabase database, ScanOptions options) {
        this(database, options, ScanTarget.AUTOMATIC);
    }

    DesktopBatchAnalyzer(
            EngineDatabase database, ScanOptions options, ScanTarget.Request interpretation) {
        this.database = database;
        this.options = options;
        this.interpretation = interpretation;
    }

    @Override
    public JSONObject analyze(InputSnapshot input, String name, BatchScanSession.Control stop)
            throws Exception {
        long start = System.nanoTime();
        Inspector.Result inspection = Inspector.scan(input.file, options.minimumStringLength, stop);
        if (inspection.size != input.size || !inspection.sha256.equals(input.sha256))
            throw new IOException("Inspection identity differs from acquired input");
        Engine.Cancellation cancellation = stop.beginScan();
        try {
            EvidenceRows.check(stop);
            ScanTarget.Catalog catalog;
            try {
                catalog =
                        ScanTarget.Catalog.parse(
                                Engine.desktop(
                                        input.file.getAbsolutePath(),
                                        database.main.getAbsolutePath(),
                                        database.custom.getAbsolutePath(),
                                        0,
                                        options.flags,
                                        0,
                                        options.includeTags,
                                        options.excludeTags,
                                        "",
                                        ""));
            } catch (Exception failure) {
                catalog =
                        ScanTarget.Catalog.unavailable(
                                "Compatible-format catalog failed: " + failure);
            }
            EvidenceRows.check(stop);
            ScanTarget.Capture target =
                    ScanTarget.Capture.resolve(
                            interpretation,
                            catalog,
                            input.sha256,
                            input.size,
                            database.engineRevision);
            ScanEvidence evidence;
            JSONObject facts = null;
            if (target.nativeTypeId == null) {
                evidence =
                        ScanEvidence.parse(
                                new JSONObject()
                                        .put("ok", false)
                                        .put("error", target.error)
                                        .toString());
            } else {
                int selected = target.nativeTypeId;
                facts =
                        new JSONObject(
                                Engine.desktop(
                                        input.file.getAbsolutePath(),
                                        database.main.getAbsolutePath(),
                                        database.custom.getAbsolutePath(),
                                        5,
                                        options.flags,
                                        selected,
                                        options.includeTags,
                                        options.excludeTags,
                                        "",
                                        ""));
                EvidenceRows.check(stop);
                evidence =
                        ScanEvidence.parse(
                                Engine.desktop(
                                        input.file.getAbsolutePath(),
                                        database.main.getAbsolutePath(),
                                        database.custom.getAbsolutePath(),
                                        1,
                                        options.flags,
                                        selected,
                                        options.includeTags,
                                        options.excludeTags,
                                        "",
                                        "",
                                        cancellation));
            }
            EvidenceRows.check(stop);
            JSONObject report =
                    ScanReport.create(
                            name,
                            inspection,
                            options,
                            evidence,
                            facts,
                            database.engineRevision,
                            database.signatureRevision,
                            database.customDigest,
                            (System.nanoTime() - start) / 1_000_000,
                            "Desktop batch: native signature tree, raw native facts, complete input"
                                + " identity and bounded generic previews. A signature describes a"
                                + " static observation; inspect the exact rule and diagnostics"
                                + " before interpreting behavior.",
                            "Android-specific analysis was not run in this desktop batch.",
                            evidence.ok ? "" : evidence.error);
            target.attach(report);
            return report;
        } finally {
            stop.endScan(cancellation);
        }
    }
}
