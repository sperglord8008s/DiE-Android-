// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Real bytes/parsers, conservative graph semantics, ownership and complete-record contracts. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
public final class PackageSetDocumentTest {
    private Path temporary;
    private static final BooleanSupplier RUN = () -> false;

    @Before
    public void directory() throws Exception {
        temporary = Files.createTempDirectory("die-package-document");
    }

    @After
    public void cleanup() throws Exception {
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    private static BinaryRunner runner() {
        return (file, stop) -> {
            try (InputStream input = new FileInputStream(file)) {
                return AndroidResourceProbe.scan(input, file.length(), stop);
            }
        };
    }

    private File apk(String name, byte[] manifest) throws Exception {
        File apk = temporary.resolve(name).toFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            CRC32 crc = new CRC32();
            crc.update(manifest);
            ZipEntry entry = new ZipEntry("AndroidManifest.xml");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(manifest.length);
            entry.setCompressedSize(manifest.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(manifest);
            zip.closeEntry();
        }
        return apk;
    }

    private static PackageSetDocument.Input input(File file) {
        return new PackageSetDocument.Input(file, file.getName(), null, -1);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++)
                if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    @Test
    public void actualCrcFailureIsNotPromotedEvenWhenTheModifiedManifestStillParses()
            throws Exception {
        byte[] original = PackageManifestTest.wire(0);
        File file = apk("bad-crc.apk", original);
        byte[] changed = original.clone();
        AndroidResourceDocument before =
                new AndroidResourceDocument(
                        write("manifest.xml", original),
                        "AndroidManifest.xml",
                        sha(original),
                        AndroidResourceProbe.scan(
                                new ByteArrayInputStream(original), original.length, RUN),
                        RUN);
        int at =
                (int)
                                before.tables
                                        .getJSONArray("attributes")
                                        .getJSONObject(2)
                                        .getLong("physicalOffset")
                        + 16;
        changed[at] ^= 1;
        AndroidResourceDocument after =
                new AndroidResourceDocument(
                        write("changed.xml", changed),
                        "AndroidManifest.xml",
                        sha(changed),
                        AndroidResourceProbe.scan(
                                new ByteArrayInputStream(changed), changed.length, RUN),
                        RUN);
        assertNotEquals(
                new PackageManifest(before, RUN).versionCode64(),
                new PackageManifest(after, RUN).versionCode64());
        byte[] encoded = Files.readAllBytes(file.toPath());
        int start = indexOf(encoded, original);
        assertTrue(start >= 0);
        encoded[start + at] ^= 1;
        Files.write(file.toPath(), encoded);
        AtomicInteger calls = new AtomicInteger();
        BinaryRunner never =
                (path, stop) -> {
                    calls.incrementAndGet();
                    return runner().scan(path, stop);
                };
        File owned = temporary.resolve("crc-owned").toFile();
        try (PackageSetDocument document =
                PackageSetDocument.read(List.of(input(file)), owned, never, RUN)) {
            PackageSetDocument.Source source = document.sources.get(0);
            assertTrue(source.identityStable);
            assertNull(source.manifest);
            assertNull(source.xml);
            assertEquals(0, calls.get());
            assertTrue(source.errors.toString(), source.errors.toString().contains("CRC"));
            assertTrue(
                    source.identity()
                                    .getJSONObject("declaredManifestMember")
                                    .getLong("crc32Unsigned")
                            >= 0);
            assertFalse(
                    new PackageSetGraph(document, RUN)
                            .tables
                            .getJSONArray("summary")
                            .getJSONObject(0)
                            .getBoolean("declarationAcquisitionComplete"));
        }
        assertEquals(0, Objects.requireNonNull(owned.list()).length);
    }

    private File write(String name, byte[] bytes) throws Exception {
        File file = temporary.resolve(name).toFile();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static String sha(byte[] bytes) throws Exception {
        return Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    public void observedCyclesConfigurationKindAndEmptyTargetsAreNotNormalizedIntoValidPackages()
            throws Exception {
        File notes = apk("notes.apk", PackageManifestTest.wire(0)),
                core = apk("core.apk", PackageManifestTest.wire(0, true));
        try (PackageSetDocument document =
                PackageSetDocument.read(
                        List.of(input(notes), input(core)),
                        temporary.resolve("cycle").toFile(),
                        runner(),
                        RUN)) {
            JSONArray diagnostics =
                    new PackageSetGraph(document, RUN).tables.getJSONArray("diagnostics");
            assertTrue(diagnostics.toString().contains("conditional-first-parent-cycle"));
        }
        File config = apk("config.apk", PackageManifestTest.wire(7)),
                nonfeature = apk("nonfeature.apk", PackageManifestTest.wire(7, true));
        try (PackageSetDocument document =
                PackageSetDocument.read(
                        List.of(input(config), input(nonfeature)),
                        temporary.resolve("config").toFile(),
                        runner(),
                        RUN)) {
            assertTrue(
                    new PackageSetGraph(document, RUN)
                            .tables
                            .getJSONArray("diagnostics")
                            .toString()
                            .contains("configuration-target-not-known-feature"));
        }
        File empty = apk("empty-target.apk", PackageManifestTest.wire(8)),
                base = apk("base.apk", PackageManifestTest.wire(4));
        try (PackageSetDocument document =
                PackageSetDocument.read(
                        List.of(input(empty), input(base)),
                        temporary.resolve("empty").toFile(),
                        runner(),
                        RUN)) {
            JSONArray edges =
                    new PackageSetGraph(document, RUN).tables.getJSONArray("dependencies");
            boolean found = false;
            for (int i = 0; i < edges.length(); i++) {
                JSONObject edge = edges.getJSONObject(i);
                if (edge.getString("kind").equals("configuration-target")) {
                    assertEquals("", edge.getString("targetSplitName"));
                    assertEquals(0, edge.getJSONArray("targetSourceIndices").length());
                    found = true;
                }
            }
            assertTrue(found);
        }
    }

    @Test
    public void reservationFailuresCancellationAndRememberedIdentityDoNotLeakOrInventScopes()
            throws Exception {
        File valid = apk("valid.apk", PackageManifestTest.wire(0)),
                first = write("first-invalid.apk", new byte[16384]),
                second = write("second-invalid.apk", new byte[16384]);
        File reserved = temporary.resolve("reserved").toFile();
        try (PackageSetDocument document =
                PackageSetDocument.read(
                        List.of(input(first), input(second), input(valid)),
                        reserved,
                        runner(),
                        RUN,
                        32768)) {
            assertEquals(32768, document.acquisition().getLong("reservedApkBytes"));
            assertNotNull(document.sources.get(0).apk);
            assertNotNull(document.sources.get(1).apk);
            assertNull(document.sources.get(2).apk);
            assertTrue(document.sources.get(2).errors.toString().contains("reservation"));
        }
        assertEquals(0, Objects.requireNonNull(reserved.list()).length);
        File huge = temporary.resolve("huge.apk").toFile();
        try (RandomAccessFile output = new RandomAccessFile(huge, "rw")) {
            output.setLength(PackageSetDocument.MAX_APK + 1);
        }
        try (PackageSetDocument document =
                PackageSetDocument.read(
                        List.of(input(huge)),
                        temporary.resolve("huge-owned").toFile(),
                        runner(),
                        RUN)) {
            assertNull(document.sources.get(0).apk);
            assertEquals(
                    PackageSetDocument.MAX_APK + 1,
                    document.sources.get(0).declaredBytes.longValue());
        }
        boolean rejected = false;
        try {
            PackageSetDocument.read(
                    Collections.nCopies(65, input(valid)),
                    temporary.resolve("too-many").toFile(),
                    runner(),
                    RUN);
        } catch (IOException expected) {
            rejected = true;
        }
        assertTrue(rejected);
        AtomicBoolean stop = new AtomicBoolean();
        File cancelled = temporary.resolve("cancelled").toFile();
        BinaryRunner late =
                (file, flag) -> {
                    String result = runner().scan(file, flag);
                    stop.set(true);
                    return result;
                };
        rejected = false;
        try {
            PackageSetDocument.read(List.of(input(valid)), cancelled, late, stop::get);
        } catch (InterruptedIOException expected) {
            rejected = true;
        }
        assertTrue(rejected);
        assertEquals(0, Objects.requireNonNull(cancelled.list()).length);
        byte[] bytes = Files.readAllBytes(valid.toPath());
        try (PackageSetDocument document =
                PackageSetDocument.read(
                        List.of(
                                new PackageSetDocument.Input(
                                        valid, "changed.apk", "0".repeat(64), bytes.length)),
                        temporary.resolve("identity").toFile(),
                        runner(),
                        RUN)) {
            assertNull(document.sources.get(0).apk);
            assertEquals(
                    sha(bytes),
                    document.sources
                            .get(0)
                            .errors
                            .getJSONObject(0)
                            .getJSONObject("observedSnapshotIdentity")
                            .getString("sha256"));
        }
    }

    @Test
    public void boundedEscapedPreviewsKeepFullUnicodeSearchAndExportIndependentFromFilter()
            throws Exception {
        JSONArray payload = new JSONArray();
        String full = "\uD800".repeat(4096) + "-full-tail-Ω";
        for (int i = 0; i < 60; i++) payload.put(full + i);
        JSONObject original =
                new JSONObject()
                        .put("name", "Large exact declaration")
                        .put("payload", payload)
                        .put("isolated-key-\uDFFF", "exact-\uD800");
        PackageSetRows rows =
                new PackageSetRows(
                        "fixture",
                        "Full declarations",
                        List.of(new PackageSetRows.Record(2, "fixture", 7, original)),
                        new JSONObject());
        EvidenceRows.Row preview = rows.row(0, RUN);
        assertTrue(preview.encoded.length() < 96 * 1024);
        assertTrue(preview.json().get("record") instanceof String);
        assertEquals(1, rows.select("full-tail", RUN).count);
        StringWriter output = new StringWriter();
        rows.writeReport(output, new JSONObject(), "nonmatching-filter", 9, 1, RUN);
        JSONObject complete =
                new JSONObject(output.toString())
                        .getJSONArray("rows")
                        .getJSONObject(0)
                        .getJSONObject("record");
        assertEquals(full + 59, complete.getJSONArray("payload").getString(59));
        assertEquals("exact-\uD800", complete.getString("isolated-key-\uDFFF"));
        assertNull(preview.physicalOffset);
    }
}
