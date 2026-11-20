// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.util.*;

/** Stable action identities, grouped by investigative purpose rather than file-format internals. */
final class ToolCatalog {
    static final class Tool {
        final String id, label, provider;
        final int nativeTool;

        Tool(String id, String label, String provider, int nativeTool) {
            this.id = id;
            this.label = label;
            this.provider = provider;
            this.nativeTool = nativeTool;
        }
    }

    static final class Group {
        final String id, label;
        final List<Tool> tools;

        Group(String id, String label, Tool... tools) {
            this.id = id;
            this.label = label;
            this.tools = Collections.unmodifiableList(Arrays.asList(tools));
        }
    }

    private static Tool provider(String id, String label) {
        return new Tool(id, label, id, -1);
    }

    private static Tool nativeTool(int id, String label) {
        return new Tool("native-" + id, label, null, id);
    }

    static final List<Group> GROUPS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            new Group(
                                    "general",
                                    "Bytes, strings, hashes and comparison",
                                    nativeTool(9, "Hex / exact byte ranges"),
                                    nativeTool(10, "Entropy / byte statistics"),
                                    provider(
                                            "hashes",
                                            "Whole-file CRC32 / MD4 / MD5 / SHA-1 / SHA-2"),
                                    provider(
                                            "strings",
                                            "Classified strings / URLs / endpoints / indicators"),
                                    nativeTool(8, "Native Unicode strings / encoding controls"),
                                    provider("comparison", "Exact binary byte comparison")),
                            new Group(
                                    "media",
                                    "Media and collected metadata",
                                    new Tool(
                                            "gallerize",
                                            "Gallerize / content gallery / byte and entropy grids",
                                            null,
                                            -1),
                                    provider(
                                            "metadata",
                                            "EXIF / IPTC / ICC / XMP / every collected tag"),
                                    provider(
                                            "mpv",
                                            "MPV codecs / containers / tracks / chapters / stream"
                                                    + " metadata")),
                            new Group(
                                    "contents",
                                    "Archives, embedded content and carving",
                                    new Tool(
                                            "archive",
                                            "Browse archives / compressed streams / parsing"
                                                + " intervals",
                                            null,
                                            -1),
                                    provider(
                                            "photorec",
                                            "PhotoRec whole-blob formats / stored-byte carving"),
                                    provider(
                                            "binwalk",
                                            "Binwalk signatures / firmware / embedded resources")),
                            new Group(
                                    "android",
                                    "Android applications and knowledge",
                                    provider("dex", "DEX tables / bytecode / references / smali"),
                                    provider(
                                            "resources",
                                            "Compiled Android XML / manifest / resource tables"),
                                    provider(
                                            "apk-signing",
                                            "APK signing / certificates / rotation / detached v4"),
                                    provider(
                                            "package-set",
                                            "Base / split declarations and captured relationships"),
                                    provider(
                                            "package-knowledge",
                                            "What does a package do? / sourced community"
                                                + " knowledge"),
                                    provider("sdk-rules", "Canonical SDK / library rule catalog"),
                                    provider(
                                            "tracker-rules",
                                            "Imported Exodus rules / diagnostics / metadata")),
                            new Group(
                                    "executable",
                                    "Executable structures and machine code",
                                    nativeTool(0, "PE / ELF / Mach-O structures and fields"),
                                    nativeTool(1, "Memory maps / sections / segments"),
                                    nativeTool(2, "Imports"),
                                    nativeTool(3, "Exports"),
                                    nativeTool(4, "Symbols"),
                                    nativeTool(5, "Native resources"),
                                    nativeTool(
                                            6,
                                            "Disassembly / architecture / entry-point controls")),
                            new Group(
                                    "expert",
                                    "Rules, profiles, batches and native controls",
                                    new Tool(
                                            "laboratory",
                                            "Desktop scan laboratory / signature editor",
                                            null,
                                            -1),
                                    new Tool("profiles", "Saved scan option profiles", null, -1),
                                    new Tool("batch", "Desktop document batches", null, -1),
                                    nativeTool(7, "Native whole-file hash controls"),
                                    new Tool(
                                            "native-all",
                                            "Every native tool / expert parameters",
                                            null,
                                            -1))));

    private ToolCatalog() {}
}
