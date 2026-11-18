// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.google.devrel.gmscore.tools.apk.arsc.*;
import org.diemobile.resources.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Streaming report adapter. Input bytes are data; no inspected classes are loaded. */
final class AndroidResourceProbe {
    static final String SOURCE_SHA="5f8079eb2d2535b4d8bdc4782022287b52a40dce852fc942fca2adc12f78ccdc";
    static final String PATCH_SHA="0045640af8ec07b2022d92edbc35a8f1f7f4c68681a72285635cd08a8ddb7250";
    static final int MAX_OUTPUT=32*1024*1024;
    private final byte[] bytes;
    private final ValidatedResources document;
    private final BooleanSupplier stop;
    private final Writer output;
    private final JSONObject counts=new JSONObject();
    private final Map<Integer,Integer> chunkIndices=new HashMap<>();
    private final Map<Integer,StringPoolChunk> rootPools=new HashMap<>();
    private boolean firstTable=true,firstRow;
    private int records;
    private AndroidResourceProbe(byte[] bytes,BooleanSupplier stop,Writer output) throws Exception {
        this.bytes=bytes;this.stop=stop;this.output=output;
        document=ValidatedResources.read(bytes,stop);
        for(ResourceWire.ChunkInfo chunk:document.wire.chunks) chunkIndices.put(chunk.offset,chunk.index);
        for(ResourceWire.PoolInfo pool:document.wire.pools.values()) if(pool.chunk.parent>=0) {
            ResourceWire.ChunkInfo parent=document.wire.chunks.get(pool.chunk.parent);
            if(parent.type==2||parent.type==3) rootPools.putIfAbsent(parent.index,(StringPoolChunk)document.chunks.get(pool.chunk.offset));
        }
    }
    static String scan(InputStream source,long size,BooleanSupplier stop) throws Exception {
        if(size<0 || size>ResourceWire.MAX_INPUT_BYTES) throw new IOException("Resource input needs a known length of at most 64 MiB");
        byte[] bytes=new byte[(int)size];int position=0;
        while(position<bytes.length) {
            EvidenceRows.check(stop);int count=source.read(bytes,position,bytes.length-position);
            if(count<0) throw new IOException("Resource input ended before its declared length");
            if(count==0) continue;
            position+=count;
        }
        EvidenceRows.check(stop);
        if(source.read()!=-1) throw new IOException("Resource input grew beyond its declared length");
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        try(OutputStream bounded=new FilterOutputStream(data) {
            int written;
            @Override public void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
            @Override public void write(byte[] value,int offset,int count) throws IOException {
                if(count>MAX_OUTPUT-written) throw new IOException("Resource report exceeds 32 MiB; no successful partial report returned");
                out.write(value,offset,count);written+=count;
            }
        };Writer writer=new OutputStreamWriter(bounded,StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT))) {
            new AndroidResourceProbe(bytes,stop,writer).write();
        }
        EvidenceRows.check(stop);return data.toString(StandardCharsets.UTF_8.name());
    }
    private void write() throws Exception {
        int opaque=0;for(Chunk value:document.chunks.values()) if(value instanceof UnknownChunk) opaque++;
        JSONObject header=new JSONObject().put("schema","org.diemobile.android-resource-evidence").put("schemaVersion",1).put("ok",true)
            .put("engine","Google binary-resources").put("engineVersion","32.5.1").put("sourceJarSha256",SOURCE_SHA).put("sourcePatchSha256",PATCH_SHA).put("adapterVersion",1)
            .put("fileSizeBytes",bytes.length).put("kind",document.wire.chunks.get(0).type==3?"binary-xml":"resource-table")
            .put("wireCoverageComplete",true).put("semanticCoverageComplete",opaque==0).put("opaqueChunks",opaque)
            .put("platformAcceptanceVerified",false).put("networkUsed",false).put("inspectedCodeExecuted",false)
            .put("wireStringCount",document.wire.stringCount).put("wireValueCount",document.wire.valueCount)
            .put("wireEntryCount",document.wire.entryCount).put("wireEntrySlots",document.wire.entrySlots)
            .put("budgets",new JSONObject().put("inputBytes",ResourceWire.MAX_INPUT_BYTES).put("reportBytes",MAX_OUTPUT)
                .put("chunks",100000).put("strings",200000).put("entries",200000).put("values",200000).put("offsetSlots",1000000).put("utf16Units",16*1024*1024))
            .put("scope","All declared supported chunks, strings/styles, XML events/attributes and resource entries/maps/slots; opaque chunks retain exact stored ranges. Unused/padding bytes remain in the owned source.")
            .put("normalizationPolicy","Wire ordinals/aliases/duplicate attributes and mapping keys are authoritative. The upstream Map of bag values is not used as a complete wire inventory. Compact values have a logical 8-byte value but only an 8-byte stored entry.");
        String encoded=header.toString();output.write(encoded,0,encoded.length()-1);output.write(",\"tables\":{");
        chunks();pools();strings();spans();packages();types();offsets();entries();maps();xmlEvents();attributes();resourceMap();typeSpecFlags();libraries();
        output.write("},\"tableCounts\":");EvidenceJson.write(output,counts,stop);output.write('}');
    }
    private void begin(String name) throws Exception {
        EvidenceRows.check(stop);if(!firstTable) output.write(',');firstTable=false;
        EvidenceJson.quoted(output,name,stop);output.write(":[");firstRow=true;records=0;
    }
    private void emit(JSONObject value) throws Exception {
        EvidenceRows.check(stop);if(!firstRow) output.write(',');firstRow=false;
        value.put("index",records++);EvidenceJson.write(output,value,stop);
    }
    private void end(String name) throws Exception { output.write(']');counts.put(name,records); }
    private JSONObject row(String name,int offset,int size) throws Exception {
        return new JSONObject().put("name",name).put("physicalOffset",offset).put("sizeBytes",size);
    }
    private int u16(int at) { return (bytes[at]&255)|((bytes[at+1]&255)<<8); }
    private long u32(int at) { return (bytes[at]&255L)|((bytes[at+1]&255L)<<8)|((bytes[at+2]&255L)<<16)|((bytes[at+3]&255L)<<24); }
    private StringPoolChunk poolFor(ResourceWire.ChunkInfo chunk,int rootType) throws Exception {
        ResourceWire.ChunkInfo root=chunk;
        while(root.type!=rootType && root.parent>=0) root=document.wire.chunks.get(root.parent);
        StringPoolChunk pool=rootPools.get(root.index);
        if(pool!=null) return pool;
        throw new IOException("Associated resource/XML string pool is absent");
    }
    private String text(StringPoolChunk pool,long index) throws Exception {
        if(index==0xffffffffL) return "";
        if(index<0||index>=pool.getStringCount()) throw new IOException("String reference is outside the associated pool: "+index);
        return pool.getString((int)index);
    }
    private JSONObject value(ResourceValue value,StringPoolChunk pool) throws Exception {
        JSONObject facts=AndroidResourceFacts.value(value);
        if(value.type()==ResourceValue.Type.STRING) facts.put("text",text(pool,Integer.toUnsignedLong(value.data()))).put("poolChunkIndex",chunkIndex(DiEResourceAccess.offset(pool)));
        return facts;
    }
    private int chunkIndex(int offset) throws Exception {
        Integer index=chunkIndices.get(offset);if(index!=null) return index;
        throw new IOException("Resource chunk selector expired");
    }
    private Long resourceId(PackageChunk pkg,int type,int index) {
        long packageId=Integer.toUnsignedLong(pkg.getId()),adjusted=(long)type+Integer.toUnsignedLong(pkg.getTypeIdOffset());
        return packageId>255||adjusted<1||adjusted>255||index<0||index>65535 ? null : (packageId<<24)|(adjusted<<16)|index;
    }
    private Object resourceIdValue(PackageChunk pkg,int type,int index) {
        Long value=resourceId(pkg,type,index);return value==null?JSONObject.NULL:value;
    }
    private void chunks() throws Exception {
        begin("chunks");
        for(ResourceWire.ChunkInfo chunk:document.wire.chunks) emit(row(AndroidResourceFacts.chunkName(chunk.type),chunk.offset,chunk.size)
            .put("chunkIndex",chunk.index).put("parentChunkIndex",chunk.parent).put("chunkType",chunk.type).put("headerSize",chunk.headerSize)
            .put("engineClass",document.chunks.get(chunk.offset).getClass().getSimpleName()).put("opaque",document.chunks.get(chunk.offset) instanceof UnknownChunk)
            .put("headerRawHex",Inspector.toHex(document.storedBytes(chunk.offset,chunk.headerSize)))
            .put("declaredPackageCount",chunk.type==2?u32(chunk.offset+8):JSONObject.NULL));
        end("chunks");
    }
    private void pools() throws Exception {
        begin("pools");
        for(ResourceWire.PoolInfo pool:document.wire.pools.values()) emit(row("String pool "+pool.chunk.index,pool.chunk.offset,pool.chunk.size)
            .put("poolChunkIndex",pool.chunk.index).put("flags",pool.flags).put("stringCount",pool.strings.size()).put("styleCount",pool.styleCount)
            .put("stringsStart",pool.stringsStart).put("stylesStart",pool.stylesStart).put("spanCount",pool.spans.size()));
        end("pools");
    }
    private void strings() throws Exception {
        begin("strings");
        for(ResourceWire.PoolInfo pool:document.wire.pools.values()) {
            StringPoolChunk parsed=(StringPoolChunk)document.chunks.get(pool.chunk.offset);
            for(ResourceWire.StringInfo value:pool.strings) emit(row("String "+pool.chunk.index+":"+value.index,value.headerOffset,value.storedBytes)
                .put("poolChunkIndex",pool.chunk.index).put("stringIndex",value.index).put("payloadOffset",value.payloadOffset).put("payloadSizeBytes",value.payloadBytes)
                .put("encoding",value.utf8?"UTF-8 / modified UTF-8":"UTF-16LE").put("utf16Units",value.utf16Units).put("text",parsed.getString(value.index))
                .put("stringOffsetWordOffset",pool.chunk.payload()+4*value.index).put("stringOffsetRelative",value.headerOffset-pool.stringsStart)
                .put("styleOffsetRaw",value.index<pool.styleOffsets.size()?Integer.toUnsignedLong(pool.styleOffsets.get(value.index)):JSONObject.NULL));
        }
        end("strings");
    }
    private void spans() throws Exception {
        begin("spans");
        for(ResourceWire.PoolInfo pool:document.wire.pools.values()) for(ResourceWire.SpanInfo span:pool.spans)
            emit(row("Style "+span.styleIndex+" span "+span.ordinal,span.offset,12).put("poolChunkIndex",pool.chunk.index).put("styleIndex",span.styleIndex)
                .put("ordinal",span.ordinal).put("nameStringIndex",span.nameIndex).put("markupName",((StringPoolChunk)document.chunks.get(pool.chunk.offset)).getString(span.nameIndex))
                .put("firstUtf16Unit",span.start).put("lastUtf16UnitInclusive",span.stop));
        end("spans");
    }
    private void packages() throws Exception {
        begin("packages");
        for(ResourceWire.ChunkInfo chunk:document.wire.chunks) if(chunk.type==0x200) {
            PackageChunk pkg=(PackageChunk)document.chunks.get(chunk.offset);
            emit(row(pkg.getPackageName(),chunk.offset,chunk.headerSize).put("packageChunkIndex",chunk.index).put("packageId",u32(chunk.offset+8))
                .put("typeIdOffset",Integer.toUnsignedLong(pkg.getTypeIdOffset())).put("typeIdOffsetStored",chunk.headerSize>=288)
                .put("typePoolChunkIndex",chunkIndex(DiEResourceAccess.offset(pkg.getTypeStringPool())))
                .put("keyPoolChunkIndex",chunkIndex(DiEResourceAccess.offset(pkg.getKeyStringPool())))
                .put("typeStringsRelative",u32(chunk.offset+268)).put("keyStringsRelative",u32(chunk.offset+276))
                .put("lastPublicType",u32(chunk.offset+272)).put("lastPublicKey",u32(chunk.offset+280))
                .put("packageNameRawUtf16Hex",Inspector.toHex(document.storedBytes(chunk.offset+12,256))));
        }
        end("packages");
    }
    private void types() throws Exception {
        begin("types");
        for(ResourceWire.TypeInfo type:document.wire.types.values()) {
            TypeChunk parsed=(TypeChunk)document.chunks.get(type.chunk.offset);
            emit(row(parsed.getTypeName()+" • "+parsed.getConfiguration(),type.chunk.offset,type.chunk.headerSize)
                .put("typeChunkIndex",type.chunk.index).put("packageChunkIndex",chunkIndex(DiEResourceAccess.offset(parsed.getPackageChunk())))
                .put("typeId",type.typeId).put("flags",type.flags).put("declaredSlots",type.declaredSlots).put("definedEntries",type.entries.size())
                .put("entryDataOffset",type.entriesStart).put("configurationOffset",type.configOffset).put("configurationSize",type.configSize)
                .put("configuration",AndroidResourceFacts.configuration(parsed.getConfiguration())).put("grammaticalByteRaw",bytes[type.configOffset+19]&255)
                .put("configurationRawHex",Inspector.toHex(document.storedBytes(type.configOffset,type.configSize))));
        }
        end("types");
    }
    private void offsets() throws Exception {
        begin("offsets");
        for(ResourceWire.TypeInfo type:document.wire.types.values()) for(int i=0;i<type.declaredSlots;i++) {
            long raw=type.slotRawOffset(i);boolean absent=(type.flags&1)==0&&raw==((type.flags&2)!=0?0xffffL:0xffffffffL);
            emit(row("Entry offset slot "+type.slotIndex(i),type.slotPhysicalOffset(i),type.slotStride).put("typeChunkIndex",type.chunk.index)
                .put("ordinal",i).put("entryIndex",type.slotIndex(i)).put("rawOffset",raw).put("absent",absent)
                .put("offsetMultiplier",(type.flags&3)!=0?4:1).put("entryDataBase",type.entriesStart)
                .put("meaning","Offset is relative to the entry-data area; sparse and offset16 offsets are stored in units of four bytes. NO_ENTRY is a sentinel."));
        }
        end("offsets");
    }
    private void entries() throws Exception {
        begin("entries");
        for(ResourceWire.TypeInfo type:document.wire.types.values()) {
            TypeChunk parsed=(TypeChunk)document.chunks.get(type.chunk.offset);StringPoolChunk global=poolFor(type.chunk,2);
            for(ResourceWire.EntryInfo entry:type.entries) {
                String key=parsed.getPackageChunk().getKeyStringPool().getString(entry.keyIndex);
                JSONObject result=row(parsed.getTypeName()+"/"+key,entry.offset,entry.size).put("typeChunkIndex",type.chunk.index).put("entryIndex",entry.index)
                    .put("ordinal",entry.ordinal).put("keyIndex",entry.keyIndex).put("keyPoolChunkIndex",chunkIndex(DiEResourceAccess.offset(parsed.getPackageChunk().getKeyStringPool())))
                    .put("resourceId",resourceIdValue(parsed.getPackageChunk(),type.typeId,entry.index))
                    .put("flags",entry.flags).put("compact",entry.compact).put("mappingCount",entry.mapOffsets.size());
                if((entry.flags&1)!=0) result.put("parentResourceId",u32(entry.offset+8));
                else result.put("value",value(entry.compact?document.compactValueAt(entry.valueOffset,entry.flags>>>8):document.valueAt(entry.valueOffset),global))
                    .put("storedDataTypeOffset",entry.valueTypeOffset).put("storedDataOffset",entry.compact?entry.valueOffset:entry.valueOffset+4)
                    .put("reservedByte",entry.compact?JSONObject.NULL:bytes[entry.valueOffset+2]&255);
                emit(result);
            }
        }
        end("entries");
    }
    private void maps() throws Exception {
        begin("maps");
        for(ResourceWire.TypeInfo type:document.wire.types.values()) for(ResourceWire.EntryInfo entry:type.entries) {
            Set<Long> seen=new HashSet<>();int ordinal=0;StringPoolChunk global=poolFor(type.chunk,2);
            for(int at:entry.mapOffsets) {
                long key=u32(at);
                emit(row("Bag map "+ordinal,at,12).put("typeChunkIndex",type.chunk.index).put("entryOrdinal",entry.ordinal).put("entryIndex",entry.index)
                    .put("ordinal",ordinal++).put("nameResourceId",key).put("duplicateKey",!seen.add(key)).put("value",value(document.valueAt(at+4),global))
                    .put("reservedByte",bytes[at+6]&255));
            }
        }
        end("maps");
    }
    private void xmlEvents() throws Exception {
        begin("xml-events");Deque<Integer> stack=new ArrayDeque<>();
        for(ResourceWire.ChunkInfo chunk:document.wire.chunks) if(chunk.type>=0x100&&chunk.type<=0x104) {
            StringPoolChunk pool=poolFor(chunk,3);int ext=chunk.payload();
            JSONObject result=row(AndroidResourceFacts.chunkName(chunk.type),chunk.offset,chunk.size).put("chunkIndex",chunk.index)
                .put("chunkType",chunk.type).put("lineNumber",u32(chunk.offset+8)).put("commentIndex",u32(chunk.offset+12))
                .put("comment",text(pool,u32(chunk.offset+12))).put("poolChunkIndex",chunkIndex(DiEResourceAccess.offset(pool)))
                .put("parentElementChunkIndex",stack.isEmpty()?JSONObject.NULL:stack.peekLast());
            if(chunk.type==0x100||chunk.type==0x101) result.put("prefixIndex",u32(ext)).put("prefix",text(pool,u32(ext))).put("uriIndex",u32(ext+4)).put("uri",text(pool,u32(ext+4)));
            else if(chunk.type==0x102||chunk.type==0x103) {
                result.put("namespaceIndex",u32(ext)).put("namespace",text(pool,u32(ext))).put("nameStringIndex",u32(ext+4)).put("elementName",text(pool,u32(ext+4)));
                if(chunk.type==0x102) { result.put("attributeCount",u16(ext+12)).put("attributeStart",u16(ext+8));stack.addLast(chunk.index); }
                else if(!stack.isEmpty()) {
                    ResourceWire.ChunkInfo start=document.wire.chunks.get(stack.peekLast());
                    if(u32(start.payload())==u32(ext)&&u32(start.payload()+4)==u32(ext+4)) result.put("matchingStartChunkIndex",stack.removeLast());
                    else result.put("structuralDiagnostic","End element does not match the current start element; stack not normalized");
                } else result.put("structuralDiagnostic","End element has no open start element");
            } else {
                result.put("textIndex",u32(ext)).put("text",text(pool,u32(ext)))
                    .put("typedValueRawHex",Inspector.toHex(document.storedBytes(ext+4,8)));
                boolean present=u16(ext+4)!=0;
                result.put("typedValuePresent",present);
                if(present) result.put("value",value(document.valueAt(ext+4),pool));
                else result.put("typedValueInterpretation","AAPT2-style unused all-zero CDATA typed-value field; textIndex retains the stored character data. This is not a size-8 TYPE_NULL value.");
            }
            emit(result);
        }
        end("xml-events");
    }
    private void attributes() throws Exception {
        begin("attributes");
        for(ResourceWire.ChunkInfo chunk:document.wire.chunks) if(chunk.type==0x102) {
            StringPoolChunk pool=poolFor(chunk,3);int ext=chunk.payload(),start=ext+u16(ext+8),count=u16(ext+12);
            for(int i=0;i<count;i++) {
                int at=start+20*i;
                emit(row(text(pool,u32(at+4)),at,20).put("elementChunkIndex",chunk.index).put("ordinal",i).put("poolChunkIndex",chunkIndex(DiEResourceAccess.offset(pool)))
                    .put("namespaceIndex",u32(at)).put("namespace",text(pool,u32(at))).put("nameStringIndex",u32(at+4))
                    .put("rawValueIndex",u32(at+8)).put("rawValue",text(pool,u32(at+8))).put("reservedByte",bytes[at+14]&255)
                    .put("value",value(document.valueAt(at+12),pool)).put("idAttribute",u16(ext+14)==i+1).put("classAttribute",u16(ext+16)==i+1).put("styleAttribute",u16(ext+18)==i+1));
            }
        }
        end("attributes");
    }
    private void resourceMap() throws Exception {
        begin("resource-map");
        for(ResourceWire.ChunkInfo chunk:document.wire.chunks) if(chunk.type==0x180) {
            StringPoolChunk pool=poolFor(chunk,3);int ordinal=0;
            for(int at=chunk.payload();at<chunk.end();at+=4) emit(row("Attribute resource map "+ordinal,at,4).put("chunkIndex",chunk.index).put("stringIndex",ordinal)
                .put("poolChunkIndex",chunkIndex(DiEResourceAccess.offset(pool))).put("attributeName",text(pool,ordinal++)).put("resourceId",u32(at)));
        }
        end("resource-map");
    }
    private void typeSpecFlags() throws Exception {
        begin("type-spec-flags");
        for(ResourceWire.ChunkInfo chunk:document.wire.chunks) if(chunk.type==0x202) {
            int count=(int)u32(chunk.offset+12);
            for(int i=0;i<count;i++) emit(row("Type spec entry "+i,chunk.payload()+4*i,4).put("chunkIndex",chunk.index).put("typeId",bytes[chunk.offset+8]&255)
                .put("entryIndex",i).put("configurationFlags",u32(chunk.payload()+4*i)));
        }
        end("type-spec-flags");
    }
    private void libraries() throws Exception {
        begin("libraries");
        for(ResourceWire.ChunkInfo chunk:document.wire.chunks) if(chunk.type==0x203) {
            int count=(int)u32(chunk.offset+8);
            for(int i=0;i<count;i++) {
                int at=chunk.payload()+260*i;StringBuilder name=new StringBuilder();
                for(int p=at+4;p<at+260;p+=2) { int c=u16(p);if(c==0) break;name.append((char)c); }
                emit(row(name.toString(),at,260).put("chunkIndex",chunk.index).put("ordinal",i).put("packageId",u32(at))
                    .put("packageNameRawUtf16Hex",Inspector.toHex(document.storedBytes(at+4,256))));
            }
        }
        end("libraries");
    }
}
