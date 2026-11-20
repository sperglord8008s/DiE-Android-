// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.util.*;

/** Every existing native action remains reachable; header recognition does not depend on names. */
public final class PresentationCatalogTest {
    public static void main(String[] arguments) throws Exception {
        Set<String> ids = new HashSet<>();
        Set<Integer> nativeIds = new HashSet<>();
        Set<String> providers = new HashSet<>();
        for (ToolCatalog.Group group : ToolCatalog.GROUPS) {
            if (group.tools.isEmpty()) throw new AssertionError("Empty group");
            for (ToolCatalog.Tool tool : group.tools) {
                if (!ids.add(tool.id)) throw new AssertionError("Duplicate action");
                if (tool.nativeTool >= 0) nativeIds.add(tool.nativeTool);
                if (tool.provider != null) providers.add(tool.provider);
            }
        }
        for (int id = 0; id <= 10; id++)
            if (!nativeIds.contains(id)) throw new AssertionError("Lost native tool " + id);
        if (!providers.containsAll(
                List.of(
                        "hashes",
                        "strings",
                        "comparison",
                        "metadata",
                        "mpv",
                        "photorec",
                        "binwalk",
                        "dex",
                        "resources",
                        "apk-signing",
                        "package-set",
                        "package-knowledge",
                        "sdk-rules",
                        "tracker-rules"))) throw new AssertionError("Lost investigation provider");
        if (!ids.containsAll(List.of("archive", "laboratory", "profiles", "batch", "native-all")))
            throw new AssertionError("Lost expert workflow");
        if (!ToolCatalog.GROUPS.get(0).id.equals("general")
                || !ToolCatalog.GROUPS.get(4).id.equals("executable"))
            throw new AssertionError("Executable tools displaced the general workflow");
        String guide =
                "FILE 101\n\n"
                    + "IDENTIFICATION\n"
                    + "Header-based recognition.\n\n"
                    + "ELF HEADER (direct observation)\n"
                    + "Entry: 0x1234\n"
                    + "Program headers: 3\n\n"
                    + "UNKNOWN FUTURE HEADING\n"
                    + "Every future line remains present.\n";
        List<ReferenceSections.Section> sections = ReferenceSections.parse(guide);
        if (sections.size() != 3
                || !sections.get(1).observed
                || !sections.get(1).body.contains("Program headers: 3")
                || !sections.get(2).body.contains("Every future line"))
            throw new AssertionError("Guide observation/context loss");
        for (ReferenceSections.Section section : sections)
            if (section.title.equals("FILE 101") || section.body.equals("FILE 101"))
                throw new AssertionError("Redundant File 101 presentation retained");
        byte[][] headers = {
            {(byte) 0xff, (byte) 0xd8, (byte) 0xff},
            "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            {'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c},
            {'R', 'a', 'r', '!', 0x1a, 7, 1, 0},
            {0x1f, (byte) 0x8b, 8},
            {(byte) 0xfd, '7', 'z', 'X', 'Z', 0},
            {'B', 'Z', 'h', '9'},
            {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd}
        };
        String[] expected = {"JPEG", "GIF", "7z", "RAR", "gzip", "XZ", "bzip2", "Zstandard"};
        for (int i = 0; i < headers.length; i++) {
            if (!Inspector.identify(headers[i], headers[i].length).startsWith(expected[i]))
                throw new AssertionError("Wrong content header " + expected[i]);
            if (!Inspector.identify(headers[i], 0).equals("Unknown / raw data"))
                throw new AssertionError("Truncated header inferred from unavailable bytes");
        }
        System.out.println(
                "Presentation catalog passed: every native/provider/workflow action retained,"
                    + " content-first order, structured guide/observations and filename-independent"
                    + " media/archive headers.");
    }
}
