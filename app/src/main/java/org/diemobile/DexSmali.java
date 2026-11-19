// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.Adaptors.*;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.debug.DebugItem;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Delegate actual smali formatting/labels/directives to the pinned baksmali engine. */
final class DexSmali {
    private DexSmali() {}
    static void write(DexDocument document, DexQuery query, Writer output, BooleanSupplier cancelled) throws Exception {
        DexDocument.Entry entry = document.entries.get(query.entry);
        DexCode code = new DexCode(document, entry, query.owner, query.member, cancelled);
        // Validate the selected declaration's values before upstream formatting.
        DexTables.method(entry, query.owner, query.member, code.method, false, cancelled);
        ClassDef owner = entry.dex.getClassSection().get(query.owner);
        if (owner.getInterfaces().size() > 65536) throw new IOException("Class interface budget exceeded");
        BaksmaliOptions options = new BaksmaliOptions(); options.apiLevel = entry.dex.getOpcodes().api;
        options.codeOffsets = true; options.accessorComments = false; options.implicitReferences = false;
        // A method-only context prevents an unrelated <clinit> from being decoded
        // by ClassDefinition's static-field discovery. No source classes execute.
        ClassDef context = new ImmutableClassDef(owner.getType(), owner.getAccessFlags(), owner.getSuperclass(), owner.getInterfaces(),
            owner.getSourceFile(), Collections.emptySet(), Collections.emptyList(), Collections.emptyList());
        ClassDefinition definition = new ClassDefinition(options, context);
        Writer checked = new FilterWriter(output) {
            @Override public void write(char[] data, int offset, int count) throws IOException { EvidenceRows.check(cancelled); out.write(data, offset, count); }
            @Override public void write(String data, int offset, int count) throws IOException { EvidenceRows.check(cancelled); out.write(data, offset, count); }
            @Override public void write(int value) throws IOException { EvidenceRows.check(cancelled); out.write(value); }
        };
        checked.write("# DiE Android static method disassembly\n# Engine: " + DexDocument.ENGINE + " " + DexDocument.VERSION
            + "\n# Input SHA-256: " + document.sha256 + "\n# DEX entry: " + query.entry
            + "\n# Code offsets below count method-relative 16-bit units, not physical bytes.\n# Upstream formatting can normalize labels/shared payload representation. Raw ranges remain authoritative.\n"
            + "# Unknown opcodes: " + code.unknownCount + "\n\n");
        BaksmaliWriter writer = new BaksmaliWriter(checked);
        if (code.implementation == null) MethodDefinition.writeEmptyMethodTo(writer, code.method, definition);
        else {
            if (code.decoded.isEmpty()) throw new IOException("Upstream baksmali cannot format an empty stored implementation; structured evidence remains available");
            MethodImplementation implementation = new MethodImplementation() {
                public int getRegisterCount() { return code.implementation.getRegisterCount(); }
                public Iterable<? extends Instruction> getInstructions() { return code.decoded; }
                public List<? extends TryBlock<? extends ExceptionHandler>> getTryBlocks() { return code.tryBlocks; }
                public Iterable<? extends DebugItem> getDebugItems() { return code.debugItems; }
            };
            new MethodDefinition(definition, code.method, implementation).writeTo(writer);
        }
        EvidenceRows.check(cancelled); writer.flush();
    }
}
