// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.dexbacked.*;
import com.android.tools.smali.dexlib2.dexbacked.raw.MapItem;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Table adapters retain source order/indices and decode only requested records. */
final class DexTables {
    interface Reader { EvidenceRows.Row read(int index, BooleanSupplier cancelled) throws Exception; }
    private DexTables() {}
    static EvidenceRows table(DexDocument.Entry entry, DexQuery query, String title, int count, Reader reader) {
        return new EvidenceRows("dex-" + query.kind, title) {
            @Override int size() { return count; }
            @Override Row row(int index, BooleanSupplier cancelled) throws Exception {
                EvidenceRows.check(cancelled); if (index < 0 || index >= count) throw new IOException("DEX row index outside table");
                try { return reader.read(index, cancelled); } catch (StackOverflowError error) { throw new IOException("DEX record recursion exhausted; no complete row returned", error); }
            }
            @Override JSONObject metadata() throws Exception { return entry.metadata().put("query", query.json()); }
        };
    }
    static EvidenceRows open(DexDocument document, DexDocument.Entry entry, DexQuery query, BooleanSupplier cancelled) throws Exception {
        EvidenceRows.check(cancelled);
        switch (query.kind) {
            case "header": {
                String[] names = {"magic", "checksum", "signature", "file_size", "header_size", "endian_tag", "link_size", "link_off", "map_off",
                    "string_ids_size", "string_ids_off", "type_ids_size", "type_ids_off", "proto_ids_size", "proto_ids_off", "field_ids_size", "field_ids_off",
                    "method_ids_size", "method_ids_off", "class_defs_size", "class_defs_off", "data_size", "data_off", "container_size", "header_offset"};
                int[] positions = {0,8,12,32,36,40,44,48,52,56,60,64,68,72,76,80,84,88,92,96,100,104,108,112,116};
                return table(entry, query, "Raw DEX header", entry.version >= 41 ? names.length : names.length - 2, (i, stop) -> {
                    long at = entry.headerOffset + positions[i]; int width = i == 0 ? 8 : i == 2 ? 20 : 4; entry.requireRange(at, width);
                    Object value = width == 4 ? Integer.toUnsignedLong(entry.dex.getBuffer().readInt((int)at)) : document.preview(at, width);
                    return new EvidenceRows.Row(i, names[i], "value=" + value + " • " + width + " bytes", at, (long)width,
                        new JSONObject().put("field", names[i]).put("value", value).put("rawBytes", document.preview(at, width))
                            .put("interpretation", "Original header value; checksums/offsets/counts have different meanings. DEX 041 data_size/data_off are unused; header signature is SHA-1 consistency, not a publisher signature."));
                });
            }
            case "classes": return table(entry, query, "Class definitions", entry.dex.getClassSection().size(), (i, stop) -> {
                DexBackedClassDef type = entry.dex.getClassSection().get(i); long at = entry.dex.getClassSection().getOffset(i); entry.requireRange(at, 32);
                JSONObject fields = new JSONObject().put("classIndex", i).put("descriptor", type.getType()).put("access", DexValues.access(type.getAccessFlags(), 0))
                    .put("superclass", DexValues.nullable(type.getSuperclass())).put("interfaces", strings(type.getInterfaces(), stop))
                    .put("sourceFile", DexValues.nullable(type.getSourceFile())).put("annotations", new DexValues(stop).annotations(type.getAnnotations()))
                    .put("classDataOffset", entry.dex.getBuffer().readSmallUint((int)at + 24))
                    .put("annotationsOffset", entry.dex.getBuffer().readSmallUint((int)at + 20)).put("staticValuesOffset", entry.dex.getBuffer().readSmallUint((int)at + 28))
                    .put("locationMeaning", "class_def item; a definition does not prove the class is loaded or instantiated");
                return new EvidenceRows.Row(i, type.getType(), "super=" + type.getSuperclass() + " • " + fields.getJSONObject("access").getJSONArray("names"), at, 32L, fields);
            });
            case "strings": return table(entry, query, "DEX MUTF-8 string pool", entry.dex.getStringSection().size(), (i, stop) -> {
                int idOffset = entry.dex.getStringSection().getOffset(i), dataOffset = entry.dex.getBuffer().readSmallUint(idOffset);
                DexReader<?> reader = entry.dex.getDataBuffer().readerAt(dataOffset); int declared = reader.readSmallUleb128(); String value = reader.readString(declared);
                long size = reader.getOffset() - dataOffset + 1L; entry.requireRange(dataOffset, size);
                JSONObject fields = new JSONObject().put("stringIndex", i).put("stringIdOffset", idOffset).put("stringDataOffset", dataOffset)
                    .put("value", value).put("utf16Units", value.length()).put("declaredUtf16Units", declared).put("encoding", "DEX modified UTF-8; lengths count UTF-16 code units")
                    .put("locationMeaning", "string_data item, including its length prefix and encoded bytes; a pool entry does not prove use");
                return new EvidenceRows.Row(i, value, "MUTF-8 • " + declared + " UTF-16 units • ID @" + DexValues.hex(idOffset), (long)dataOffset, size, fields);
            });
            case "types": return table(entry, query, "Type descriptors", entry.dex.getTypeSection().size(), (i, stop) -> {
                long at = entry.dex.getTypeSection().getOffset(i); entry.requireRange(at, 4); String value = entry.dex.getTypeSection().get(i);
                return new EvidenceRows.Row(i, value, "Type reference; presence is distinct from a local class definition", at, 4L,
                    new JSONObject().put("typeIndex", i).put("descriptor", value).put("stringIndex", entry.dex.getBuffer().readSmallUint((int)at)));
            });
            case "protos": return references(entry, query, "Method prototypes", ReferenceType.METHOD_PROTO, entry.dex.getProtoSection());
            case "method-refs": return references(entry, query, "Method references", ReferenceType.METHOD, entry.dex.getMethodSection());
            case "field-refs": return references(entry, query, "Field references", ReferenceType.FIELD, entry.dex.getFieldSection());
            case "method-handles": return references(entry, query, "Method handles", ReferenceType.METHOD_HANDLE, entry.dex.getMethodHandleSection());
            case "call-sites": return references(entry, query, "Call sites", ReferenceType.CALL_SITE, entry.dex.getCallSiteSection());
            case "map":
                List<MapItem> map = entry.dex.getMapItems();
                return table(entry, query, "DEX section map", map.size(), (i, stop) -> {
                    MapItem item = map.get(i); long record = entry.dex.getBuffer().readSmallUint(entry.headerOffset + 52) + 4L + i * 12L; entry.requireRange(record, 12);
                    return new EvidenceRows.Row(i, item.getName(), "type=" + DexValues.hex(item.getType()) + " • " + item.getItemCount() + " items @" + DexValues.hex(item.getOffset()), record, 12L,
                        new JSONObject().put("itemType", item.getType()).put("unused", entry.dex.getDataBuffer().readUshort((int)record + 2)).put("itemCount", item.getItemCount()).put("sectionOffset", item.getOffset())
                            .put("locationMeaning", "map_item record; sectionOffset locates its declared section, whose item sizes depend on type"));
                });
            case "methods": {
                DexDocument.Declarations<DexBackedMethod> declarations = entry.methodDeclarations(query.owner, cancelled);
                return table(entry, query, "Declared methods • " + entry.dex.getClassSection().get(query.owner).getType(), declarations.values.size(),
                    (i, stop) -> method(entry, query.owner, i, declarations.values.get(i), i < declarations.firstGroupCount, stop));
            }
            case "fields": {
                DexDocument.Declarations<DexBackedField> declarations = entry.fieldDeclarations(query.owner, cancelled);
                return table(entry, query, "Declared fields • " + entry.dex.getClassSection().get(query.owner).getType(), declarations.values.size(), (i, stop) -> {
                    DexBackedField field = declarations.values.get(i); DexValues values = new DexValues(stop); String descriptor = DexValues.reference(field, stop);
                    JSONObject fields = new JSONObject().put("classIndex", query.owner).put("declarationIndex", i).put("fieldIndex", field.fieldIndex)
                        .put("descriptor", descriptor).put("declaringType", field.getDefiningClass()).put("fieldType", field.getType()).put("declarationGroup", i < declarations.firstGroupCount ? "static" : "instance")
                        .put("access", DexValues.access(field.getAccessFlags(), 2)).put("initialValue", DexValues.nullable(values.value(field.getInitialValue())))
                        .put("annotations", values.annotations(field.getAnnotations())).put("hiddenApiRestrictions", restrictions(field.getHiddenApiRestrictions()))
                        .put("fieldIdDeclaringType", entry.dex.getFieldSection().get(field.fieldIndex).getDefiningClass())
                        .put("declarationMatchesFieldIdOwner", field.getDefiningClass().equals(entry.dex.getFieldSection().get(field.fieldIndex).getDefiningClass()))
                        .put("locationMeaning", "field_id item; encoded class_data definition and optional initial value are separate structures. A missing initializer is distinct from an encoded null.");
                    long at = entry.dex.getFieldSection().getOffset(field.fieldIndex); entry.requireRange(at, 8);
                    return new EvidenceRows.Row(i, descriptor, fields.getString("declarationGroup") + " • " + fields.getJSONObject("access").getJSONArray("names"), at, 8L, fields);
                });
            }
            case "code": case "debug": case "tries": case "array": case "switch": {
                DexCode code = new DexCode(document, entry, query.owner, query.member, cancelled);
                if (query.kind.equals("code")) return code.instructions(query);
                if (query.kind.equals("debug")) return code.debug(query);
                if (query.kind.equals("array")) return code.array(query);
                if (query.kind.equals("switch")) return code.switchCases(query);
                return code.tries(query);
            }
            case "xrefs": return DexCode.references(document, entry, query, cancelled);
            default: throw new IOException("Unsupported DEX query: " + query.kind);
        }
    }
    private static EvidenceRows references(DexDocument.Entry entry, DexQuery query, String title, int type, DexBackedDexFile.IndexedSection<? extends Reference> references) {
        return table(entry, query, title, references.size(), (i, stop) -> {
            Reference reference = references.get(i); reference.validateReference(); String descriptor = DexValues.reference(reference, stop);
            int width = type == ReferenceType.METHOD_PROTO ? 12 : type == ReferenceType.CALL_SITE ? 4 : 8;
            long at = references.getOffset(i); entry.requireRange(at, width);
            JSONObject fields = new JSONObject().put("referenceType", type).put("referenceIndex", i).put("descriptor", descriptor)
                .put("locationMeaning", "identifier/reference table item; a reference does not prove that its target is defined or executed");
            if (reference instanceof MethodReference) {
                MethodReference method = (MethodReference)reference;
                fields.put("declaringType", method.getDefiningClass()).put("methodName", method.getName()).put("parameters", strings(method.getParameterTypes(), stop)).put("returnType", method.getReturnType())
                    .put("declaringTypeIndex", entry.dex.getBuffer().readUshort((int)at)).put("prototypeIndex", entry.dex.getBuffer().readUshort((int)at + 2)).put("nameStringIndex", entry.dex.getBuffer().readSmallUint((int)at + 4));
            } else if (reference instanceof FieldReference) {
                FieldReference field = (FieldReference)reference; fields.put("declaringType", field.getDefiningClass()).put("fieldName", field.getName()).put("fieldType", field.getType())
                    .put("declaringTypeIndex", entry.dex.getBuffer().readUshort((int)at)).put("fieldTypeIndex", entry.dex.getBuffer().readUshort((int)at + 2)).put("nameStringIndex", entry.dex.getBuffer().readSmallUint((int)at + 4));
            } else if (reference instanceof MethodProtoReference) {
                MethodProtoReference proto = (MethodProtoReference)reference; int shorty = entry.dex.getBuffer().readSmallUint((int)at);
                fields.put("parameters", strings(proto.getParameterTypes(), stop)).put("returnType", proto.getReturnType())
                    .put("shortyStringIndex", shorty).put("shorty", entry.dex.getStringSection().get(shorty)).put("returnTypeIndex", entry.dex.getBuffer().readSmallUint((int)at + 4))
                    .put("parameterListOffset", entry.dex.getBuffer().readSmallUint((int)at + 8));
            } else if (reference instanceof MethodHandleReference) {
                MethodHandleReference handle = (MethodHandleReference)reference;
                fields.put("handleType", handle.getMethodHandleType()).put("member", DexValues.reference(handle.getMemberReference(), stop))
                    .put("memberIndex", entry.dex.getBuffer().readUshort((int)at + 4)).put("unused1", entry.dex.getBuffer().readUshort((int)at + 2)).put("unused2", entry.dex.getBuffer().readUshort((int)at + 6));
            } else if (reference instanceof CallSiteReference) {
                CallSiteReference site = (CallSiteReference)reference; DexValues values = new DexValues(stop); JSONArray args = new JSONArray();
                if (site.getExtraArguments().size() > DexValues.MAX_NODES) throw new IOException("Call-site argument budget exceeded");
                for (var argument : site.getExtraArguments()) args.put(values.value(argument));
                fields.put("bootstrapHandle", DexValues.reference(site.getMethodHandle(), stop)).put("methodName", site.getMethodName())
                    .put("methodProto", DexValues.reference(site.getMethodProto(), stop)).put("extraArguments", args).put("encodedArrayOffset", entry.dex.getBuffer().readSmallUint((int)at));
            }
            return new EvidenceRows.Row(i, descriptor, "reference kind=" + type + " • index=" + i, at, (long)width, fields);
        });
    }
    static EvidenceRows.Row method(DexDocument.Entry entry, int owner, int declaration, DexBackedMethod method, boolean direct, BooleanSupplier stop) throws Exception {
        DexValues values = new DexValues(stop); String descriptor = DexValues.reference(method, stop); List<? extends MethodParameter> parameters = method.getParameters();
        if (parameters.size() > 65536) throw new IOException("Method parameter count exceeds 65,536");
        JSONArray params = new JSONArray();
        for (MethodParameter parameter : parameters) {
            EvidenceRows.check(stop); params.put(new JSONObject().put("type", parameter.getType()).put("name", DexValues.nullable(parameter.getName()))
                .put("signature", DexValues.nullable(parameter.getSignature())).put("annotations", values.annotations(parameter.getAnnotations())));
        }
        DexBackedMethodImplementation implementation = method.getImplementation();
        JSONObject fields = new JSONObject().put("classIndex", owner).put("declarationIndex", declaration).put("methodIndex", method.getMethodIndex())
            .put("descriptor", descriptor).put("declaringType", method.getDefiningClass()).put("returnType", method.getReturnType()).put("parameters", params)
            .put("declarationGroup", direct ? "direct" : "virtual").put("access", DexValues.access(method.getAccessFlags(), 1))
            .put("annotations", values.annotations(method.getAnnotations())).put("hiddenApiRestrictions", restrictions(method.getHiddenApiRestrictions()))
            .put("hasImplementation", implementation != null).put("registerCount", implementation == null ? JSONObject.NULL : implementation.getRegisterCount())
            .put("codeUnits", implementation == null ? JSONObject.NULL : implementation.getInstructionsSize())
            .put("methodIdDeclaringType", entry.dex.getMethodSection().get(method.getMethodIndex()).getDefiningClass())
            .put("declarationMatchesMethodIdOwner", method.getDefiningClass().equals(entry.dex.getMethodSection().get(method.getMethodIndex()).getDefiningClass()))
            .put("locationMeaning", "method_id item; code bytes and the encoded class_data definition are separate locations");
        long at = entry.dex.getMethodSection().getOffset(method.getMethodIndex()); entry.requireRange(at, 8);
        return new EvidenceRows.Row(declaration, descriptor, fields.getString("declarationGroup") + " • " + fields.getJSONObject("access").getJSONArray("names")
            + (implementation == null ? " • no implementation" : " • registers=" + implementation.getRegisterCount() + " • units=" + implementation.getInstructionsSize()), at, 8L, fields);
    }
    private static JSONArray restrictions(Set<HiddenApiRestriction> restrictions) {
        JSONArray result = new JSONArray(); for (HiddenApiRestriction restriction : restrictions) result.put(restriction.toString()); return result;
    }
    static JSONArray strings(List<? extends CharSequence> strings, BooleanSupplier cancelled) throws Exception {
        if (strings.size() > 65536) throw new IOException("DEX type/parameter list exceeds 65,536 entries");
        JSONArray result = new JSONArray(); for (CharSequence string : strings) { EvidenceRows.check(cancelled); result.put(string.toString()); } return result;
    }
}
