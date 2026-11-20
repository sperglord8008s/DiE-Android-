// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Real upstream PDF file checks, exact payload exports and explicit malformed/budget scope. */
public final class PhotoRecPdfTest {
    private interface Mutation {
        void apply(JSONObject report) throws Exception;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String scan(File source) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(source, "r")) {
            Field descriptor = FileDescriptor.class.getDeclaredField("fd");
            descriptor.setAccessible(true);
            return PhotoRecEngine.scanFd(descriptor.getInt(file.getFD()));
        }
    }

    private static JSONObject pdfRow(JSONObject report, long at) throws Exception {
        PhotoRecReport decoded = new PhotoRecReport(report);
        for (int i = 0; i < report.getInt("candidateCount"); i++) {
            JSONObject row = decoded.row(i);
            if (row.getString("name").equals("pdf") && row.getLong("physicalOffset") == at)
                return row;
        }
        throw new AssertionError("Missing PDF candidate at " + at);
    }

    public static void main(String[] args) throws Exception {
        require(PhotoRecEngine.AVAILABLE, PhotoRecEngine.ERROR);
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        byte[] payload = MetadataFixtureFactory.pdf();
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        blob.write(new byte[37]);
        blob.write(payload);
        blob.write(new byte[97]);
        File source = root.resolve("document-disguised-as-image.jpg").toFile();
        Files.write(source.toPath(), blob.toByteArray());
        JSONObject report = new JSONObject(scan(source));
        require(report.getBoolean("ok"), report.toString());
        JSONObject row = pdfRow(report, 37);
        require(
                row.getBoolean("fileCheckAttempted") && row.getBoolean("fileCheckExecuted"),
                "Original PDF file check not executed");
        require(
                row.getLong("reportedSizeBytes") == payload.length,
                "PDF boundary differs: " + row.toString());
        require(
                row.isNull("fileCheckFailureReason")
                        && row.getLong("fileCheckSourceBytesProcessed")
                                <= PhotoRecFileChecks.MAX_READ,
                "PDF stream read failure/work scope lost");
        require(!row.getBoolean("renameCheckExecuted"), "Rename callback executed");
        Files.write(root.resolve("expected.pdf"), payload);
        Files.writeString(root.resolve("native-pdf-report.json"), report.toString());
        File directory = root.resolve("inputs").toFile();
        try (GallerizeSession gallery =
                GallerizeSession.open(
                        source,
                        directory,
                        "mislabeled",
                        null,
                        source.length(),
                        null,
                        (file, stop) -> scan(file),
                        () -> false)) {
            ContentMap.Card card =
                    gallery.digest(() -> false).cards.stream()
                            .filter(
                                    c ->
                                            c.format.equals("pdf")
                                                    && c.offset == 37
                                                    && c.length != null
                                                    && c.length == payload.length)
                            .findFirst()
                            .orElseThrow(
                                    () -> new AssertionError("Actual PDF missing from gallery"));
            require(card.kind == ContentMap.Kind.DOCUMENT, "PDF not labeled document");
            PreparedExport saved =
                    RecoveredFiles.file(
                            gallery.input, card, root.resolve("exports").toFile(), () -> false);
            require(
                    saved.mime.equals("application/pdf") && saved.name.endsWith(".pdf"),
                    "PDF file type lost");
            require(
                    Arrays.equals(payload, Files.readAllBytes(saved.file.toPath())),
                    "Exported PDF bytes changed");
            Files.copy(saved.file.toPath(), root.resolve("recovered.pdf"));
            saved.discard();
        }
        // A report must not authorize arbitrary callbacks or a failed/missing PDF attempt.
        List<Mutation> mutations =
                List.of(
                        x -> x.put("fileCheckCandidatesExecuted", 0),
                        x -> pdfRow(x, 37).put("fileCheckMode", "arbitrary-full-check"),
                        x -> pdfRow(x, 37).put("fileCheckAttempted", false),
                        x -> pdfRow(x, 37).put("fileCheckFailureReason", "work-limit"));
        final String validReport = report.toString();
        for (var mutation : mutations) {
            JSONObject altered = new JSONObject(validReport);
            mutation.apply(altered);
            try (PhotoRecBackend backend =
                    new PhotoRecBackend(
                            source, directory, "forged", (file, stop) -> altered.toString())) {
                try {
                    backend.open("all", () -> false);
                    throw new AssertionError("Changed PDF check scope accepted");
                } catch (IOException expected) {
                    /* Refusal is required before a range becomes exportable. */
                }
            }
        }
        // Removing the footer must not turn the parsing tail or declared input size into a file.
        Files.write(source.toPath(), Arrays.copyOf(payload, payload.length - 7));
        row = pdfRow(new JSONObject(scan(source)), 0);
        require(
                row.getBoolean("fileCheckExecuted") && row.isNull("reportedSizeBytes"),
                "Truncated PDF got a recovered boundary");
        // A large footerless tail exercises the file-check read cap without an input-sized buffer.
        try (OutputStream out = new FileOutputStream(source)) {
            out.write(Arrays.copyOf(payload, payload.length - 7));
            byte[] padding = new byte[65536];
            Arrays.fill(padding, (byte) 'x');
            for (int i = 0; i < 160; i++) out.write(padding);
        }
        report = new JSONObject(scan(source));
        row = pdfRow(report, 0);
        require(
                row.isNull("reportedSizeBytes") && !row.isNull("fileCheckFailureReason"),
                "Read-budget-limited PDF acquired a boundary");
        require(
                row.getLong("fileCheckSourceBytesProcessed") <= PhotoRecFileChecks.MAX_READ,
                "PDF read budget exceeded");
        require(report.getInt("fileCheckCandidatesLimited") > 0, "Partial PDF scope hidden");
        try (PhotoRecBackend backend =
                new PhotoRecBackend(source, directory, "large", (file, stop) -> scan(file))) {
            require(
                    backend.open("all", () -> false).size() > 0,
                    "Budget-limited raw result rejected/dropped");
        }
        System.out.println(
                "Actual upstream PDF footer recovery, exact gallery/application-pdf export,"
                        + " truncated refusal and bounded read-only file-check work passed.");
    }
}
