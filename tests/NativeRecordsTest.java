// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Actual JNI snapshots parsed by the production model, without executing samples. */
public final class NativeRecordsTest {
    private static NativeTable read(String path, int tool, int mode, long offset) throws Exception {
        NativeTable value = NativeTable.parse(Engine.records(path, tool, mode, offset, false));
        require(value.ok, "Native " + tool + " failed: " + value.error); return value;
    }
    private static NativeTable.Row named(NativeTable table, String name) {
        for (NativeTable.Row row : table.rows) if (row.name.equals(name)) return row;
        throw new AssertionError("Missing " + name + " in " + table.kind);
    }

    public static void main(String[] args) throws Exception {
        String elf = args[0]; Path formats = Path.of(args[1]);
        NativeTable symbols = read(elf, 4, 0, -1);
        NativeTable.Row exported = named(symbols, "die_fixture_export");
        require(exported.hasFileByte(symbols.fileSize) && exported.recordOffset != null, "ELF symbol target/source offsets missing");
        NativeTable.Row absolute = named(symbols, "die_fixture_absolute_max");
        require(absolute.flag("isAbsolute") && absolute.toJson().getString("value").equals("0xFFFFFFFFFFFFFFFF"), "Raw unsigned symbol value was treated as a sentinel");
        named(read(elf, 2, 0, -1), "puts"); named(read(elf, 3, 0, -1), "die_fixture_export");

        for (int bits : new int[]{32, 64}) {
            String binary = formats.resolve("pe" + bits + "-rich.exe").toString();
            NativeTable imports = read(binary, 2, 0, -1);
            NativeTable.Row named = named(imports, "fixture_import");
            require(named.text("library").equals("fixture.dll") && named.physicalOffset == 0x480 && named.code("hint") == 0x23, "Import IAT/hint metadata");
            boolean ordinal = false;
            for (NativeTable.Row row : imports.rows) if (row.flag("byOrdinal") && row.text("ordinal").equals("7")) ordinal = true;
            require(ordinal, "Ordinal import missing");
            NativeTable exports = read(binary, 3, 0, -1);
            require(named(exports, "fixture_\"exp").physicalOffset == 0x200, "Export target/JSON escaping");
            require(named(exports, "fixture_forward").text("forwarder").equals("target.Export"), "Forwarder metadata");
            symbols = read(binary, 4, 0, -1);
            require(named(symbols, "die_func").physicalOffset == 0x200 && named(symbols, "die_func").recordOffset == 0xc00, "COFF target confused with record offset");
            require(named(symbols, "abs_sym").flag("isAbsolute") && named(symbols, "undef").flag("isUndefined"), "COFF classifications");
            require(named(symbols, ".file").text("fileName").equals("fixture-source.c"), "COFF auxiliary filename lost");
            named(symbols, "long_é_symbol");
            NativeTable resources = read(binary, 5, 0, -1);
            require(resources.rows.size() == 1, "Resource leaf count");
            NativeTable.Row payload = resources.rows.get(0);
            require(payload.code("typeId") == 10 && payload.code("nameId") == 101 && payload.code("languageId") == 1033 && payload.code("codePage") == 65001, "Resource IDs/code page");
            FileRange range = payload.contentRange(resources.fileSize);
            require(range != null && range.offset == 0x880, "Resource physical range");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            RangeAnalysis.copy(Path.of(binary).toFile(), range, bytes, () -> false);
            require(Arrays.equals(bytes.toByteArray(), Files.readAllBytes(formats.resolve("resource-payload.bin"))), "Resource extraction bytes differ");
            NativeTable memory = read(binary, 1, 1, -1);
            boolean virtual = false;
            for (NativeTable.Row row : memory.rows) if (row.isVirtual) {
                virtual = true; require(row.physicalOffset == null && row.contentRange(memory.fileSize) == null, "Virtual tail acquired fake bytes");
            }
            require(virtual, "PE virtual tail not preserved");
            NativeTable decoding = read(binary, 6, 0, -1);
            require(decoding.rows.get(0).physicalOffset == 0x200 && decoding.rows.get(0).text("addressDomain").equals("virtual"), "Mapped disassembly address domain");
            require(read(formats.resolve("pe" + bits + ".exe").toString(), 5, 0, -1).rows.isEmpty(), "Supported empty table reported as failure");
        }
        NativeTable many = read(formats.resolve("pe-symbols-10000.exe").toString(), 4, 0, -1);
        require(many.rows.size() == 10000 && many.page("", 199, 50).rows.get(49).index == 9999, "Full table silently truncated");
        require(many.page("S0009999", 0, 50).rows.get(0).index == 9999, "Native late record not searchable");
        require(!NativeTable.parse(Engine.records(formats.resolve("pe-symbols-10001.exe").toString(), 4, 0, -1, false)).ok, "Native record bound returned partial success");
        NativeTable high = read(formats.resolve("mach64-high.bin").toString(), 1, 2, -1);
        require(high.mapVirtualAddress("0xFFFF800000001000").get(0).physicalOffset == 4096, "High Mach-O VA mapping");
        NativeTable raw = read(formats.resolve("nops.bin").toString(), 6, 3, 0);
        require(raw.rows.size() == 512 && raw.rows.get(0).virtualAddress == null && raw.rows.get(0).text("addressDomain").equals("file-offset"), "Raw instruction address mislabeled as VA");
        long next = raw.metadata().getLong("nextOffset");
        require(next == 512 && read(formats.resolve("nops.bin").toString(), 6, 3, next).rows.get(0).physicalOffset == next, "Instruction page continuity");
        require(!NativeTable.parse(Engine.records(formats.resolve("nops.bin").toString(), 6, 0, 0, false)).ok, "Raw automatic architecture invented");
        require(!NativeTable.parse(Engine.records(formats.resolve("mach64.bin").toString(), 5, 0, -1, false)).ok, "Unsupported Mach-O resources became empty success");
        require(!NativeTable.parse(Engine.records(elf, 999, 0, -1, false)).ok, "Unknown table succeeded");
        System.out.println("Typed JNI fixtures passed: ELF, rich PE32/64, 10k symbols/caps, payload extraction, high Mach-O VA and disassembly pages/domains.");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
