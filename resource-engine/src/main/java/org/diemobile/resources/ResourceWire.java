// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile.resources;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded wire inventory before the upstream parser allocates or follows offsets.
 * Every location belongs to the selected uncompressed resource file, never a
 * logical resource ID or a guessed physical position inside its parent APK. */
public final class ResourceWire {
    public static final int MAX_INPUT_BYTES=WireInput.MAX_BYTES;
    public static final class ChunkInfo {
        public final int index, parent, type, offset, headerSize, size;
        ChunkInfo(int index,int parent,int type,int offset,int headerSize,int size) {
            this.index=index;this.parent=parent;this.type=type;this.offset=offset;this.headerSize=headerSize;this.size=size;
        }
        public int end() { return offset+size; }
        public int payload() { return offset+headerSize; }
    }
    public static final class StringInfo {
        public final int index, headerOffset, payloadOffset, payloadBytes, storedBytes, utf16Units;
        public final boolean utf8;
        StringInfo(int index,int headerOffset,int payloadOffset,int payloadBytes,int storedBytes,int utf16Units,boolean utf8) {
            this.index=index;this.headerOffset=headerOffset;this.payloadOffset=payloadOffset;this.payloadBytes=payloadBytes;
            this.storedBytes=storedBytes;this.utf16Units=utf16Units;this.utf8=utf8;
        }
    }
    public static final class SpanInfo {
        public final int styleIndex, ordinal, offset, nameIndex, start, stop;
        SpanInfo(int styleIndex,int ordinal,int offset,int nameIndex,int start,int stop) {
            this.styleIndex=styleIndex;this.ordinal=ordinal;this.offset=offset;this.nameIndex=nameIndex;this.start=start;this.stop=stop;
        }
    }
    public static final class PoolInfo {
        public final ChunkInfo chunk;
        public final long flags;
        public final int styleCount, stringsStart, stylesStart;
        public final List<StringInfo> strings;
        public final List<SpanInfo> spans;
        public final List<Integer> styleOffsets;
        PoolInfo(ChunkInfo chunk,long flags,int styleCount,int stringsStart,int stylesStart,List<StringInfo> strings,List<SpanInfo> spans,List<Integer> styleOffsets) {
            this.chunk=chunk;this.flags=flags;this.styleCount=styleCount;this.stringsStart=stringsStart;this.stylesStart=stylesStart;
            this.strings=Collections.unmodifiableList(strings);this.spans=Collections.unmodifiableList(spans);this.styleOffsets=Collections.unmodifiableList(styleOffsets);
        }
    }
    public static final class EntryInfo {
        public final int ordinal, index, offset, size, flags, keyIndex, valueOffset, valueTypeOffset;
        public final List<Integer> mapOffsets;
        public final boolean compact;
        EntryInfo(int ordinal,int index,int offset,int size,int flags,int keyIndex,int valueOffset,int valueTypeOffset,boolean compact,List<Integer> mapOffsets) {
            this.ordinal=ordinal;this.index=index;this.offset=offset;this.size=size;this.flags=flags;this.keyIndex=keyIndex;
            this.valueOffset=valueOffset;this.valueTypeOffset=valueTypeOffset;this.compact=compact;this.mapOffsets=Collections.unmodifiableList(mapOffsets);
        }
    }
    public static final class TypeInfo {
        public final ChunkInfo chunk;
        public final int typeId, flags, declaredSlots, entriesStart, configOffset, configSize;
        public final List<EntryInfo> entries;
        public final int slotStride;
        private final int[] sparseIndices,rawOffsets;
        TypeInfo(ChunkInfo chunk,int typeId,int flags,int declaredSlots,int entriesStart,int configOffset,int configSize,List<EntryInfo> entries,int[] sparseIndices,int[] rawOffsets,int slotStride) {
            this.chunk=chunk;this.typeId=typeId;this.flags=flags;this.declaredSlots=declaredSlots;this.entriesStart=entriesStart;
            this.configOffset=configOffset;this.configSize=configSize;this.entries=Collections.unmodifiableList(entries);
            this.sparseIndices=sparseIndices;this.rawOffsets=rawOffsets;this.slotStride=slotStride;
        }
        public int slotIndex(int ordinal) { return sparseIndices==null ? ordinal : sparseIndices[ordinal]; }
        public long slotRawOffset(int ordinal) { return rawOffsets[ordinal]&0xffffffffL; }
        public int slotPhysicalOffset(int ordinal) { return chunk.payload()+ordinal*slotStride; }
    }
    public final List<ChunkInfo> chunks;
    public final Map<Integer,PoolInfo> pools;
    public final Map<Integer,TypeInfo> types;
    public final int inputBytes, stringCount, utf16Units, valueCount, entryCount, entrySlots;
    private final WireInput input;
    private final List<ChunkInfo> building=new ArrayList<>();
    private final Map<Integer,PoolInfo> poolIndex=new LinkedHashMap<>();
    private final Map<Integer,TypeInfo> typeIndex=new LinkedHashMap<>();

