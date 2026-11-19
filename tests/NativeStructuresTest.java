// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual CDiE visitor snapshots; deterministic source fixtures are never run. */
public final class NativeStructuresTest {
    private static NativeTable read(String path, String selector, int inventory, int slice)
            throws Exception {
        NativeTable table =
                NativeTable.parse(Engine.structures(path, selector, inventory, slice, () -> false));
        require(table.ok, "Structures failed: " + table.error);
        return table;
    }

    public static void main(String[] args) throws Exception {
        String elf = args[0];
        Path directory = Path.of(args[1]), output = Path.of(args[2]);
        Files.createDirectories(output);
        for (String fixture :
                new String[] {
                    "pe32-rich.exe",
                    "pe64-rich.exe",
                    "mach64.bin",
                    "mach64-high.bin",
                    "mach-fat.bin"
                }) {
            String path = directory.resolve(fixture).toString();
            NativeTable table = read(path, "", 0, -1);
            require(
                    table.kind.equals("structures") && table.metadata().getInt("fieldCount") > 0,
                    "Missing typed fields");
            Files.writeString(output.resolve(fixture + ".json"), table.encoded());
            referencesAndContracts(table);
            NativeTable inventory = read(path, "", 1, -1);
            require(
                    inventory.metadata().getInt("fieldCount") == 0 && !inventory.rows.isEmpty(),
                    "Inventory returned fields/empty scope");
            String selector = "";
            for (NativeTable.Row row : table.rows)
                if (row.text("rowType").equals("field")) {
                    int parent = row.toJson().getInt("parentStructureIndex");
                    require(
                            parent < row.index
                                    && table.rows.get(parent).text("rowType").equals("structure"),
                            "Field lost parent");
                    if (selector.isEmpty() && !table.rows.get(parent).name.startsWith("fat_"))
                        selector = table.rows.get(parent).name + "#" + row.name;
                    if (row.contentRange(table.fileSize) != null)
                        require(
                                row.contentRange(table.fileSize).offset == row.physicalOffset,
                                "Field range changed coordinate");
                }
            require(!selector.isEmpty(), "No usable field selector");
            NativeTable selected = read(path, selector, 0, -1);
            require(selected.metadata().getInt("fieldCount") > 0, "Field selector lost fields");
            for (NativeTable.Row row : selected.rows)
                if (row.text("rowType").equals("field"))
                    require(
                            selector.endsWith("#" + row.name),
                            "Field selector returned unrelated fields");
            require(
                    !NativeTable.parse(Engine.structures(path, "unknown#field", 0, -1, () -> false))
                            .ok,
                    "Unknown selector became empty success");
            require(
                    !NativeTable.parse(
                                    Engine.structures(
                                            path,
                                            selector.substring(0, selector.indexOf('#'))
                                                    + "#unknown",
                                            0,
                                            -1,
                                            () -> false))
                            .ok,
                    "Missing field became empty success");
        }
        NativeTable elfTable = read(elf, "", 0, -1);
        require(elfTable.fileType.contains("ELF"), "ELF visitor type");
        NativeTable fatSecond = read(directory.resolve("mach-fat.bin").toString(), "", 0, 1);
        Files.writeString(output.resolve("mach-fat-second.json"), fatSecond.encoded());
        require(
                fatSecond.metadata().getInt("requestedMachOSliceIndex") == 1,
                "Captured slice lost");
        boolean shifted = false;
        for (NativeTable.Row row : fatSecond.rows)
            if (row.text("rowType").equals("field")
                    && row.physicalOffset != null
                    && row.physicalOffset >= 0x3000) shifted = true;
        require(shifted, "Fat fields not shifted into whole input");
        NativeStructures.Reference sliceOffset =
                NativeStructures.reference(fatSecond, field(fatSecond, "section_64", "offset"));
        require(
                sliceOffset != null
                        && sliceOffset.fileOffset
                        && sliceOffset.value.longValueExact() == 0x4000,
                "Slice-relative value not shifted");
        NativeTable selectedSlice =
                read(directory.resolve("mach-fat.bin").toString(), "section_64#offset", 0, 1);
        require(
                NativeStructures.reference(
                                selectedSlice, field(selectedSlice, "section_64", "offset"))
                        == null,
                "Unknown captured slice base invented");
        NativeTable zeroFill = read(directory.resolve("mach64-zerofill.bin").toString(), "", 0, -1);
        require(
                NativeStructures.reference(zeroFill, field(zeroFill, "section_64", "offset"))
                        == null,
                "Zero-fill section acquired a stored target");
        String fatPath = directory.resolve("mach-fat.bin").toString();
        require(
                !NativeTable.parse(Engine.structures(fatPath, "", 0, 2, () -> false)).ok,
                "Absent slice succeeded");
        String huge = directory.resolve("pe-symbols-10000.exe").toString();
        NativeTable failure = NativeTable.parse(Engine.structures(huge, "", 0, -1, () -> false));
        require(
                !failure.ok && failure.rows.isEmpty() && failure.error.contains("10,000"),
                "Over-limit scope returned partial success: " + failure.error);
        require(
                !NativeTable.parse(Engine.structures(huge, "", 1, -1, () -> false)).ok,
                "Over-limit inventory returned partial success");
        AtomicInteger polls = new AtomicInteger();
        failure =
                NativeTable.parse(
                        Engine.structures(huge, "", 0, -1, () -> polls.incrementAndGet() > 40));
        require(
                !failure.ok
                        && failure.rows.isEmpty()
                        && failure.error.toLowerCase().contains("cancel"),
                "Active visitor cancellation failed");
        read(directory.resolve("pe32-rich.exe").toString(), "", 0, -1);
        for (String fixture :
                new String[] {
                    "truncated-elf.bin", "truncated-pe.bin", "truncated-mach.bin", "nops.bin"
                })
            require(
                    !NativeTable.parse(
                                    Engine.structures(
                                            directory.resolve(fixture).toString(),
                                            "",
                                            0,
                                            -1,
                                            () -> false))
                            .ok,
                    "Unsupported/malformed structure success");
        Path oversized = output.resolve("oversized.bin");
        try (var file = new java.io.RandomAccessFile(oversized.toFile(), "rw")) {
            file.setLength(256L * 1024 * 1024 + 1);
        }
        require(
                !NativeTable.parse(Engine.structures(oversized.toString(), "", 0, -1, () -> false))
                        .ok,
                "Input size gate failed");
        Files.delete(oversized);
        require(
                !NativeTable.parse(Engine.structures(fatPath, "bad\0selector", 0, -1, () -> false))
                        .ok,
                "NUL selector accepted");
        require(
                !NativeTable.parse(Engine.structures(fatPath, "", 4, -1, () -> false)).ok,
                "Invalid inventory accepted");
        System.out.println(
                "Typed native structures passed: PE/ELF/Mach-O/fat slices,"
                        + " fields/parents/selectors/inventory, hard row/input gates and active"
                        + " cancellation/recovery.");
    }

