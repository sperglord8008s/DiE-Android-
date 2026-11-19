// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.formatter.DexFormatter;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.value.*;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Bounded conversion of engine-provided values; no independent DEX parser. */
final class DexValues {
    static final int MAX_NODES = 10000, MAX_DEPTH = 32, MAX_FORMATTED_CHARACTERS = 1024 * 1024;
    private int nodes;
    private final BooleanSupplier cancelled;
    DexValues(BooleanSupplier cancelled) { this.cancelled = cancelled; }

    private void visit(int depth) throws IOException {
        EvidenceRows.check(cancelled);
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES) throw new IOException("DEX annotation/value budget: 32 levels and 10,000 nodes per record");
    }
    JSONArray annotations(Set<? extends Annotation> annotations) throws Exception {
        JSONArray result = new JSONArray();
        if (annotations.size() > MAX_NODES) throw new IOException("DEX annotation count exceeds record budget");
        for (Annotation annotation : annotations) {
            visit(0); result.put(new JSONObject().put("type", annotation.getType()).put("visibility", annotation.getVisibility())
                .put("elements", elements(annotation.getElements(), 1)));
        }
        return result;
    }
    private JSONArray elements(Set<? extends AnnotationElement> elements, int depth) throws Exception {
        if (elements.size() > MAX_NODES) throw new IOException("DEX annotation elements exceed record budget");
        JSONArray result = new JSONArray();
        for (AnnotationElement element : elements) { visit(depth); result.put(new JSONObject().put("name", element.getName()).put("value", value(element.getValue(), depth + 1))); }
        return result;
    }
    JSONObject value(EncodedValue value) throws Exception { return value == null ? null : value(value, 0); }
    private JSONObject value(EncodedValue value, int depth) throws Exception {
        visit(depth); int type = value.getValueType(); JSONObject result = new JSONObject().put("valueType", type);
        switch (type) {
            case ValueType.BYTE: result.put("value", ((ByteEncodedValue)value).getValue()); break;
            case ValueType.SHORT: result.put("value", ((ShortEncodedValue)value).getValue()); break;
            case ValueType.CHAR: result.put("utf16CodeUnit", (int)((CharEncodedValue)value).getValue()); break;
            case ValueType.INT: result.put("value", ((IntEncodedValue)value).getValue()); break;
            case ValueType.LONG: result.put("value", Long.toString(((LongEncodedValue)value).getValue())); break;
            case ValueType.FLOAT:
                float f = ((FloatEncodedValue)value).getValue(); result.put("value", Float.toString(f)).put("rawBits", hex(Integer.toUnsignedLong(Float.floatToRawIntBits(f)))); break;
            case ValueType.DOUBLE:
                double d = ((DoubleEncodedValue)value).getValue(); result.put("value", Double.toString(d)).put("rawBits", hex(Double.doubleToRawLongBits(d))); break;
            case ValueType.STRING: result.put("value", ((StringEncodedValue)value).getValue()); break;
            case ValueType.TYPE: result.put("descriptor", ((TypeEncodedValue)value).getValue()); break;
            case ValueType.FIELD: result.put("descriptor", reference(((FieldEncodedValue)value).getValue(), cancelled)); break;
            case ValueType.ENUM: result.put("descriptor", reference(((EnumEncodedValue)value).getValue(), cancelled)); break;
            case ValueType.METHOD: result.put("descriptor", reference(((MethodEncodedValue)value).getValue(), cancelled)); break;
            case ValueType.METHOD_TYPE: result.put("descriptor", reference(((MethodTypeEncodedValue)value).getValue(), cancelled)); break;
            case ValueType.METHOD_HANDLE: result.put("descriptor", reference(((MethodHandleEncodedValue)value).getValue(), cancelled)); break;
            case ValueType.NULL: result.put("value", JSONObject.NULL); break;
            case ValueType.BOOLEAN: result.put("value", ((BooleanEncodedValue)value).getValue()); break;
            case ValueType.ARRAY:
                List<? extends EncodedValue> values = ((ArrayEncodedValue)value).getValue();
                if (values.size() > MAX_NODES) throw new IOException("DEX encoded array exceeds record budget");
                JSONArray array = new JSONArray(); for (EncodedValue item : values) array.put(value(item, depth + 1)); result.put("elements", array); break;
            case ValueType.ANNOTATION:
                AnnotationEncodedValue annotation = (AnnotationEncodedValue)value;
                result.put("type", annotation.getType()).put("elements", elements(annotation.getElements(), depth + 1)); break;
            default: throw new IOException("Unsupported DEX encoded value type: " + type);
        }
        return result;
    }
    static JSONObject access(int flags, int kind) throws JSONException {
        AccessFlags[] known = kind == 0 ? AccessFlags.getAccessFlagsForClass(flags) : kind == 1 ? AccessFlags.getAccessFlagsForMethod(flags) : AccessFlags.getAccessFlagsForField(flags);
        JSONArray names = new JSONArray(); int mask = 0;
        for (AccessFlags flag : known) { names.put(flag.toString()); mask |= flag.getValue(); }
        return new JSONObject().put("raw", hex(Integer.toUnsignedLong(flags))).put("names", names).put("unknownBits", hex(Integer.toUnsignedLong(flags & ~mask)));
    }
    static String reference(Reference reference, BooleanSupplier cancelled) throws IOException {
        try {
            if (reference instanceof com.android.tools.smali.dexlib2.iface.reference.CallSiteReference) {
                var site = (com.android.tools.smali.dexlib2.iface.reference.CallSiteReference)reference;
                if (site.getExtraArguments().size() > MAX_NODES) throw new IOException("Call-site value budget exceeded");
                DexValues values = new DexValues(cancelled);
                for (EncodedValue argument : site.getExtraArguments()) values.value(argument);
            }
            LimitedWriter writer = new LimitedWriter(MAX_FORMATTED_CHARACTERS, cancelled);
            DexFormatter.INSTANCE.getWriter(writer).writeReference(reference); return writer.toString();
        } catch (StackOverflowError error) { throw new IOException("DEX reference recursion exhausted", error); }
        catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("Cannot format DEX reference: " + error.getMessage(), error); }
    }
    static String hex(long value) { return "0x" + Long.toUnsignedString(value, 16).toUpperCase(Locale.ROOT); }
    static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }

    static final class LimitedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private final BooleanSupplier cancelled;
        LimitedWriter(int limit, BooleanSupplier cancelled) { this.limit = limit; this.cancelled = cancelled; }
        @Override public void write(char[] data, int offset, int count) throws IOException {
            EvidenceRows.check(cancelled);
            if (count > limit - text.length()) throw new IOException("Formatted DEX value exceeds character budget");
            text.append(data, offset, count);
        }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() { return text.toString(); }
    }
}