    private ResourceWire(byte[] bytes, BooleanSupplier cancelled) throws IOException {
        input=new WireInput(bytes,cancelled);input.check();
        if (bytes.length<8) throw WireInput.fail("Android resource root header is truncated",0);
        int rootType=input.u16(0);
        if (rootType!=2 && rootType!=3) throw WireInput.fail("Expected a binary XML or resources.arsc root chunk",0);
        ChunkInfo root=walk(0,bytes.length,-1,0);
        if (root.end()!=bytes.length) throw WireInput.fail("Trailing bytes outside the declared Android resource root",root.end());
        chunks=Collections.unmodifiableList(building);
        pools=Collections.unmodifiableMap(poolIndex);types=Collections.unmodifiableMap(typeIndex);
        inputBytes=bytes.length;stringCount=input.strings;utf16Units=input.units;valueCount=input.values;entryCount=input.entries;entrySlots=input.slots;
    }
    /** The caller owns this private byte array and must keep it unchanged. */
    public static ResourceWire read(byte[] ownedBytes, BooleanSupplier cancelled) throws IOException { return new ResourceWire(ownedBytes,cancelled); }
    private ChunkInfo walk(int at,int enclosingEnd,int parent,int depth) throws IOException {
        input.check();
        if (depth>64 || building.size()>=WireInput.MAX_CHUNKS) throw WireInput.fail("Android resource chunk depth/count budget exceeded",at);
        input.range(at,8,0,enclosingEnd);
        int type=input.u16(at),header=input.u16(at+2);
        long declared=input.u32(at+4);
        if (header<8 || declared<header || declared>enclosingEnd-at || (declared&3)!=0 || (header&3)!=0)
            throw WireInput.fail("Invalid/alignment-crossing Android resource chunk header or size",at);
        ChunkInfo chunk=new ChunkInfo(building.size(),parent,type,at,header,(int)declared);building.add(chunk);
        if (type==1) poolIndex.put(at,WirePools.read(input,chunk));
        else if (type==0x201) typeIndex.put(at,WireTypes.read(input,chunk));
        else if (type==0x202) {
            if (header<16) throw WireInput.fail("Truncated type specification header",at);
            int count=input.count(at+12,65536,"Type specification slots");
            input.range(chunk.payload(),4L*count,chunk.payload(),chunk.end());
        } else if (type==0x203) {
            if (header<12) throw WireInput.fail("Truncated dynamic package library header",at);
            int count=input.count(at+8,256,"Dynamic package entries");
            input.range(chunk.payload(),260L*count,chunk.payload(),chunk.end());
        } else if (type==0x180) {
            if (header!=8 || (chunk.size-header)%4!=0 || (chunk.size-header)/4>WireInput.MAX_STRINGS)
                throw WireInput.fail("Invalid XML resource-map shape",at);
        } else if (type>=0x100 && type<=0x104) validateXml(chunk);
        if (type==2 || type==3 || type==0x200) {
            if (type==2 && header<12 || type==0x200 && header!=284 && header!=288)
                throw WireInput.fail("Unsupported resource table/package header layout",at);
            int child=chunk.payload();
            while (child<chunk.end()) child=walk(child,chunk.end(),chunk.index,depth+1).end();
            if (child!=chunk.end()) throw WireInput.fail("Resource children do not consume their declared parent",child);
        }
        return chunk;
    }
    private void validateXml(ChunkInfo chunk) throws IOException {
        int at=chunk.offset,extension=chunk.payload();
        if (chunk.headerSize!=16) throw WireInput.fail("Unsupported XML node header; exact 16-byte layout required",at);
        if (chunk.type==0x100 || chunk.type==0x101 || chunk.type==0x103) input.range(extension,8,extension,chunk.end());
        else if (chunk.type==0x104) { input.range(extension,12,extension,chunk.end());input.cdataValue(extension+4,chunk.end()); }
        else {
            input.range(extension,20,extension,chunk.end());
            int start=input.u16(extension+8),width=input.u16(extension+10),count=input.u16(extension+12);
            if (start<20 || width!=20 || count>4096) throw WireInput.fail("Unsupported XML attribute stride/start/count",extension+8);
            int first=input.relative(extension,start,extension,chunk.end());
            input.range(first,20L*count,extension,chunk.end());
            for (int i=0;i<count;i++) { input.check();input.value(first+20*i+12,chunk.end()); }
            for (int field=14;field<=18;field+=2) if (input.u16(extension+field)>count)
                throw WireInput.fail("XML id/class/style attribute selector is outside the declared attribute list",extension+field);
        }
    }
}
