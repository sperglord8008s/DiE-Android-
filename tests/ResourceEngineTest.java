// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.google.devrel.gmscore.tools.apk.arsc.*;
import org.diemobile.resources.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Real source-built Google models plus original wire identity and bounded failures. */
public final class ResourceEngineTest {
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static void rejected(byte[] bytes,String reason) throws Exception {
        try { ValidatedResources.read(bytes,()->false);throw new AssertionError("Malformed resource input accepted: "+reason); }
        catch(IOException expected) { require(expected.getMessage().contains(reason),"Unexpected resource rejection: "+expected); }
    }
    public static void main(String[] args) throws Exception {
        for(boolean utf8:new boolean[]{true,false}) {
            byte[] bytes=ResourceFixtureFactory.xml(utf8,true);
            ValidatedResources document=ValidatedResources.read(bytes,()->false);
            require(document.wire.chunks.size()==4 && document.chunks.size()==4,"XML chunk inventory differs");
            ResourceWire.PoolInfo pool=document.wire.pools.values().iterator().next();
            StringPoolChunk strings=(StringPoolChunk)document.chunks.get(pool.chunk.offset);
            require(strings.getString(4).equals("Fixture Ω 東京 😀"),"Unicode XML text changed");
            require(strings.getString(5).equals("embedded\0value"),"Embedded string-pool NUL changed");
            require(strings.getString(6).equals("isolated-\ud800"),"Isolated UTF-16 surrogate changed");
            require(pool.styleOffsets.equals(Arrays.asList(-1,0)) && pool.spans.size()==1,"Style hole/order/raw span evidence changed");
            require(DiEResourceAccess.styleSpanCount(strings,0)==0 && DiEResourceAccess.styleSpanCount(strings,1)==1,"Absent style normalized into a physical read");
            XmlStartElementChunk start=null;
            for(Chunk chunk:document.chunks.values()) if(chunk instanceof XmlStartElementChunk) start=(XmlStartElementChunk)chunk;
            require(start!=null && start.getAttributes().size()==2 && start.getAttributes().get(0).name().equals(start.getAttributes().get(1).name()),"Duplicate XML attribute evidence dropped");
            ResourceWire.StringInfo text=pool.strings.get(4);
            require(Arrays.equals(Arrays.copyOfRange(bytes,text.payloadOffset,text.payloadOffset+text.payloadBytes),document.storedBytes(text.payloadOffset,text.payloadBytes)),"Stored text range changed");
            byte[] invalid=bytes.clone();ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(pool.chunk.offset+8,0x7fffffff);
            rejected(invalid,"count budget");
            invalid=bytes.clone();invalid[text.payloadOffset+text.payloadBytes]=1;rejected(invalid,"terminator");
        }
        ValidatedResources longValue=ValidatedResources.read(ResourceFixtureFactory.longUtf16Xml(),()->false);
        ResourceWire.PoolInfo longPool=longValue.wire.pools.values().iterator().next();
        require(longPool.strings.get(0).utf16Units==33005 && longPool.strings.get(0).payloadOffset-longPool.strings.get(0).headerOffset==4,"Extended UTF-16 length prefix changed");
        byte[] table=ResourceFixtureFactory.table();ValidatedResources resources=ValidatedResources.read(table,()->false);
        require(resources.wire.types.size()==5 && resources.wire.entrySlots==11 && resources.wire.entryCount==9,"Resource type/slot/entry coverage differs");
        boolean sawDense=false,sawOffset16=false,sawSparse=false,sawCompact=false,sawBag=false;
        for(ResourceWire.TypeInfo type:resources.wire.types.values()) {
            if(type.flags==0 && type.declaredSlots==3) { sawDense=true;require(type.slotRawOffset(2)==0xffffffffL,"Dense NO_ENTRY changed"); }
            if(type.flags==2) { sawOffset16=true;require(type.slotStride==2 && type.slotRawOffset(2)==0xffff,"16-bit NO_ENTRY/stride changed"); }
            if(type.flags==1) { sawSparse=true;require(type.entries.size()==3 && type.entries.get(0).index==2 && type.entries.get(2).index==2 && type.entries.get(0).offset==type.entries.get(2).offset,"Sparse duplicate/alias records dropped"); }
            for(ResourceWire.EntryInfo entry:type.entries) {
                if(entry.compact) { sawCompact=true;require(entry.size==8 && entry.valueOffset==entry.offset+4 && entry.valueTypeOffset==entry.offset+3,"Compact logical value became a false 16-byte extraction extent"); }
                if((entry.flags&1)!=0) {
                    sawBag=true;require(entry.mapOffsets.size()==2,"Duplicate bag mapping keys dropped");
                    require(resources.valueAt(entry.mapOffsets.get(0)+4).data()==0 && resources.valueAt(entry.mapOffsets.get(1)+4).data()==1,"Stored duplicate bag values changed");
                    TypeChunk engine=(TypeChunk)resources.chunks.get(type.chunk.offset);
                    require(engine.getEntries().get(entry.index).values().size()==1,"Fixture should expose the upstream Map normalization; raw wire evidence must retain both");
                }
            }
        }
        require(sawDense&&sawOffset16&&sawSparse&&sawCompact&&sawBag,"Modern resource layouts missing");
        ValidatedResources legacy=ValidatedResources.read(ResourceFixtureFactory.table(true),()->false);
        for(Chunk chunk:legacy.chunks.values()) if(chunk instanceof PackageChunk)
            require(((PackageChunk)chunk).getTypeIdOffset()==0 && chunk.getHeaderSize()==284,"Absent legacy type-ID offset read bytes from the first child chunk");
        byte[] invalid=table.clone();ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(4,0);rejected(invalid,"chunk header");
        invalid=Arrays.copyOf(table,table.length-1);rejected(invalid,"chunk header");
        ResourceWire.TypeInfo first=resources.wire.types.values().iterator().next();
        invalid=table.clone();ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(first.chunk.offset+16,4);rejected(invalid,"enclosing");
        try { ValidatedResources.read(table,()->true);throw new AssertionError("Cancelled resource parse succeeded"); } catch(InterruptedIOException expected) {}
        System.out.println("Resource engine fixtures passed: actual Google parser, UTF-8/MUTF-8/UTF-16 and surrogate/NUL identity, style holes, duplicate attributes/maps, dense/offset16/sparse/compact entries, aliases, stored ranges, extended lengths, count/range/truncation limits and cancellation.");
    }
}
