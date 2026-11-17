// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.dexbacked.*;
import com.android.tools.smali.dexlib2.dexbacked.instruction.DexBackedInstruction;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.debug.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.*;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Method-local decoding and explicit encoded references, distinct from runtime control flow. */
final class DexCode {
    static final int MAX_INSTRUCTIONS = 200000, MAX_CODE_UNITS = 2000000, MAX_DEBUG = 200000;
    static final int MAX_XREF_METHODS = 200000, MAX_XREF_INSTRUCTIONS = 2000000, MAX_XREF_MATCHES = 50000;
    final DexDocument document;
    final DexDocument.Entry entry;
    final DexBackedMethod method;
    final int owner, member;
    final DexBackedMethodImplementation implementation;
    final List<Instruction> decoded = new ArrayList<>();
    final List<DebugItem> debugItems = new ArrayList<>();
    final List<TryBlock<? extends ExceptionHandler>> tryBlocks = new ArrayList<>();
    private final int[] offsets;
    final int codeUnits, unknownCount;
    final Long firstPhysical;

    DexCode(DexDocument document, DexDocument.Entry entry, int owner, int member, BooleanSupplier cancelled) throws Exception {
        this(document, entry, owner, member, selected(entry, owner, member, cancelled), true, cancelled);
    }
    private static DexBackedMethod selected(DexDocument.Entry entry, int owner, int member, BooleanSupplier stop) throws Exception {
        List<DexBackedMethod> methods = entry.methods(owner, stop);
        if (member < 0 || member >= methods.size()) throw new IOException("Method declaration index outside class"); return methods.get(member);
    }
    private DexCode(DexDocument document, DexDocument.Entry entry, int owner, int member, DexBackedMethod selected, boolean auxiliaryMetadata, BooleanSupplier cancelled) throws Exception {
        this.document = document; this.entry = entry; this.owner = owner; this.member = member;
        method = selected; implementation = method.getImplementation();
        int units = 0, unknown = 0; Long physical = null;
        if (implementation != null) {
            int declared = implementation.getInstructionsSize();
            if (declared < 0 || declared > MAX_CODE_UNITS) throw new IOException("Method exceeds two million code units");
            for (Instruction instruction : implementation.getInstructions()) {
                EvidenceRows.check(cancelled);
                if (decoded.size() == MAX_INSTRUCTIONS) throw new IOException("Method exceeds 200,000 instructions/payloads; no partial decode returned");
                int length = instruction.getCodeUnits();
                if (length <= 0 || length > declared - units || !(instruction instanceof DexBackedInstruction)) throw new IOException("Invalid decoded instruction extent");
                long at = physical(instruction); entry.requireRange(at, length * 2L);
                if (physical == null) { physical = at; entry.requireRange(at, declared * 2L); entry.requireRange(at - 16, 16); }
                if (at != physical + units * 2L) throw new IOException("Discontinuous DEX instruction offsets");
                decoded.add(instruction); units += length; if (instruction instanceof UnknownInstruction) unknown++;
            }
            if (units != declared) throw new IOException("Decoded method length differs from its declaration");
            int handlers = 0;
            if (auxiliaryMetadata) {
            for (TryBlock<? extends ExceptionHandler> block : implementation.getTryBlocks()) {
                EvidenceRows.check(cancelled); List<? extends ExceptionHandler> list = block.getExceptionHandlers();
                if (list.size() > 100000 - handlers || tryBlocks.size() == 65536) throw new IOException("Method exception metadata budget exceeded");
                handlers += list.size(); tryBlocks.add(block);
            }
            for (DebugItem item : implementation.getDebugItems()) {
                EvidenceRows.check(cancelled); if (debugItems.size() == MAX_DEBUG) throw new IOException("Method debug metadata exceeds 200,000 events"); debugItems.add(item);
            }
            }
        }
        firstPhysical = physical; codeUnits = units; unknownCount = unknown; offsets = new int[decoded.size()];
        for (int i = 0, at = 0; i < offsets.length; i++) { offsets[i] = at; at += decoded.get(i).getCodeUnits(); }
    }
    private long physical(Instruction instruction) { return (long)((DexBackedInstruction)instruction).instructionStart + entry.dex.getDataBuffer().getBaseOffset(); }
    int instructionAt(long units) { return units < 0 || units > Integer.MAX_VALUE ? -1 : Arrays.binarySearch(offsets, (int)units); }
    private Object physicalFor(long units) { return firstPhysical == null || units < 0 || units > codeUnits ? JSONObject.NULL : firstPhysical + units * 2; }
    JSONObject metadata() throws Exception {
        JSONObject result = entry.metadata().put("classIndex", owner).put("declarationIndex", member).put("method", DexValues.reference(method, () -> false))
            .put("methodIndex", method.getMethodIndex()).put("hasImplementation", implementation != null)
            .put("registerCount", implementation == null ? JSONObject.NULL : implementation.getRegisterCount())
            .put("codeUnits", implementation == null ? JSONObject.NULL : codeUnits).put("instructionCount", decoded.size()).put("unknownOpcodeCount", unknownCount)
            .put("instructionPhysicalOffset", DexValues.nullable(firstPhysical)).put("debugEventCount", debugItems.size()).put("tryBlockCount", tryBlocks.size())
            .put("addressDomain", "Code-unit addresses are method-relative 16-bit units. Physical offsets locate this immutable input container; they are not runtime/ART addresses.")
            .put("interpretation", "Linear decode and static references do not establish execution, resolved dispatch, a reachable path or validity under ART verification.");
        if (firstPhysical != null) {
            int at = (int)(firstPhysical - 16);
            result.put("codeItemOffset", at).put("incomingParameterWords", entry.dex.getDataBuffer().readUshort(at + 2))
                .put("outgoingArgumentWords", entry.dex.getDataBuffer().readUshort(at + 4)).put("debugProgramOffset", implementation.getDebugOffset());
        }
        return result;
    }
    private EvidenceRows table(DexQuery query, String title, int count, DexTables.Reader reader) {
        return new EvidenceRows("dex-" + query.kind, title) {
            @Override int size() { return count; }
            @Override Row row(int index, BooleanSupplier cancelled) throws Exception {
                EvidenceRows.check(cancelled); if (index < 0 || index >= count) throw new IOException("DEX row outside method table"); return reader.read(index, cancelled);
            }
            @Override JSONObject metadata() throws Exception { return DexCode.this.metadata().put("query", query.json()); }
        };
    }
    EvidenceRows instructions(DexQuery query) { return table(query, "Bytecode • " + method.getName(), decoded.size(), this::instruction); }
    private EvidenceRows.Row instruction(int index, BooleanSupplier stop) throws Exception {
        Instruction instruction = decoded.get(index); Opcode opcode = instruction.getOpcode(); long at = physical(instruction), length = instruction.getCodeUnits() * 2L;
        boolean unknown = instruction instanceof UnknownInstruction; String name = unknown ? "unknown " + DexValues.hex(((UnknownInstruction)instruction).getOriginalOpcode()) : opcode.name;
        JSONObject fields = new JSONObject().put("classIndex", owner).put("declarationIndex", member).put("instructionIndex", index)
            .put("codeUnitOffset", offsets[index]).put("codeUnits", instruction.getCodeUnits()).put("opcode", unknown ? "unknown" : opcode.name)
            .put("format", unknown ? JSONObject.NULL : opcode.format.toString()).put("unknownOpcode", unknown).put("bytesPreview", document.preview(at, length)).put("bytePreviewLimit", 64)
            .put("fullByteLength", length).put("canThrow", unknown ? JSONObject.NULL : opcode.canThrow()).put("canContinue", unknown ? JSONObject.NULL : opcode.canContinue())
            .put("setsResult", unknown ? JSONObject.NULL : opcode.setsResult()).put("setsRegister", unknown ? JSONObject.NULL : opcode.setsRegister())
            .put("setsWideRegister", unknown ? JSONObject.NULL : opcode.setsWideRegister()).put("odexOnly", unknown ? JSONObject.NULL : opcode.odexOnly());
        if (unknown) fields.put("originalOpcode", ((UnknownInstruction)instruction).getOriginalOpcode()).put("engineFallbackOpcode", opcode.name).put("engineFallbackFormat", opcode.format.toString());
        JSONArray registers = new JSONArray();
        if (instruction instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction five = (FiveRegisterInstruction)instruction;
            int[] raw = {five.getRegisterC(), five.getRegisterD(), five.getRegisterE(), five.getRegisterF(), five.getRegisterG()};
            JSONArray encoded = new JSONArray(); for (int value : raw) encoded.put(value);
            for (int i = 0; i < Math.min(5, five.getRegisterCount()); i++) registers.put(raw[i]);
            fields.put("registerCount", five.getRegisterCount()).put("encodedRegisterFields", encoded).put("registerCountFitsFormat", five.getRegisterCount() <= 5);
        } else if (instruction instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction range = (RegisterRangeInstruction)instruction;
            fields.put("registerRangeStart", range.getStartRegister()).put("registerCount", range.getRegisterCount())
                .put("registerRangeFitsFrame", (long)range.getStartRegister() + range.getRegisterCount() <= implementation.getRegisterCount());
        } else {
            if (instruction instanceof OneRegisterInstruction) registers.put(((OneRegisterInstruction)instruction).getRegisterA());
            if (instruction instanceof TwoRegisterInstruction) registers.put(((TwoRegisterInstruction)instruction).getRegisterB());
            if (instruction instanceof ThreeRegisterInstruction) registers.put(((ThreeRegisterInstruction)instruction).getRegisterC());
        }
        fields.put("registers", registers);
        if (instruction instanceof NarrowLiteralInstruction) fields.put("intLiteral", ((NarrowLiteralInstruction)instruction).getNarrowLiteral());
        if (instruction instanceof WideLiteralInstruction) {
            long value = ((WideLiteralInstruction)instruction).getWideLiteral(); fields.put("literalDecimal", Long.toString(value)).put("literalBits", DexValues.hex(value));
        }
        if (instruction instanceof FieldOffsetInstruction) fields.put("fieldOffset", ((FieldOffsetInstruction)instruction).getFieldOffset());
        if (instruction instanceof VtableIndexInstruction) fields.put("vtableIndex", ((VtableIndexInstruction)instruction).getVtableIndex());
        if (instruction instanceof InlineIndexInstruction) fields.put("inlineIndex", ((InlineIndexInstruction)instruction).getInlineIndex());
        if (instruction instanceof VerificationErrorInstruction) fields.put("verificationError", ((VerificationErrorInstruction)instruction).getVerificationError());
        String operands = registers.length() == 0 ? "" : " " + registers;
        if (instruction instanceof ReferenceInstruction) {
            JSONObject reference = reference(instruction, false, stop); fields.put("reference", reference); operands += " " + reference.getString("descriptor");
        }
        if (instruction instanceof DualReferenceInstruction) { JSONObject second = reference(instruction, true, stop); fields.put("reference2", second); operands += " " + second.getString("descriptor"); }
        if (instruction instanceof OffsetInstruction) {
            int relative = ((OffsetInstruction)instruction).getCodeOffset(); long target = (long)offsets[index] + relative; int targetIndex = instructionAt(target);
            fields.put("relativeCodeUnits", relative).put("targetCodeUnits", target).put("targetInstructionIndex", targetIndex < 0 ? JSONObject.NULL : targetIndex)
                .put("targetPhysicalOffset", physicalFor(target)).put("targetKind", opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH || opcode == Opcode.FILL_ARRAY_DATA ? "payload" : "control-transfer");
            operands += " → cu=" + DexValues.hex(target);
        }
        if (instruction instanceof ArrayPayload) {
            ArrayPayload array = (ArrayPayload)instruction; List<Number> values = array.getArrayElements(); JSONArray preview = new JSONArray();
            for (int i = 0; i < Math.min(64, values.size()); i++) { EvidenceRows.check(stop); preview.put(Long.toString(values.get(i).longValue())); }
            fields.put("payloadKind", "array").put("elementWidthBytes", array.getElementWidth()).put("elementCount", values.size()).put("valuesPreview", preview)
                .put("payloadScope", "Preview contains at most 64 elements. Open the full array table, raw range or full method smali for every element.");
        }
        if (instruction instanceof SwitchPayload) {
            fields.put("payloadKind", "switch").put("caseCount", ((SwitchPayload)instruction).getSwitchElements().size())
                .put("payloadScope", "Open switch cases for every key/relative target. Targets are relative to each referring switch instruction, not the payload.");
        }
        return new EvidenceRows.Row(index, name + operands, "cu=" + DexValues.hex(offsets[index]) + " • " + instruction.getCodeUnits() + " units • " + opcode.format, at, length, fields);
    }
    private JSONObject reference(Instruction instruction, boolean second, BooleanSupplier stop) throws Exception {
        Reference value = second ? ((DualReferenceInstruction)instruction).getReference2() : ((ReferenceInstruction)instruction).getReference(); value.validateReference();
        int type = second ? ((DualReferenceInstruction)instruction).getReferenceType2() : ((ReferenceInstruction)instruction).getReferenceType();
        return new JSONObject().put("type", type).put("index", referenceIndex(instruction, second)).put("descriptor", DexValues.reference(value, stop));
    }
    private int referenceIndex(Instruction instruction, boolean second) throws IOException {
        // These raw ID fields are not exposed by the public reference objects.
        // Offsets/widths follow the pinned DexBackedInstruction{20bc,21c,22c,
        // 31c,35c,3rc,45cc,4rcc} readers; instruction decoding remains upstream.
        Format format = instruction.getOpcode().format; int relative = second ? 6 : 2;
        if (second) {
            if (format != Format.Format45cc && format != Format.Format4rcc) throw new IOException("Unsupported second-reference layout");
        } else if (!(format == Format.Format20bc || format == Format.Format21c || format == Format.Format22c || format == Format.Format31c
            || format == Format.Format35c || format == Format.Format3rc || format == Format.Format45cc || format == Format.Format4rcc)) throw new IOException("Unsupported reference layout: " + format);
        int at = ((DexBackedInstruction)instruction).instructionStart + relative;
        return !second && format == Format.Format31c ? entry.dex.getDataBuffer().readSmallUint(at) : entry.dex.getDataBuffer().readUshort(at);
    }
    EvidenceRows array(DexQuery query) throws Exception {
        Instruction instruction = decoded.get(query.referenceIndex);
        if (!(instruction instanceof ArrayPayload)) throw new IOException("Selected instruction is not an array payload");
        ArrayPayload array = (ArrayPayload)instruction; int width = array.getElementWidth(); List<Number> values = array.getArrayElements(); long start = physical(instruction) + 8;
        return table(query, "Array payload elements", values.size(), (i, stop) -> {
            long at = start + (long)i * width; entry.requireRange(at, width); String value = Long.toString(values.get(i).longValue());
            return new EvidenceRows.Row(i, value, width + " stored bytes • untyped bits", at, (long)width,
                new JSONObject().put("elementIndex", i).put("valueSigned", value).put("rawBytes", document.preview(at, width)).put("elementWidthBytes", width)
                    .put("interpretation", "Stored fixed-width bits; numeric/float semantics depend on the consuming array type and dataflow."));
        });
    }
    EvidenceRows switchCases(DexQuery query) throws Exception {
        Instruction instruction = decoded.get(query.referenceIndex);
        if (!(instruction instanceof SwitchPayload)) throw new IOException("Selected instruction is not a switch payload");
        List<? extends SwitchElement> cases = ((SwitchPayload)instruction).getSwitchElements(); List<Integer> origins = new ArrayList<>();
        for (int i = 0; i < decoded.size(); i++) {
            Instruction candidate = decoded.get(i);
            if ((candidate.getOpcode() == Opcode.PACKED_SWITCH || candidate.getOpcode() == Opcode.SPARSE_SWITCH)
                && (long)offsets[i] + ((OffsetInstruction)candidate).getCodeOffset() == offsets[query.referenceIndex]) origins.add(offsets[i]);
        }
        EvidenceRows base = table(query, "Switch cases", cases.size(), (i, stop) -> {
            SwitchElement value = cases.get(i); Long target = origins.size() == 1 ? (long)origins.get(0) + value.getOffset() : null;
            return new EvidenceRows.Row(i, Integer.toString(value.getKey()), "relative=" + value.getOffset() + " code units • origins=" + origins.size(),
                target == null || physicalFor(target) == JSONObject.NULL ? null : (Long)physicalFor(target), null,
                new JSONObject().put("key", value.getKey()).put("relativeCodeUnits", value.getOffset()).put("targetCodeUnits", DexValues.nullable(target))
                    .put("targetInstructionIndex", target == null || instructionAt(target) < 0 ? JSONObject.NULL : instructionAt(target))
                    .put("originCount", origins.size()).put("locationMeaning", "Associated target for a single origin; this is not the physical switch-element record"));
        });
        return new EvidenceRows(base.kind, base.title) {
            int size() { return base.size(); }
            Row row(int index, BooleanSupplier stop) throws Exception { return base.row(index, stop); }
            JSONObject metadata() throws Exception { return base.metadata().put("switchOriginCodeUnits", new JSONArray(origins)); }
        };
    }
    EvidenceRows debug(DexQuery query) {
        return table(query, "Debug events • " + method.getName(), debugItems.size(), (i, stop) -> {
            DebugItem item = debugItems.get(i); Object at = physicalFor(item.getCodeAddress()); JSONObject fields = new JSONObject()
                .put("debugItemType", item.getDebugItemType()).put("codeUnitOffset", item.getCodeAddress()).put("locationMeaning", "Associated code position, not bytes encoding this debug event");
            String name = item.getClass().getSimpleName().replace("Immutable", "");
            if (item instanceof LineNumber) { fields.put("line", ((LineNumber)item).getLineNumber()); name = "line " + ((LineNumber)item).getLineNumber(); }
            if (item instanceof LocalInfo) {
                LocalInfo local = (LocalInfo)item; fields.put("localName", DexValues.nullable(local.getName())).put("localType", DexValues.nullable(local.getType())).put("signature", DexValues.nullable(local.getSignature()));
            }
            if (item instanceof StartLocal) fields.put("register", ((StartLocal)item).getRegister());
            if (item instanceof EndLocal) fields.put("register", ((EndLocal)item).getRegister());
            if (item instanceof RestartLocal) fields.put("register", ((RestartLocal)item).getRegister());
            if (item instanceof SetSourceFile) fields.put("sourceFile", DexValues.nullable(((SetSourceFile)item).getSourceFile()));
            return new EvidenceRows.Row(i, name, "cu=" + DexValues.hex(item.getCodeAddress()) + " • debug type=" + item.getDebugItemType(), at == JSONObject.NULL ? null : (Long)at, null, fields);
        });
    }
    EvidenceRows tries(DexQuery query) {
        return table(query, "Exception regions • " + method.getName(), tryBlocks.size(), (i, stop) -> {
            TryBlock<? extends ExceptionHandler> block = tryBlocks.get(i); JSONArray handlers = new JSONArray();
            for (ExceptionHandler handler : block.getExceptionHandlers()) {
                EvidenceRows.check(stop); int target = handler.getHandlerCodeAddress();
                handlers.put(new JSONObject().put("exceptionType", DexValues.nullable(handler.getExceptionType())).put("handlerCodeUnits", target)
                    .put("handlerInstructionIndex", instructionAt(target) < 0 ? JSONObject.NULL : instructionAt(target)).put("handlerPhysicalOffset", physicalFor(target)));
            }
            Object at = physicalFor(block.getStartCodeAddress()); long end = (long)block.getStartCodeAddress() + block.getCodeUnitCount();
            return new EvidenceRows.Row(i, "try [" + block.getStartCodeAddress() + ", " + end + ")", handlers.length() + " typed/catch-all handlers",
                at == JSONObject.NULL ? null : (Long)at, at == JSONObject.NULL || end > codeUnits ? null : block.getCodeUnitCount() * 2L,
                new JSONObject().put("startCodeUnits", block.getStartCodeAddress()).put("lengthCodeUnits", block.getCodeUnitCount()).put("endCodeUnits", end)
                    .put("rangeFitsMethod", block.getStartCodeAddress() >= 0 && end <= codeUnits).put("handlers", handlers)
                    .put("locationMeaning", "Protected code range, not the bytes encoding the try_item. A null exceptionType is a catch-all."));
        });
    }

