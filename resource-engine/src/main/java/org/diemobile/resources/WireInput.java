// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile.resources;

import java.io.*;
import java.util.function.BooleanSupplier;

/** Unsigned little-endian reads, explicit physical bounds and shared budgets. */
final class WireInput {
    static final int MAX_BYTES=64*1024*1024, MAX_CHUNKS=100000, MAX_STRINGS=200000;
    static final int MAX_STRING_UNITS=4*1024*1024, MAX_TOTAL_UNITS=16*1024*1024;
    static final int MAX_SLOTS=1000000, MAX_VALUES=200000;
    final byte[] bytes;
    final BooleanSupplier cancelled;
    int strings, units, slots, entries, values, spans;
    WireInput(byte[] bytes, BooleanSupplier cancelled) throws IOException {
        if (bytes.length>MAX_BYTES) throw new IOException("Android resource input exceeds 64 MiB");
        this.bytes=bytes; this.cancelled=cancelled;
    }
    void check() throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Android resource investigation cancelled");
    }
    static IOException fail(String message, int offset) { return new IOException(message+" at physical 0x"+Integer.toHexString(offset)); }
    void range(int offset, long count, int begin, int end) throws IOException {
        if (begin<0 || end<begin || end>bytes.length || offset<begin || count<0 || count>(long)end-offset) throw fail("Android resource range crosses its enclosing physical bounds",offset);
    }
    int u8(int at) throws IOException { range(at,1,0,bytes.length); return bytes[at]&255; }
    int u16(int at) throws IOException { range(at,2,0,bytes.length); return (bytes[at]&255)|((bytes[at+1]&255)<<8); }
    long u32(int at) throws IOException {
        range(at,4,0,bytes.length);
        return (bytes[at]&255L)|((bytes[at+1]&255L)<<8)|((bytes[at+2]&255L)<<16)|((bytes[at+3]&255L)<<24);
    }
    int count(int at, int maximum, String kind) throws IOException {
        long value=u32(at);
        if (value>maximum) throw fail(kind+" exceeds the supported count budget",at);
        return (int)value;
    }
    int relative(int base, long relative, int begin, int end) throws IOException {
        if (relative<0 || relative>(long)end-base) throw fail("Relative Android resource offset crosses its enclosing chunk",base);
        int result=base+(int)relative; range(result,0,begin,end); return result;
    }
    void value(int at, int end) throws IOException {
        range(at,8,0,end);
        if (u16(at)!=8) throw fail("Unsupported Res_value size; exact 8-byte layout required",at);
        valueType(u8(at+3),at+3);
        if (++values>MAX_VALUES) throw fail("Android resource values exceed 200,000 records",at);
    }
    void cdataValue(int at,int end) throws IOException {
        range(at,8,0,end);
        // AAPT2 can leave the optional CDATA typed-value field entirely zero.
        // This is absent data, not a normal size-8 TYPE_NULL declaration.
        if(u32(at)==0&&u32(at+4)==0) {
            if(++values>MAX_VALUES) throw fail("Android resource values exceed 200,000 records",at);
        } else value(at,end);
    }
    void valueType(int type, int at) throws IOException {
        if (!(type<=8 || type>=16&&type<=18 || type>=28&&type<=31)) throw fail("Unsupported Android resource data type 0x"+Integer.toHexString(type),at);
    }
}
