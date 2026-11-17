// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Android report integration with actual engine/ZIP bytes, not mocked DEX results. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
public final class DexKnowledgeTest {
    private Path temporary;
    @Before public void directory() throws Exception { temporary = Files.createTempDirectory("die-dex-report"); }
    @After public void cleanup() throws Exception {
        try (var files = Files.walk(temporary)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    @Test public void standaloneDexReportUsesUnicodeEngineAndIntegrityEvidence() throws Exception {
        File input = temporary.resolve("unicode.dex").toFile(); Files.write(input.toPath(), DexFixtureFactory.rich());
        KnowledgeReport.Output report = KnowledgeReport.analyze(RuntimeEnvironment.getApplication(), input, Inspector.scan(input, 4), "unicode.dex");
        assertTrue(report.apk.toString(), report.apk.toString().contains("smali/dexlib2 + baksmali 3.0.10"));
        assertTrue(report.apk.toString(), report.apk.toString().contains("classes=126"));
        assertTrue(report.apk.toString(), report.apk.toString().contains("matches=true"));
        assertTrue(report.apk.toString(), report.apk.toString().contains("MUTF-8/Unicode"));
    }
    @Test public void zipDexReportVerifiesStoredSizeAndCrc() throws Exception {
        byte[] dex = DexFixtureFactory.rich(); File input = zip("classes.dex", dex);
        KnowledgeReport.Output valid = analyze(input); assertTrue(valid.apk.toString(), valid.apk.toString().contains("classes=126"));
        byte[] bytes = Files.readAllBytes(input.toPath()); int at = find(bytes, dex); assertTrue(at >= 0); bytes[at + 8] ^= 1; Files.write(input.toPath(), bytes);
        KnowledgeReport.Output corrupt = analyze(input);
        assertTrue(corrupt.apk.toString(), corrupt.apk.toString().contains("CRC32 mismatch"));
        assertFalse(corrupt.apk.toString(), corrupt.apk.toString().contains("classes=126"));
    }
    @Test public void duplicateDexNamesCannotContributeAmbiguousClassEvidence() throws Exception {
        byte[] dex = DexFixtureFactory.rich(); File input = temporary.resolve("duplicate.zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(input))) { add(out, "classes1.dex", dex); add(out, "classes2.dex", dex); }
        byte[] bytes = Files.readAllBytes(input.toPath()); byte[] before = "classes2.dex".getBytes(java.nio.charset.StandardCharsets.UTF_8), after = "classes1.dex".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int replacements = 0;
        for (int i = 0; i <= bytes.length - before.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + before.length), before)) { System.arraycopy(after, 0, bytes, i, after.length); replacements++; }
        }
        assertEquals(2, replacements); Files.write(input.toPath(), bytes);
        KnowledgeReport.Output report = analyze(input);
        assertTrue(report.apk.toString(), report.apk.toString().contains("duplicate name"));
        assertFalse(report.apk.toString(), report.apk.toString().contains("classes=126"));
    }
    @Test public void cancelledDexMetadataIsDisclosedWithoutSuccessfulCounts() throws Exception {
        File input = temporary.resolve("cancel.dex").toFile(); Files.write(input.toPath(), DexFixtureFactory.rich());
        KnowledgeReport.Output report = KnowledgeReport.analyze(RuntimeEnvironment.getApplication(), input, Inspector.scan(input, 4), "cancel.dex", () -> true);
        assertTrue(report.apk.toString(), report.apk.toString().contains("cancelled")); assertFalse(report.apk.toString().contains("classes=126"));
    }
    private KnowledgeReport.Output analyze(File input) throws Exception { return KnowledgeReport.analyze(RuntimeEnvironment.getApplication(), input, Inspector.scan(input, 4), "fixture.zip"); }
    private File zip(String name, byte[] bytes) throws Exception {
        File file = temporary.resolve("fixture.zip").toFile(); try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) { add(out, name, bytes); } return file;
    }
    private static void add(ZipOutputStream out, String name, byte[] bytes) throws Exception {
        CRC32 crc = new CRC32(); crc.update(bytes); ZipEntry entry = new ZipEntry(name); entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length); entry.setCrc(crc.getValue());
        out.putNextEntry(entry); out.write(bytes); out.closeEntry();
    }
    private static int find(byte[] bytes, byte[] target) {
        outer: for (int i = 0; i <= bytes.length - target.length; i++) { for (int j = 0; j < target.length; j++) if (bytes[i + j] != target[j]) continue outer; return i; } return -1;
    }
}
