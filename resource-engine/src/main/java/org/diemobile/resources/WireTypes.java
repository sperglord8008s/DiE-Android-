// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile.resources;

import java.io.*;
import java.util.*;
import org.diemobile.resources.ResourceWire.*;

/** Dense, offset16, sparse and compact entries retain their actual stored extents. */
final class WireTypes {
    static TypeInfo read(WireInput input,ChunkInfo chunk) throws IOException {
        int at=chunk.offset,end=chunk.end();
        if (chunk.headerSize<48) throw WireInput.fail("Truncated resource type/configuration header",at);
        int type=input.u8(at+8),flags=input.u8(at+9);
        if ((flags&~3)!=0) throw WireInput.fail("Unsupported resource type offset flags",at+9);
        int count=input.count(at+12,65536,"Resource entry slots");
        input.slots+=count;
        if (input.slots>WireInput.MAX_SLOTS) throw WireInput.fail("Resource types exceed one million offset slots",at+12);
        int configSize=input.count(at+20,chunk.headerSize-20,"Resource configuration size");
        if (configSize<28) throw WireInput.fail("Unsupported resource configuration smaller than 28 bytes",at+20);
        boolean sparse=(flags&1)!=0,offset16=(flags&2)!=0;
        int stride=sparse||!offset16 ? 4 : 2;
        input.range(chunk.payload(),(long)stride*count,chunk.payload(),end);
        int tableEnd=chunk.payload()+stride*count;
        int entriesStart=input.relative(at,input.u32(at+16),tableEnd,end);
        int[] indices=sparse ? new int[count] : null,rawOffsets=new int[count];
        List<EntryInfo> entries=new ArrayList<>();
        for (int ordinal=0;ordinal<count;ordinal++) {
            input.check();
            int slot=chunk.payload()+ordinal*stride,index=sparse ? input.u16(slot) : ordinal;
            long raw=sparse ? input.u16(slot+2) : offset16 ? input.u16(slot) : input.u32(slot);
            rawOffsets[ordinal]=(int)raw;if (sparse) indices[ordinal]=index;
            if (!sparse && raw==(offset16?0xffffL:0xffffffffL)) continue;
            if (++input.entries>WireInput.MAX_VALUES) throw WireInput.fail("Defined resource entries exceed 200,000 records",slot);
            long relative=sparse||offset16 ? raw*4 : raw;
            int position=input.relative(entriesStart,relative,entriesStart,end);
            input.range(position,8,entriesStart,end);
            int first=input.u16(position),entryFlags=input.u16(position+2),key,value=-1,typeOffset=-1,size;
            boolean compact=(entryFlags&8)!=0,complex=(entryFlags&1)!=0;
            List<Integer> maps=new ArrayList<>();
            if (compact) {
                if (complex) throw WireInput.fail("Resource entry declares both compact and complex layouts",position);
                input.valueType(entryFlags>>>8,position+3);
                if (++input.values>WireInput.MAX_VALUES) throw WireInput.fail("Resource values exceed 200,000 records",position);
                key=first;size=8;value=position+4;typeOffset=position+3;
            } else {
                key=input.count(position+4,Integer.MAX_VALUE,"Resource key index");
                if (first!=(complex?16:8)) throw WireInput.fail("Unsupported resource entry header size",position);
                if (complex) {
                    input.range(position,16,entriesStart,end);
                    int members=input.count(position+12,65536,"Complex resource mappings");
                    input.range(position+16,12L*members,entriesStart,end);size=16+12*members;
                    for (int i=0;i<members;i++) {
                        input.check();int map=position+16+12*i;input.value(map+4,end);maps.add(map);
                    }
                } else { input.value(position+8,end);size=16;value=position+8;typeOffset=value+3; }
            }
            entries.add(new EntryInfo(ordinal,index,position,size,entryFlags,key,value,typeOffset,compact,maps));
        }
        return new TypeInfo(chunk,type,flags,count,entriesStart,at+20,configSize,entries,indices,rawOffsets,stride);
    }
    private WireTypes() {}
}