    private static Reference target(DexDocument.Entry entry, int type, int index) throws IOException {
        switch (type) {
            case ReferenceType.STRING: return entry.dex.getStringReferences().get(index);
            case ReferenceType.TYPE: return entry.dex.getTypeReferences().get(index);
            case ReferenceType.FIELD: return entry.dex.getFieldSection().get(index);
            case ReferenceType.METHOD: return entry.dex.getMethodSection().get(index);
            case ReferenceType.METHOD_PROTO: return entry.dex.getProtoSection().get(index);
            case ReferenceType.METHOD_HANDLE: return entry.dex.getMethodHandleSection().get(index);
            case ReferenceType.CALL_SITE: return entry.dex.getCallSiteSection().get(index);
            default: throw new IOException("Unsupported DEX reference type: " + type);
        }
    }
    private static final class Match {
        final int owner, member, methodIndex, instructionIndex, codeUnits, slot;
        final long physical, length;
        final String opcode;
        Match(DexCode code, int instruction, int slot) {
            owner = code.owner; member = code.member; methodIndex = code.method.getMethodIndex(); instructionIndex = instruction; codeUnits = code.offsets[instruction]; this.slot = slot;
            Instruction value = code.decoded.get(instruction); physical = code.physical(value); length = value.getCodeUnits() * 2L; opcode = value.getOpcode().name;
        }
    }
    static EvidenceRows references(DexDocument document, DexDocument.Entry entry, DexQuery query, BooleanSupplier cancelled) throws Exception {
        Reference target = target(entry, query.referenceType, query.referenceIndex); target.validateReference(); String descriptor = DexValues.reference(target, cancelled);
        List<Match> matches = new ArrayList<>(); int methods = 0, instructions = 0, unknown = 0;
        for (int owner = 0; owner < entry.dex.getClassSection().size(); owner++) {
            List<DexBackedMethod> declarations = entry.methods(owner, cancelled);
            for (int member = 0; member < declarations.size(); member++) {
                EvidenceRows.check(cancelled); if (++methods > MAX_XREF_METHODS) throw new IOException("Reference scan exceeds 200,000 method declarations");
                if (declarations.get(member).getImplementation() == null) continue;
                DexCode code = new DexCode(document, entry, owner, member, declarations.get(member), false, cancelled); unknown += code.unknownCount;
                if (code.decoded.size() > MAX_XREF_INSTRUCTIONS - instructions) throw new IOException("Reference scan exceeds two million decoded instructions"); instructions += code.decoded.size();
                for (int i = 0; i < code.decoded.size(); i++) {
                    EvidenceRows.check(cancelled); Instruction instruction = code.decoded.get(i);
                    for (int slot = 0; slot < (instruction instanceof DualReferenceInstruction ? 2 : instruction instanceof ReferenceInstruction ? 1 : 0); slot++) {
                        int type = slot == 1 ? ((DualReferenceInstruction)instruction).getReferenceType2() : ((ReferenceInstruction)instruction).getReferenceType();
                        if (type == query.referenceType && code.referenceIndex(instruction, slot == 1) == query.referenceIndex) {
                            if (matches.size() == MAX_XREF_MATCHES) throw new IOException("Reference scan exceeds 50,000 matches; no partial match table returned"); matches.add(new Match(code, i, slot));
                        }
                    }
                }
            }
        }
        final int methodCount = methods, instructionCount = instructions, unknownCount = unknown;
        return new EvidenceRows("dex-xrefs", "Encoded references to " + descriptor) {
            int size() { return matches.size(); }
            Row row(int i, BooleanSupplier stop) throws Exception {
                EvidenceRows.check(stop); Match match = matches.get(i); String caller = DexValues.reference(entry.dex.getMethodSection().get(match.methodIndex), stop);
                return new Row(i, caller, match.opcode + " • cu=" + DexValues.hex(match.codeUnits) + " • reference slot=" + match.slot, match.physical, match.length,
                    new JSONObject().put("classIndex", match.owner).put("declarationIndex", match.member).put("methodIndex", match.methodIndex)
                        .put("instructionIndex", match.instructionIndex).put("codeUnitOffset", match.codeUnits).put("opcode", match.opcode).put("referenceSlot", match.slot)
                        .put("referenceType", query.referenceType).put("referenceIndex", query.referenceIndex).put("target", descriptor).put("caller", caller));
            }
            JSONObject metadata() throws Exception {
                return entry.metadata().put("query", query.json()).put("methodDeclarationsScanned", methodCount).put("instructionsScanned", instructionCount)
                    .put("unknownOpcodeCount", unknownCount).put("referenceCoverage", "Explicit encoded references in this entry; excludes reflection, dynamic dispatch resolution, other DEX entries/files and runtime observations. Unknown opcodes can hide additional references.");
            }
        };
    }
}