    private static NativeTable.Row field(NativeTable table, String owner, String name)
            throws Exception {
        for (NativeTable.Row row : table.rows)
            if (row.text("rowType").equals("field")
                    && row.name.equals(name)
                    && table.rows
                            .get(row.toJson().getInt("parentStructureIndex"))
                            .name
                            .equals(owner)) return row;
        throw new AssertionError("Missing " + owner + "#" + name);
    }

    private static void referencesAndContracts(NativeTable table) throws Exception {
        if (table.fileType.startsWith("PE")) {
            NativeStructures.Reference offset =
                    NativeStructures.reference(table, field(table, "IMAGE_DOS_HEADER", "e_lfanew"));
            require(
                    offset != null
                            && offset.fileOffset
                            && !offset.virtualAddress
                            && offset.value.longValueExact() == 0x80,
                    "PE file-offset reference");
            String owner =
                    table.fileType.equals("PE32")
                            ? "IMAGE_OPTIONAL_HEADER32"
                            : "IMAGE_OPTIONAL_HEADER64";
            NativeStructures.Reference entry =
                    NativeStructures.reference(table, field(table, owner, "AddressOfEntryPoint"));
            require(
                    entry != null
                            && entry.virtualAddress
                            && !entry.fileOffset
                            && entry.value.longValueExact()
                                    == (table.fileType.equals("PE32") ? 0x401000L : 0x140001000L),
                    "PE RVA treated as absolute VA");
            require(
                    NativeStructures.reference(
                                    table, field(table, "IMAGE_DATA_DIRECTORY", "VirtualAddress"))
                            == null,
                    "Unreviewed data-directory coordinates inferred");
        } else if (table.fileType.equals("MACH-O64")) {
            NativeStructures.Reference address =
                    NativeStructures.reference(table, field(table, "segment_command_64", "vmaddr"));
            require(
                    address != null
                            && address.virtualAddress
                            && address.value
                                    .toString(16)
                                    .equals(
                                            table.rows.stream()
                                                            .anyMatch(
                                                                    r ->
                                                                            r.text("rawValue")
                                                                                    .equals(
                                                                                            "0xFFFF800000000000"))
                                                    ? "ffff800000000000"
                                                    : "100000000"),
                    "Mach-O high preferred VA");
        }
        JSONObject source = table.toJson();
        int field = -1;
        for (NativeTable.Row row : table.rows)
            if (row.text("rowType").equals("field")) {
                field = row.index;
                break;
            }
        require(field > 0, "No contract fixture field");
        JSONObject invalid = new JSONObject(source.toString());
        invalid.getJSONArray("rows")
                .getJSONObject(field)
                .put("relativeOffset", "9223372036854775808");
        malformed(invalid);
        invalid = new JSONObject(source.toString());
        invalid.getJSONArray("rows").getJSONObject(field).put("physicalOffset", table.fileSize);
        malformed(invalid);
        invalid = new JSONObject(source.toString());
        invalid.getJSONArray("rows").getJSONObject(field).put("parentStructureIndex", field);
        malformed(invalid);
        invalid = new JSONObject(source.toString());
        invalid.getJSONArray("rows").getJSONObject(field).put("nativeSize", "-1");
        malformed(invalid);
        invalid = new JSONObject(source.toString());
        invalid.getJSONArray("rows").getJSONObject(field).put("unknownPropertyBits", 1);
        malformed(invalid);
        invalid = new JSONObject(source.toString());
        invalid.getJSONArray("rows")
                .getJSONObject(field)
                .getJSONObject("nativeTextUnits")
                .put("nativeName", "00000041");
        malformed(invalid);
        invalid = new JSONObject(source.toString());
        invalid.getJSONArray("rows")
                .getJSONObject(field)
                .getJSONObject("nativeValue")
                .put("typeId", 999);
        malformed(invalid);
        JSONObject extended = new JSONObject(source.toString()),
                record = extended.getJSONArray("rows").getJSONObject(field);
        record.put("properties", record.getLong("properties") | 0x80000000L)
                .put("unknownPropertyBits", 0x80000000L)
                .put("futureOpaque", new JSONObject().put("unpaired", "\ud800"));
        NativeTable future = NativeTable.parse(extended.toString());
        require(
                future.rows
                        .get(field)
                        .toJson()
                        .getJSONObject("futureOpaque")
                        .getString("unpaired")
                        .equals("\ud800"),
                "Unknown fields/semantic bits lost");
    }

    private static void malformed(JSONObject value) throws Exception {
        try {
            NativeTable.parse(value.toString());
            throw new AssertionError("Malformed structure snapshot accepted");
        } catch (JSONException expected) {
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
