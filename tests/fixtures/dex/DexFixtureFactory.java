// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.builder.*;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.immutable.value.*;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.Adler32;

/** Builds data only. No test installs/loads/executes the generated classes. */
public final class DexFixtureFactory {
    public static final String OWNER = "Lorg/diemobile/fixture/研究;";
    public static final String TEXT = "hello\0é\uD83D\uDE00\uD800", ENDPOINT = "https://fixture.invalid/api/v1?token=test";
    private static final int PUBLIC = AccessFlags.PUBLIC.getValue(), STATIC = AccessFlags.STATIC.getValue();
    private DexFixtureFactory() {}
    public static byte[] rich() throws Exception {
        Set<Annotation> annotation = Collections.singleton(new ImmutableAnnotation(AnnotationVisibility.RUNTIME, "Lfixture/Metadata;", Arrays.asList(
            new ImmutableAnnotationElement("note", new ImmutableStringEncodedValue("offline-δ")),
            new ImmutableAnnotationElement("constants", new ImmutableArrayEncodedValue(Arrays.asList(new ImmutableLongEncodedValue(Long.MAX_VALUE),
                new ImmutableFloatEncodedValue(Float.NaN), new ImmutableIntEncodedValue(42), ImmutableBooleanEncodedValue.TRUE_VALUE))))));
        List<Field> fields = Arrays.asList(
            new ImmutableField(OWNER, "ENDPOINT", "Ljava/lang/String;", PUBLIC | STATIC | AccessFlags.FINAL.getValue(), new ImmutableStringEncodedValue(ENDPOINT), annotation, Collections.emptySet()),
            new ImmutableField(OWNER, "BIG", "J", PUBLIC | STATIC, new ImmutableLongEncodedValue(Long.MAX_VALUE), Collections.emptySet(), Collections.emptySet()),
            new ImmutableField(OWNER, "ZERO", "D", PUBLIC | STATIC, new ImmutableDoubleEncodedValue(-0.0), Collections.emptySet(), Collections.emptySet()),
            // Keep ZERO before a non-default value: DexWriter otherwise strips
            // a trailing -0.0 as a default initializer, losing its sign bit.
            new ImmutableField(OWNER, "ZZ_AFTER_ZERO", "I", PUBLIC | STATIC, new ImmutableIntEncodedValue(1), Collections.emptySet(), Collections.emptySet()),
            new ImmutableField(OWNER, "value", "I", PUBLIC, null, Collections.emptySet(), Collections.emptySet()));
        List<Method> methods = new ArrayList<>();
        MethodImplementationBuilder target = new MethodImplementationBuilder(2); target.addLineNumber(10);
        target.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 7)); target.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0));
        methods.add(new ImmutableMethod(OWNER, "target", Arrays.asList(new ImmutableMethodParameter("I", annotation, "入力")), "I", PUBLIC | STATIC,
            annotation, Collections.emptySet(), target.getMethodImplementation()));

        MethodImplementationBuilder body = new MethodImplementationBuilder(6); body.addPrologue(); body.addLineNumber(7);
        // The pinned ClassPool writer rejects SET_SOURCE_FILE debug events;
        // source-file identity is retained in class_def for this fixture.
        body.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference(TEXT)));
        body.addStartLocal(0, new ImmutableStringReference("message"), new ImmutableTypeReference("Ljava/lang/String;"), null);
        body.addLabel("try-start"); body.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 1, 3));
        body.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, new ImmutableMethodReference(OWNER, "target", Arrays.asList("I"), "I")));
        body.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 3));
        body.addInstruction(new BuilderInstruction21c(Opcode.SGET_WIDE, 4, new ImmutableFieldReference(OWNER, "BIG", "J")));
        body.addInstruction(new BuilderInstruction22c(Opcode.NEW_ARRAY, 2, 1, new ImmutableTypeReference("[B")));
        body.addInstruction(new BuilderInstruction31t(Opcode.FILL_ARRAY_DATA, 2, body.getLabel("array")));
        body.addLabel("try-end"); body.addEndLocal(0); body.addRestartLocal(0);
        body.addInstruction(new BuilderInstruction31t(Opcode.PACKED_SWITCH, 3, body.getLabel("switch")));
        body.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        body.addLabel("case0"); body.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference(ENDPOINT)));
        body.addInstruction(new BuilderInstruction10t(Opcode.GOTO, body.getLabel("end")));
        body.addLabel("case1"); body.addInstruction(new BuilderInstruction10x(Opcode.NOP));
        body.addLabel("end"); body.addEpilogue(); body.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        body.addLabel("handler"); body.addInstruction(new BuilderInstruction11x(Opcode.MOVE_EXCEPTION, 0)); body.addInstruction(new BuilderInstruction11x(Opcode.THROW, 0));
        body.addLabel("array"); List<Number> values = new ArrayList<>(); for (int i = 0; i < 80; i++) values.add((byte)(i - 40));
        body.addInstruction(new BuilderArrayPayload(1, values));
        body.addLabel("switch"); body.addInstruction(new BuilderPackedSwitchPayload(7, Arrays.asList(body.getLabel("case0"), body.getLabel("case1"))));
        body.addCatch("Ljava/lang/Exception;", body.getLabel("try-start"), body.getLabel("try-end"), body.getLabel("handler"));
        body.addCatch(body.getLabel("try-start"), body.getLabel("try-end"), body.getLabel("handler"));
        methods.add(method("inspect", body.getMethodImplementation()));
        methods.add(method("longBody", nops(600)));
        methods.add(new ImmutableMethod(OWNER, "nativeMethod", Collections.emptyList(), "V", PUBLIC | STATIC | AccessFlags.NATIVE.getValue(),
            Collections.emptySet(), Collections.emptySet(), (MethodImplementation)null));

        ImmutableMethodReference invokeExact = new ImmutableMethodReference("Ljava/lang/invoke/MethodHandle;", "invokeExact", Arrays.asList("[Ljava/lang/Object;"), "Ljava/lang/Object;");
        ImmutableMethodProtoReference proto = new ImmutableMethodProtoReference(Arrays.asList("I"), "I");
        MethodImplementationBuilder dual = new MethodImplementationBuilder(2);
        dual.addInstruction(new BuilderInstruction45cc(Opcode.INVOKE_POLYMORPHIC, 2, 0, 1, 0, 0, 0, invokeExact, proto));
        dual.addInstruction(new BuilderInstruction4rcc(Opcode.INVOKE_POLYMORPHIC_RANGE, 0, 2, invokeExact, proto));
        dual.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID)); methods.add(method("dualReferences", dual.getMethodImplementation()));

        ImmutableMethodHandleReference bootstrap = new ImmutableMethodHandleReference(MethodHandleType.INVOKE_STATIC,
            new ImmutableMethodReference("Lfixture/Factory;", "bootstrap", Collections.emptyList(), "Ljava/lang/invoke/CallSite;"));
        ImmutableCallSiteReference site = new ImmutableCallSiteReference("fixtureSite", bootstrap, "dynamic", new ImmutableMethodProtoReference(Collections.emptyList(), "V"),
            Arrays.asList(new ImmutableStringEncodedValue(ENDPOINT), new ImmutableIntEncodedValue(42)));
        MethodImplementationBuilder dynamic = new MethodImplementationBuilder(1);
        dynamic.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_CUSTOM, 0, 0, 0, 0, 0, 0, site));
        dynamic.addInstruction(new BuilderInstruction21c(Opcode.CONST_METHOD_HANDLE, 0, bootstrap));
        dynamic.addInstruction(new BuilderInstruction21c(Opcode.CONST_METHOD_TYPE, 0, proto));
        dynamic.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID)); methods.add(method("dynamic", dynamic.getMethodImplementation()));
        List<ClassDef> classes = new ArrayList<>();
        classes.add(new ImmutableClassDef(OWNER, PUBLIC, "Ljava/lang/Object;", Arrays.asList("Lfixture/Marker;"), "研究.smali", annotation, fields, methods));
        for (int i = 0; i < 125; i++) classes.add(new ImmutableClassDef(String.format(Locale.ROOT, "Lfixture/Indexed%04d;", i), PUBLIC, "Ljava/lang/Object;",
            Collections.emptyList(), null, Collections.emptySet(), Collections.emptyList(), Collections.emptyList()));
        return write(classes);
    }
    public static byte[] instructionCap(int count) throws Exception {
        return write(Collections.singletonList(new ImmutableClassDef(OWNER, PUBLIC, "Ljava/lang/Object;", Collections.emptyList(), null, Collections.emptySet(),
            Collections.emptyList(), Collections.singletonList(method("longBody", nops(count))))));
    }
    public static byte[] literal(String value) throws Exception {
        MethodImplementationBuilder body = new MethodImplementationBuilder(1);
        body.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING,0,new ImmutableStringReference(value)));
        body.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        return write(Collections.singletonList(new ImmutableClassDef(OWNER,PUBLIC,"Ljava/lang/Object;",Collections.emptyList(),null,Collections.emptySet(),
            Collections.emptyList(),Collections.singletonList(method("literal",body.getMethodImplementation())))));
    }
    public static byte[] simpleVersion(int api) throws Exception {
        DexPool pool = new DexPool(Opcodes.forApi(api));
        pool.internClass(new ImmutableClassDef(OWNER, PUBLIC, "Ljava/lang/Object;", Collections.emptyList(), null, Collections.emptySet(),
            Collections.emptyList(), Collections.singletonList(method("simple", nops(1)))));
        MemoryDataStore data = new MemoryDataStore(); pool.writeTo(data); return data.getData();
    }
    /** Build a 041 shared-table container without relying on DexWriter's legacy 112-byte header. */
    public static byte[] container41(byte[] classic, boolean sharedSecondEntry) throws Exception {
        int strings = get(classic, 56), oldIds = get(classic, 60), mapOffset = get(classic, 52), mapCount = get(classic, mapOffset);
        if (oldIds != 112 || strings < 2) throw new IllegalArgumentException("Fixture needs a relocatable leading string-ID section");
        byte[] first = Arrays.copyOf(classic, classic.length + strings * 4);
        System.arraycopy(classic, oldIds, first, classic.length, strings * 4);
        first[4] = '0'; first[5] = '4'; first[6] = '1';
        put(first, 32, first.length); put(first, 36, 120); put(first, 60, classic.length);
        put(first, 104, 0); put(first, 108, 0); put(first, 112, first.length); put(first, 116, 0);
        List<byte[]> map = new ArrayList<>();
        for (int i = 0; i < mapCount; i++) {
            byte[] item = Arrays.copyOfRange(classic, mapOffset + 4 + i * 12, mapOffset + 16 + i * 12);
            if ((item[0] & 255) == 1 && item[1] == 0) put(item, 8, classic.length);
            map.add(item);
        }
        writeMap(first, mapOffset, map);
        if (!sharedSecondEntry) { integrity(first, 0, first.length); return first; }
        int second = first.length, secondMap = second + 120, secondSize = 120 + 4 + mapCount * 12;
        byte[] container = Arrays.copyOf(first, first.length + secondSize); System.arraycopy(first, 0, container, second, 120);
        put(container, 112, container.length); put(container, second + 32, secondSize); put(container, second + 52, secondMap);
        put(container, second + 112, container.length); put(container, second + 116, second);
        List<byte[]> shared = new ArrayList<>();
        for (byte[] original : map) {
            byte[] item = original.clone(); int type = (item[0]&255) | (item[1]&255)<<8;
            if (type == 0) put(item, 8, second); if (type == 0x1000) put(item, 8, secondMap); shared.add(item);
        }
        writeMap(container, secondMap, shared);
        integrity(container, 0, first.length); integrity(container, second, secondSize); return container;
    }
    private static void writeMap(byte[] data, int at, List<byte[]> map) {
        map.sort(Comparator.comparingInt(item -> get(item, 8))); put(data, at, map.size());
        for (int i = 0; i < map.size(); i++) System.arraycopy(map.get(i), 0, data, at + 4 + i * 12, 12);
    }
    private static void integrity(byte[] bytes, int offset, int length) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1"); sha.update(bytes, offset + 32, length - 32); System.arraycopy(sha.digest(), 0, bytes, offset + 12, 20);
        Adler32 crc = new Adler32(); crc.update(bytes, offset + 12, length - 12); put(bytes, offset + 8, (int)crc.getValue());
    }
    private static int get(byte[] bytes, int at) { return (bytes[at]&255) | (bytes[at+1]&255)<<8 | (bytes[at+2]&255)<<16 | (bytes[at+3]&255)<<24; }
    private static void put(byte[] bytes, int at, int value) { for (int i = 0; i < 4; i++) bytes[at+i] = (byte)(value >>> (i * 8)); }
    private static Method method(String name, MethodImplementation code) {
        return new ImmutableMethod(OWNER, name, Collections.emptyList(), "V", PUBLIC | STATIC, Collections.emptySet(), Collections.emptySet(), code);
    }
    private static MethodImplementation nops(int count) {
        List<com.android.tools.smali.dexlib2.iface.instruction.Instruction> instructions = new ArrayList<>(Collections.nCopies(count, new ImmutableInstruction10x(Opcode.NOP)));
        instructions.add(new ImmutableInstruction10x(Opcode.RETURN_VOID)); return new ImmutableMethodImplementation(1, instructions, Collections.emptyList(), Collections.emptyList());
    }
    private static byte[] write(List<ClassDef> classes) throws Exception {
        DexPool pool = new DexPool(Opcodes.forApi(28)); for (ClassDef definition : classes) pool.internClass(definition);
        MemoryDataStore data = new MemoryDataStore(); pool.writeTo(data); return data.getData();
    }
}
