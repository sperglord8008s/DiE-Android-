// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile.resources;

import java.io.*;
import java.util.*;
import org.diemobile.resources.ResourceWire.*;

/** Exact string/style storage locations; UTF-8 and Android modified UTF-8 coexist. */
final class WirePools {
    static PoolInfo read(WireInput input,ChunkInfo chunk) throws IOException {
        int at=chunk.offset,end=chunk.end();
        if (chunk.headerSize<28) throw WireInput.fail("Truncated string-pool header",at);
        int count=input.count(at+8,WireInput.MAX_STRINGS,"String pool"),styles=input.count(at+12,count,"String styles");
        input.strings+=count;
        if (input.strings>WireInput.MAX_STRINGS) throw WireInput.fail("Resource string pools exceed 200,000 logical strings",at+8);
        long flags=input.u32(at+16),stringRelative=input.u32(at+20),styleRelative=input.u32(at+24);
        input.range(chunk.payload(),4L*(count+styles),chunk.payload(),end);
        int tableEnd=chunk.payload()+4*(count+styles);
        int stringsStart=input.relative(at,stringRelative,at,end);
        int stylesStart=styleRelative==0 ? 0 : input.relative(at,styleRelative,tableEnd,end);
        if (count>0 && stringsStart<tableEnd || count==0 && stringRelative!=0 && stringsStart<tableEnd
            || styles>0 && stylesStart==0 || stylesStart!=0 && stylesStart<stringsStart)
            throw WireInput.fail("String/style data crosses its offset tables or data areas",at+20);
        int stringsEnd=stylesStart==0 ? end : stylesStart;
        boolean utf8=(flags&0x100)!=0;
        List<StringInfo> strings=new ArrayList<>(count);
        for (int i=0;i<count;i++) {
            input.check();
            int start=input.relative(stringsStart,input.u32(chunk.payload()+i*4),stringsStart,stringsEnd);
            int[] cursor={start};
            int units=length(input,cursor,stringsEnd,utf8);
            if (units>WireInput.MAX_STRING_UNITS || units>WireInput.MAX_TOTAL_UNITS-input.units)
                throw WireInput.fail("Resource strings exceed per-value/aggregate UTF-16 budgets",start);
            input.units+=units;
            int bytes=utf8 ? length(input,cursor,stringsEnd,true) : units*2;
            int terminator=utf8 ? 1 : 2,payload=cursor[0];
            input.range(payload,(long)bytes+terminator,stringsStart,stringsEnd);
            if (utf8) {
                if (input.u8(payload+bytes)!=0 || countUtf16(input,payload,payload+bytes)!=units)
                    throw WireInput.fail("UTF-8/MUTF-8 length or terminator differs from string-pool declarations",start);
            } else if (input.u16(payload+bytes)!=0) throw WireInput.fail("UTF-16 string-pool terminator is absent",start);
            strings.add(new StringInfo(i,start,payload,bytes,payload+bytes+terminator-start,units,utf8));
        }
        List<SpanInfo> spans=new ArrayList<>();
        List<Integer> styleOffsets=new ArrayList<>(styles);
        for (int i=0;i<styles;i++) {
            input.check();long relative=input.u32(chunk.payload()+4*(count+i));
            styleOffsets.add((int)relative);
            if (relative==0xffffffffL) continue; // Explicit absent style, not an offset of -1.
            int cursor=input.relative(stylesStart,relative,stylesStart,end),ordinal=0;
            for (;;) {
                input.check();input.range(cursor,4,stylesStart,end);long name=input.u32(cursor);
                if (name==0xffffffffL) break;
                input.range(cursor,12,stylesStart,end);
                long first=input.u32(cursor+4),last=input.u32(cursor+8);
                if (name>=count || first>last || last>=strings.get(i).utf16Units)
                    throw WireInput.fail("Style name/range crosses its string pool or styled UTF-16 value",cursor);
                if (++input.spans>WireInput.MAX_VALUES) throw WireInput.fail("Resource style spans exceed 200,000 records",cursor);
                spans.add(new SpanInfo(i,ordinal++,cursor,(int)name,(int)first,(int)last));cursor+=12;
            }
        }
        return new PoolInfo(chunk,flags,styles,stringsStart,stylesStart,strings,spans,styleOffsets);
    }
    private static int length(WireInput input,int[] cursor,int end,boolean utf8) throws IOException {
        int at=cursor[0];input.range(at,utf8?1:2,0,end);
        int first=utf8 ? input.u8(at) : input.u16(at);
        int width=utf8 ? 1 : 2,mask=utf8 ? 0x80 : 0x8000;
        cursor[0]+=width;
        if ((first&mask)==0) return first;
        input.range(cursor[0],width,0,end);
        int next=utf8 ? input.u8(cursor[0]) : input.u16(cursor[0]);cursor[0]+=width;
        return ((first&(mask-1))<<(utf8?8:16))|next;
    }
    private static int countUtf16(WireInput input,int start,int end) throws IOException {
        int units=0;
        for (int at=start;at<end;) {
            if ((at&4095)==0) input.check();
            int first=input.u8(at++);
            if (first<0x80) { units++;continue; }
            int extra=first>=0xc0&&first<=0xdf ? 1 : first>=0xe0&&first<=0xef ? 2 : first>=0xf0&&first<=0xf4 ? 3 : -1;
            if (extra<0) throw WireInput.fail("Invalid UTF-8/MUTF-8 lead byte",at-1);
            input.range(at,extra,start,end);
            int value=first&((1<<(6-extra))-1);
            for (int i=0;i<extra;i++) {
                int next=input.u8(at++);
                if ((next&0xc0)!=0x80) throw WireInput.fail("Invalid UTF-8/MUTF-8 continuation byte",at-1);
                value=(value<<6)|(next&0x3f);
            }
            boolean modifiedNull=extra==1&&value==0&&first==0xc0;
            if (!modifiedNull && value<(extra==1?0x80:extra==2?0x800:0x10000) || value>0x10ffff)
                throw WireInput.fail("Overlong/out-of-range UTF-8/MUTF-8 code point",at-extra-1);
            // Three-byte surrogate code units are valid modified UTF-8 evidence.
            units+=extra==3 ? 2 : 1;
        }
        return units;
    }
    private WirePools() {}
}
