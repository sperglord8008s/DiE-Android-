// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile.resources;

import com.google.devrel.gmscore.tools.apk.arsc.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Google resource models after bounded wire acquisition; raw offsets stay separate. */
public final class ValidatedResources {
    public final ResourceWire wire;
    public final ResourceFile file;
    public final Map<Integer,Chunk> chunks;
    private final byte[] bytes;
    private final BooleanSupplier cancelled;
    private ValidatedResources(byte[] ownedBytes,BooleanSupplier cancelled) throws IOException {
        this.bytes=ownedBytes;this.cancelled=cancelled;
        wire=ResourceWire.read(ownedBytes,cancelled);
        Map<Integer,Chunk> index=new LinkedHashMap<>();
        try {
            file=new ResourceFile(ByteBuffer.wrap(ownedBytes).order(ByteOrder.LITTLE_ENDIAN));
            check();
            for (Chunk root:file.getChunks()) index(root,index);
            if (index.size()!=wire.chunks.size()) throw new IOException("Google resource parser and wire chunk inventories differ");
            for (ResourceWire.ChunkInfo item:wire.chunks) {
                check();Chunk parsed=index.get(item.offset);
                if (parsed==null || parsed.getHeaderSize()!=item.headerSize || parsed.getOriginalChunkSize()!=item.size
                    || DiEResourceAccess.type(parsed)!=item.type) throw WireInput.fail("Google resource model differs from its physical chunk declaration",item.offset);
            }
            for (ResourceWire.PoolInfo pool:wire.pools.values()) {
                StringPoolChunk parsed=(StringPoolChunk)index.get(pool.chunk.offset);
                if (parsed.getStringCount()!=pool.strings.size() || parsed.getStyleCount()!=pool.styleCount)
                    throw WireInput.fail("Google string/style model count differs from the wire",pool.chunk.offset);
                for (ResourceWire.StringInfo string:pool.strings) {
                    check();
                    if (parsed.getString(string.index).length()!=string.utf16Units) throw WireInput.fail("Decoded resource string changed its declared UTF-16 identity",string.headerOffset);
                }
                int spans=0;
                for (int i=0;i<pool.styleCount;i++) spans+=DiEResourceAccess.styleSpanCount(parsed,i);
                if (spans!=pool.spans.size()) throw WireInput.fail("Decoded style span count differs from complete wire evidence",pool.chunk.offset);
            }
            // Do not enumerate Entry.values() as authoritative raw bag evidence:
            // the upstream Map intentionally normalizes duplicate mapping keys.
            // ResourceWire retains every ordinal and stored map/value location.
            for (ResourceWire.TypeInfo type:wire.types.values()) {
                TypeChunk parsed=(TypeChunk)index.get(type.chunk.offset);
                StringPoolChunk keys=parsed.getPackageChunk().getKeyStringPool();
                int typeStrings=parsed.getPackageChunk().getTypeStringPool().getStringCount();
                if (type.typeId<1 || type.typeId>typeStrings) throw WireInput.fail("Resource type ID is outside its package's type string pool",type.chunk.offset+8);
                for (ResourceWire.EntryInfo entry:type.entries) {
                    check();
                    if (entry.keyIndex<0 || entry.keyIndex>=keys.getStringCount()) throw WireInput.fail("Resource key is outside its package's key string pool",entry.offset);
                }
            }
            chunks=Collections.unmodifiableMap(index);
        } catch (RuntimeException error) { throw new IOException("Google resource parser rejected the bounded input: "+error.toString(),error); }
        check();
    }
    /** Ownership is transferred: callers must not change this private byte array. */
    public static ValidatedResources read(byte[] ownedBytes,BooleanSupplier cancelled) throws IOException { return new ValidatedResources(ownedBytes,cancelled); }
    private void check() throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Android resource investigation cancelled");
    }
    private void index(Chunk chunk,Map<Integer,Chunk> index) throws IOException {
        check();
        if (index.put(DiEResourceAccess.offset(chunk),chunk)!=null) throw new IOException("Google resource parser returned duplicate chunk positions");
        if (chunk instanceof ChunkWithChunks) for (Chunk child:((ChunkWithChunks)chunk).getChunks().values()) index(child,index);
    }
    /** An exact stored range; this never serializes/normalizes the upstream model. */
    public byte[] storedBytes(int offset,int count) throws IOException {
        check();
        if (offset<0 || count<0 || count>bytes.length-offset) throw WireInput.fail("Requested resource bytes are outside the source",offset);
        return Arrays.copyOfRange(bytes,offset,offset+count);
    }
    public ResourceValue valueAt(int offset) throws IOException {
        check();
        if (offset<0 || offset>bytes.length-8) throw WireInput.fail("Resource value selector is outside the source",offset);
        ByteBuffer input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        input.position(offset);
        return ResourceValue.create(input);
    }
    public ResourceValue compactValueAt(int offset,int type) throws IOException {
        check();
        if(offset<0 || offset>bytes.length-4) throw WireInput.fail("Compact resource data selector is outside the source",offset);
        ByteBuffer input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        input.position(offset);
        return ResourceValue.createCompact(input,(byte)type);
    }
}
