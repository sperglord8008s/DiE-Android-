// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.dexbacked.*;
import com.android.tools.smali.dexlib2.dexbacked.raw.HeaderItem;
import com.android.tools.smali.dexlib2.dexbacked.raw.MapItem;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.Adler32;
import org.json.*;

/** Immutable, bounded input plus lazy access to the pinned smali engine. No class loading/execution. */
final class DexDocument {
    static final String ENGINE = "Google smali/dexlib2 + baksmali", VERSION = "3.0.10";
    static final int MAX_BYTES = 32 * 1024 * 1024, MAX_ENTRIES = 16, MAX_CLASSES = 120000;
    static final int MAX_STRINGS = 1000000, MAX_STRING_UNITS = 1024 * 1024, MAX_TOTAL_STRING_UNITS = 16 * 1024 * 1024;
    static final int MAX_DECLARATIONS = 200000;
    private final byte[] bytes;
    final String sha256;
    final List<Entry> entries;

    static DexDocument read(File input, BooleanSupplier cancelled) throws Exception {
        long size = input.length();
        if (!input.isFile() || size > MAX_BYTES) throw new IOException("DEX input unavailable or larger than 32 MiB");
        byte[] data = new byte[(int)size];
        try (InputStream in = new FileInputStream(input)) {
            int position = 0;
            while (position < data.length) {
                EvidenceRows.check(cancelled); int count = in.read(data, position, Math.min(65536, data.length - position));
                if (count < 0) throw new EOFException("DEX input shortened during acquisition"); position += count;
            }
            if (in.read() != -1 || input.length() != size) throw new IOException("DEX input length changed during acquisition");
        }
        return new DexDocument(data, cancelled);
    }
    static DexDocument parse(byte[] data, BooleanSupplier cancelled) throws Exception {
        if (data.length > MAX_BYTES) throw new IOException("DEX input exceeds 32 MiB");
        return new DexDocument(data.clone(), cancelled);
    }
    /** Transfer a freshly acquired private buffer; the caller must never mutate it afterward. */
    static DexDocument takeOwnership(byte[] data, BooleanSupplier cancelled) throws Exception {
        if (data.length > MAX_BYTES) throw new IOException("DEX input exceeds 32 MiB"); return new DexDocument(data, cancelled);
    }
    private DexDocument(byte[] data, BooleanSupplier cancelled) throws Exception {
        bytes = data; sha256 = Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(data));
        List<Entry> parsed = new ArrayList<>(); int offset = 0, totalPools = 0;
        do {
            EvidenceRows.check(cancelled);
            if (parsed.size() == MAX_ENTRIES) throw new IOException("DEX container exceeds 16 entries");
            Entry entry = new Entry(parsed.size(), offset, cancelled); parsed.add(entry);
            totalPools += entry.dex.getStringSection().size() + entry.dex.getClassSection().size();
            if (totalPools > 2000000) throw new IOException("DEX container exceeds two million string/class table entries");
            offset += entry.fileSize;
            if (entry.version < 41 || offset == entry.limit) break;
            if (offset > entry.limit || offset <= entry.headerOffset) throw new IOException("DEX container has invalid entry progression");
        } while (true);
        int declaredContainer = parsed.get(0).limit;
        for (Entry entry : parsed) if (entry.limit != declaredContainer || parsed.size() > 1 && entry.version != 41) throw new IOException("Inconsistent DEX container headers");
        entries = Collections.unmodifiableList(parsed);
    }
    int size() { return bytes.length; }
    JSONObject provenance(String name) throws JSONException {
        return new JSONObject().put("input", new JSONObject().put("name", name).put("sizeBytes", size()).put("sha256", sha256))
            .put("engine", ENGINE).put("engineVersion", VERSION).put("networkUsed", false).put("executionObserved", false);
    }
    Set<String> definedClassNames(BooleanSupplier cancelled) throws Exception {
        return definedClasses(false,cancelled);
    }
    Set<String> definedClassDescriptors(BooleanSupplier cancelled) throws Exception {
        return definedClasses(true,cancelled);
    }
    private Set<String> definedClasses(boolean descriptors,BooleanSupplier cancelled) throws Exception {
        Set<String> result = new TreeSet<>(); int count = 0;
        for (Entry entry : entries) for (int i = 0; i < entry.dex.getClassSection().size(); i++) {
            EvidenceRows.check(cancelled);
            if (++count > MAX_CLASSES) throw new IOException("Defined-class inspection exceeds 120,000 records");
            String descriptor = entry.dex.getClassSection().get(i).getType();
            if (!descriptor.startsWith("L") || !descriptor.endsWith(";") || descriptor.length() < 3) throw new IOException("Invalid class definition descriptor");
            result.add(descriptors?descriptor:descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
        }
        return Collections.unmodifiableSet(result);
    }
    String preview(long offset, long length) throws IOException {
        range(offset, length, bytes.length); return Inspector.toHex(Arrays.copyOfRange(bytes, (int)offset, (int)(offset + Math.min(length, 64))));
    }
    private long u32(int at) throws IOException {
        range(at, 4, bytes.length);
        return (bytes[at] & 255L) | (bytes[at + 1] & 255L) << 8 | (bytes[at + 2] & 255L) << 16 | (bytes[at + 3] & 255L) << 24;
    }
    private static void range(long offset, long count, long limit) throws IOException {
        if (offset < 0 || count < 0 || offset > limit || count > limit - offset) throw new IOException("DEX range outside the declared container");
    }
    private int small(int at) throws IOException {
        long value = u32(at); if (value > Integer.MAX_VALUE) throw new IOException("DEX count/offset exceeds the supported range"); return (int)value;
    }
    private void fixed(int header, int countAt, int offsetAt, int width, int maxCount, int limit) throws IOException {
        long count = u32(header + countAt), offset = u32(header + offsetAt);
        if (count > maxCount) throw new IOException("DEX table exceeds its record budget at header +" + countAt);
        range(offset, count * width, limit);
    }
    private static final class ContainerFile extends DexBackedDexFile {
        // The pinned engine supports absolute container offsets via this protected constructor.
        ContainerFile(byte[] bytes, int headerOffset) { super(null, bytes, 0, true, headerOffset); }
    }
    static final class Declarations<T> {
        final List<T> values;
        final int firstGroupCount;
        Declarations(List<T> values, int firstGroupCount) { this.values = Collections.unmodifiableList(values); this.firstGroupCount = firstGroupCount; }
    }

    final class Entry {
        final int index, headerOffset, version, fileSize, limit;
        final DexBackedDexFile dex;
        final boolean checksumValid, signatureValid;
        final String storedChecksum, measuredChecksum, storedSignature, measuredSignature;
        Entry(int index, int offset, BooleanSupplier cancelled) throws Exception {
            this.index = index; headerOffset = offset; range(offset, 112, bytes.length);
            version = HeaderItem.getVersion(bytes, offset);
            if (!HeaderItem.isSupportedDexVersion(version)) throw new IOException("Unsupported DEX version or magic; compact/ODEX/OAT are separate formats");
            int headerSize = version >= 41 ? 120 : 112; range(offset, headerSize, bytes.length);
            if (u32(offset + HeaderItem.HEADER_SIZE_OFFSET) != headerSize || u32(offset + HeaderItem.ENDIAN_TAG_OFFSET) != 0x12345678L) {
                throw new IOException("Unsupported DEX header size or byte order");
            }
            fileSize = small(offset + HeaderItem.FILE_SIZE_OFFSET); limit = version >= 41 ? small(offset + HeaderItem.CONTAINER_SIZE_OFFSET) : fileSize;
            if (fileSize < headerSize || limit > bytes.length || (long)offset + fileSize > limit) throw new IOException("Invalid DEX file/container size");
            if (version >= 41 && u32(offset + HeaderItem.HEADER_OFFSET_OFFSET) != offset) throw new IOException("DEX container header offset mismatch");
            fixed(offset, 56, 60, 4, MAX_STRINGS, limit); fixed(offset, 64, 68, 4, 65536, limit);
            fixed(offset, 72, 76, 12, 65536, limit); fixed(offset, 80, 84, 8, 65536, limit);
            fixed(offset, 88, 92, 8, 65536, limit); fixed(offset, 96, 100, 32, MAX_CLASSES, limit);
            int map = small(offset + HeaderItem.MAP_OFFSET); range(map, 4, limit); int mapCount = small(map);
            if (mapCount > 65536) throw new IOException("DEX map exceeds 65,536 records"); range(map, 4L + 12L * mapCount, limit);
            // Bound each declared MUTF-8 allocation before the engine decodes any class/reference name.
            int strings = small(offset + 56), stringIds = small(offset + 60); long units = 0; DexBuffer stringBuffer = new DexBuffer(bytes);
            for (int i = 0; i < strings; i++) {
                EvidenceRows.check(cancelled); int dataOffset = small(stringIds + 4 * i); range(dataOffset, 1, limit);
                long length = 0; int shift = 0, at = dataOffset; boolean complete = false;
                for (int n = 0; n < 5; n++) {
                    range(at, 1, limit); int b = bytes[at++] & 255;
                    if (n == 4 && (b & 0xf0) != 0) throw new IOException("DEX string ULEB128 overflows 32 bits");
                    length |= (long)(b & 127) << shift; shift += 7;
                    if ((b & 128) == 0) { complete = true; break; }
                }
                if (!complete || length > MAX_STRING_UNITS || length >= limit - at || (units += length) > MAX_TOTAL_STRING_UNITS) {
                    throw new IOException("DEX MUTF-8 bounds exceed 1 Mi UTF-16 units per string / 16 Mi units per entry");
                }
                DexReader<?> reader = stringBuffer.readerAt(at); reader.readString((int)length);
                range(reader.getOffset(), 1, limit);
                if (bytes[reader.getOffset()] != 0) throw new IOException("DEX string is missing its NUL terminator");
            }
            dex = new ContainerFile(bytes, offset);
            Adler32 checksum = new Adler32(); checksum.update(bytes, offset + 12, fileSize - 12);
            storedChecksum = String.format(Locale.ROOT, "%08x", u32(offset + 8)); measuredChecksum = String.format(Locale.ROOT, "%08x", checksum.getValue());
            storedSignature = Inspector.toHex(Arrays.copyOfRange(bytes, offset + 12, offset + 32));
            MessageDigest signature = MessageDigest.getInstance("SHA-1"); signature.update(bytes, offset + 32, fileSize - 32); measuredSignature = Inspector.toHex(signature.digest());
            checksumValid = storedChecksum.equals(measuredChecksum); signatureValid = storedSignature.equals(measuredSignature);
        }
        JSONObject metadata() throws JSONException {
            return new JSONObject().put("engine", ENGINE).put("engineVersion", VERSION).put("entryIndex", index).put("dexVersion", version)
                .put("headerOffset", headerOffset).put("entrySizeBytes", fileSize).put("containerSizeBytes", limit).put("sourceSizeBytes", size())
                .put("firstContainerEntry", dex.isDexContainerFirstEntry()).put("lastContainerEntry", dex.isDexContainerLastEntry())
                .put("trailingSourceBytes", size() - limit).put("opcodeApi", dex.getOpcodes().api)
                .put("checksum", new JSONObject().put("algorithm", "Adler32").put("stored", storedChecksum).put("measured", measuredChecksum).put("matches", checksumValid))
                .put("signature", new JSONObject().put("algorithm", "SHA-1").put("stored", storedSignature).put("measured", measuredSignature).put("matches", signatureValid))
                .put("integritySpan", "This entry's declared file_size; whole-input SHA-256 also identifies shared container data/overlays. These fields do not authenticate a publisher.")
                .put("classCount", dex.getClassSection().size()).put("stringCount", dex.getStringSection().size()).put("typeCount", dex.getTypeSection().size())
                .put("methodReferenceCount", dex.getMethodSection().size()).put("fieldReferenceCount", dex.getFieldSection().size()).put("prototypeCount", dex.getProtoSection().size())
                .put("validationScope", "Header/string bounds, MUTF-8 and terminators checked. Other records decode lazily; counts are declarations, not full ART verification. Integrity mismatch is disclosed and does not prevent forensic inspection.");
        }
        List<DexBackedMethod> methods(int classIndex, BooleanSupplier cancelled) throws IOException {
            return methodDeclarations(classIndex, cancelled).values;
        }
        Declarations<DexBackedMethod> methodDeclarations(int classIndex, BooleanSupplier cancelled) throws IOException {
            DexBackedClassDef owner = dex.getClassSection().get(classIndex); List<DexBackedMethod> result = new ArrayList<>();
            append(result, owner.getDirectMethods(false), cancelled); int direct = result.size();
            append(result, owner.getVirtualMethods(false), cancelled); return new Declarations<>(result, direct);
        }
        List<DexBackedField> fields(int classIndex, BooleanSupplier cancelled) throws IOException {
            return fieldDeclarations(classIndex, cancelled).values;
        }
        Declarations<DexBackedField> fieldDeclarations(int classIndex, BooleanSupplier cancelled) throws IOException {
            DexBackedClassDef owner = dex.getClassSection().get(classIndex); List<DexBackedField> result = new ArrayList<>();
            append(result, owner.getStaticFields(false), cancelled); int statics = result.size();
            append(result, owner.getInstanceFields(false), cancelled); return new Declarations<>(result, statics);
        }
        private <T> void append(List<T> output, Iterable<? extends T> input, BooleanSupplier cancelled) throws IOException {
            for (T value : input) { EvidenceRows.check(cancelled); if (output.size() == MAX_DECLARATIONS) throw new IOException("DEX class declaration budget exceeded (200,000, including duplicates)"); output.add(value); }
        }
        EvidenceRows rows(DexQuery query, BooleanSupplier cancelled) throws Exception {
            try { return DexTables.open(DexDocument.this, this, query, cancelled); }
            catch (StackOverflowError error) { throw new IOException("DEX parser recursion exhausted; this query is incomplete", error); }
        }
        void requireRange(long at, long count) throws IOException { range(at, count, limit); }
        byte[] copyBytes(long at, long count) throws IOException { requireRange(at, count); return Arrays.copyOfRange(bytes, (int)at, (int)(at + count)); }
    }
}
