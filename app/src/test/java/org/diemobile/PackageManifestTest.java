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
import java.util.zip.ZipFile;

/** Actual Google/wire parsing of independent AAPT2 output and malformed serialized declarations. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
public final class PackageManifestTest {
    private Path temporary;

    @Before
    public void directory() throws Exception {
        temporary = Files.createTempDirectory("die-package-manifest");
    }

    @After
    public void cleanup() throws Exception {
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    private AndroidResourceDocument document(byte[] bytes) throws Exception {
        File file = temporary.resolve(UUID.randomUUID() + ".xml").toFile();
        Files.write(file.toPath(), bytes);
        String sha = Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new AndroidResourceDocument(
                file,
                "AndroidManifest.xml",
                sha,
                AndroidResourceProbe.scan(
                        new ByteArrayInputStream(bytes), bytes.length, () -> false),
                () -> false);
    }

    private PackageManifest reference(String name) throws Exception {
        File apk =
                Path.of(System.getProperty("die.fixture.dir"), "packages", name + ".apk").toFile();
        assertTrue(
                "Run scripts/test-workbench-ui.sh to generate independent package AAPT2 fixtures",
                apk.isFile());
        try (ZipFile zip = new ZipFile(apk);
                InputStream input = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))) {
            return new PackageManifest(document(input.readAllBytes()), () -> false);
        }
    }

    @Test
    public void independentAaptBaseFeatureAndConfigurationDeclarationsPreserveDomainsAndVersions()
            throws Exception {
        PackageManifest base = reference("base");
        assertEquals("org.diemobile.split.reference", base.packageName());
        assertTrue(base.splitKnown());
        assertNull(base.splitName());
        assertEquals("10737418239", base.versionCode64());
        assertTrue(base.fields.get("isolatedSplits").flag());
        assertTrue(base.fields.get("isSplitRequired").flag());
        assertFalse(base.fields.get("isFeatureSplit").flag());
        assertTrue(base.fields.get("isFeatureSplit").defaultApplied);
        assertFalse(base.fields.get("applicationHasCode").flag());
        assertEquals(0, base.diagnostics.length());
        assertEquals("fixture Ω", base.fields.get("versionName").text());
        assertEquals(26L, base.fields.get("minSdkVersion").value);
        PackageManifest notes = reference("notes");
        assertEquals("feature.notes", notes.splitName());
        assertTrue(notes.fields.get("isFeatureSplit").flag());
        assertEquals("feature.core", notes.usesSplits.get(0).text());
        assertEquals(0, notes.diagnostics.length());
        PackageManifest language = reference("language");
        assertEquals("config.en", language.splitName());
        assertEquals("feature.notes", language.fields.get("configForSplit").text());
        assertEquals("language", language.fields.get("splitTypes").text());
        assertEquals(0, language.diagnostics.length());
        assertEquals(3, base.nodes.length());
        assertEquals(4, notes.nodes.length());
        assertTrue(base.json().getString("scope").contains("no resource resolution"));
        JSONObject original = base.fields.get("versionCodeMajor").original.getJSONObject(0);
        assertEquals(0x01010576L, original.getLong("expectedAttributeResourceId"));
        assertEquals(0x01010576L, original.getJSONArray("mappedAttributeResourceIds").getLong(0));
        assertTrue(original.getLong("physicalOffset") > 0);
    }

    @Test
    public void
            serializedDuplicateReferenceAndAttributeIdentityFailuresStayUnresolvedWithoutDefaulting()
                    throws Exception {
        for (int mode : new int[] {1, 2, 3}) {
            PackageManifest manifest = new PackageManifest(document(wire(mode)), () -> false);
            assertNull(manifest.versionCode64());
            assertFalse(manifest.fields.get("versionCode").defaultApplied);
            assertTrue(manifest.diagnostics.length() > 0);
            assertEquals(mode == 1 ? 2 : 1, manifest.fields.get("versionCode").original.length());
            assertEquals(
                    mode == 1
                            ? "ambiguous-attributes"
                            : mode == 2
                                    ? "compiled-attribute-identity-mismatch"
                                    : "unresolved-typed-value",
                    manifest.fields.get("versionCode").status);
            assertEquals(
                    6 + (mode == 1 ? 1 : 0),
                    manifest.nodes.getJSONObject(0).getJSONArray("originalAttributes").length());
        }
        PackageManifest absent = new PackageManifest(document(wire(4)), () -> false);
        assertEquals("18446744073709551615", absent.versionCode64());
        assertTrue(absent.splitKnown());
        assertNull(absent.splitName());
        assertEquals("", absent.fields.get("split").text());
        assertEquals("literal", absent.fields.get("split").status);
        assertFalse(absent.fields.get("split").defaultApplied);
    }

    @Test
    public void repeatedAndNestedNodesRetainEveryOriginalWithoutInventingEffectiveValues()
            throws Exception {
        PackageManifest repeats = new PackageManifest(document(wire(5)), () -> false);
        assertEquals(2, repeats.usesSplits.size());
        assertEquals("feature.core", repeats.usesSplits.get(0).text());
        assertEquals("feature.notes", repeats.usesSplits.get(1).text());
        assertEquals("ambiguous-elements", repeats.fields.get("minSdkVersion").status);
        assertTrue(repeats.diagnostics.toString().contains("repeated-uses-split"));
        assertEquals(6, repeats.nodes.length());
        PackageManifest nested = new PackageManifest(document(wire(6)), () -> false);
        assertEquals(1, nested.usesSplits.size());
        assertEquals(4, nested.nodes.length());
        AndroidResourceDocument captured = document(wire(0));
        boolean cancelled = false;
        try {
            new PackageManifest(captured, () -> true);
        } catch (InterruptedIOException expected) {
            cancelled = true;
        }
        assertTrue(cancelled);
    }

    static byte[] wire(int mode) throws Exception {
        return wire(mode, false);
    }

    static byte[] wire(int mode, boolean reverse) throws Exception {
        String[] strings = {
            "manifest",
            "package",
            "split",
            "versionCode",
            "versionCodeMajor",
            "versionName",
            "isFeatureSplit",
            "uses-split",
            "name",
            PackageManifest.ANDROID,
            "org.diemobile.fixture",
            "feature.notes",
            "feature.core",
            "uses-sdk",
            "minSdkVersion",
            "targetSdkVersion",
            "application",
            "hasCode",
            "configForSplit",
            "fixture Ω",
            ""
        };
        if (reverse) {
            String swap = strings[11];
            strings[11] = strings[12];
            strings[12] = swap;
        }
        int[] ids = new int[strings.length];
        ids[3] = mode == 2 ? 0x01010003 : 0x0101021b;
        ids[4] = 0x01010576;
        ids[5] = 0x0101021c;
        ids[6] = 0x0101055b;
        ids[8] = 0x01010003;
        ids[14] = 0x0101020c;
        ids[15] = 0x01010270;
        ids[17] = 0x0101000c;
        List<int[]> root = new ArrayList<>();
        root.add(new int[] {-1, 1, 10, 3, 10});
        root.add(new int[] {-1, 2, mode == 4 ? 20 : 11, 3, mode == 4 ? 20 : 11});
        root.add(new int[] {9, 3, -1, mode == 3 ? 1 : 16, mode == 3 ? 0x7f010000 : -1});
        root.add(new int[] {9, 4, -1, 16, -1});
        root.add(new int[] {9, 5, 19, 3, 19});
        root.add(new int[] {9, 6, -1, 18, mode == 7 || mode == 8 ? 0 : -1});
        if (mode == 7 || mode == 8)
            root.add(new int[] {-1, 18, mode == 8 ? 20 : 12, 3, mode == 8 ? 20 : 12});
        if (mode == 1) root.add(new int[] {9, 3, -1, 16, 7});
        List<Integer> elements = new ArrayList<>(Arrays.asList(0, 7, 13, 16)),
                depths = new ArrayList<>(Arrays.asList(0, 1, 1, 1));
        List<int[][]> attrs = new ArrayList<>();
        attrs.add(root.toArray(new int[0][]));
        attrs.add(new int[][] {{9, 8, 12, 3, 12}});
        attrs.add(new int[][] {{9, 14, -1, 16, 26}, {9, 15, -1, 16, 35}});
        attrs.add(new int[][] {{9, 17, -1, 18, 0}});
        if (mode == 5) {
            elements.add(7);
            depths.add(1);
            attrs.add(new int[][] {{9, 8, 11, 3, 11}});
            elements.add(13);
            depths.add(1);
            attrs.add(new int[][] {{9, 14, -1, 16, 30}});
        }
        if (mode == 6) {
            elements.add(7);
            depths.add(2);
            attrs.add(new int[][] {{9, 8, 11, 3, 11}});
        }
        return ResourceFixtureFactory.xmlTree(
                strings,
                ids,
                elements.stream().mapToInt(Integer::intValue).toArray(),
                depths.stream().mapToInt(Integer::intValue).toArray(),
                attrs.toArray(new int[0][][]));
    }
}
