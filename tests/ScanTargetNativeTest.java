// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.nio.file.*;

/** Actual native candidate IDs and selected scan passes, with distinct known rules. */
public final class ScanTargetNativeTest {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), custom = Path.of(args[2]);
        String db = args[1];
        Files.createDirectories(custom.resolve("ELF"));
        Files.createDirectories(custom.resolve("Binary"));
        Files.writeString(
                custom.resolve("ELF/tool_target.sg"),
                "meta(\"tool\",\"DIE_TARGET_ELF_ONLY\"); function"
                        + " detect(){bDetected=ELF.getSize()>0;return result();}");
        Files.writeString(
                custom.resolve("Binary/tool_target.sg"),
                "meta(\"tool\",\"DIE_TARGET_BINARY_ONLY\"); function"
                        + " detect(){bDetected=Binary.getSize()>0;return result();}");
        int flags = ScanOptions.VERBOSE | ScanOptions.CUSTOM_DB;
        ScanTarget.Catalog catalog =
                ScanTarget.Catalog.parse(
                        Engine.desktop(
                                input.toString(),
                                db,
                                custom.toString(),
                                0,
                                flags,
                                0,
                                "",
                                "",
                                "",
                                ""));
        String sha = Inspector.scan(input.toFile(), 4).sha256;
        ScanTarget.Capture selected =
                ScanTarget.Capture.resolve(
                        new ScanTarget.Request("Binary"),
                        catalog,
                        sha,
                        Files.size(input),
                        "fixture-engine");
        require(
                selected.nativeTypeId != null && selected.nativeTypeId != 0,
                "Native Binary choice missing");
        for (int f : new int[] {flags, flags | ScanOptions.ALL_TYPES}) {
            JSONObject explicit =
                    new JSONObject(
                            Engine.desktop(
                                    input.toString(),
                                    db,
                                    custom.toString(),
                                    1,
                                    f,
                                    selected.nativeTypeId,
                                    "",
                                    "",
                                    "",
                                    ""));
            require(
                    explicit.getBoolean("ok") && explicit.getInt("typeId") == selected.nativeTypeId,
                    "Actual native selector changed");
            String rows = explicit.getJSONArray("rows").toString();
            require(
                    rows.contains("DIE_TARGET_BINARY_ONLY")
                            && !rows.contains("DIE_TARGET_ELF_ONLY"),
                    "Explicit selection did not run only its pass: " + rows);
            JSONObject facts =
                    new JSONObject(
                            Engine.desktop(
                                    input.toString(),
                                    db,
                                    custom.toString(),
                                    5,
                                    f,
                                    selected.nativeTypeId,
                                    "",
                                    "",
                                    "",
                                    ""));
            require(facts.getBoolean("ok"), "Explicit facts failed");
        }
        JSONObject automatic =
                new JSONObject(
                        Engine.desktop(
                                input.toString(),
                                db,
                                custom.toString(),
                                1,
                                flags,
                                0,
                                "",
                                "",
                                "",
                                ""));
        require(
                automatic.getBoolean("ok")
                        && automatic.getInt("typeId") == 0
                        && automatic
                                .getJSONArray("rows")
                                .toString()
                                .contains("DIE_TARGET_ELF_ONLY"),
                "Explicit selection contaminated next automatic request");
        int cachedFlags = flags | ScanOptions.CACHE;
        JSONObject cachedBinary =
                new JSONObject(
                        Engine.desktop(
                                input.toString(),
                                db,
                                custom.toString(),
                                1,
                                cachedFlags,
                                selected.nativeTypeId,
                                "",
                                "",
                                "",
                                ""));
        require(
                cachedBinary.getJSONArray("rows").toString().contains("DIE_TARGET_BINARY_ONLY"),
                "Cached Binary pass missing");
        JSONObject cachedAutomatic =
                new JSONObject(
                        Engine.desktop(
                                input.toString(),
                                db,
                                custom.toString(),
                                1,
                                cachedFlags,
                                0,
                                "",
                                "",
                                "",
                                ""));
        require(
                cachedAutomatic.getJSONArray("rows").toString().contains("DIE_TARGET_ELF_ONLY"),
                "Binary cache contaminated automatic ELF interpretation");
        JSONObject cachedAgain =
                new JSONObject(
                        Engine.desktop(
                                input.toString(),
                                db,
                                custom.toString(),
                                1,
                                cachedFlags,
                                selected.nativeTypeId,
                                "",
                                "",
                                "",
                                ""));
        require(
                cachedAgain.getJSONArray("rows").toString().contains("DIE_TARGET_BINARY_ONLY")
                        && !cachedAgain
                                .getJSONArray("rows")
                                .toString()
                                .contains("DIE_TARGET_ELF_ONLY"),
                "Automatic cache contaminated explicit Binary interpretation");
        require(
                ScanTarget.Capture.resolve(
                                        new ScanTarget.Request("missing-format"),
                                        catalog,
                                        sha,
                                        Files.size(input),
                                        "fixture-engine")
                                .nativeTypeId
                        == null,
                "Missing interpretation became automatic");
        System.out.println(
                "Actual native interpretation tests passed: compatible selectors, Binary-only"
                        + " rules/facts with both All-types settings and subsequent automatic ELF"
                        + " recovery.");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
