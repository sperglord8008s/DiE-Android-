// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic Android wire fixtures, including forms a normal packager omits. */
public final class ResourceFixtureFactory {
    private static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        for(byte[] part:parts) output.write(part);
        return output.toByteArray();
    }
    private static byte[] aligned(byte[] bytes) { return Arrays.copyOf(bytes,(bytes.length+3)&~3); }
    private static byte[] words(int... values) {
        ByteBuffer bytes=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);
        for(int value:values) bytes.putInt(value);
        return bytes.array();
    }
    private static byte[] chunk(int type,int headerSize,byte[] header,byte[] payload) throws IOException {
        if(headerSize!=8+header.length) throw new AssertionError("Bad fixture header");
        byte[] data=aligned(payload);
        ByteBuffer prefix=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        prefix.putShort((short)type).putShort((short)headerSize).putInt(headerSize+data.length);
        return join(prefix.array(),header,data);
    }
    private static void length(ByteArrayOutputStream output,int count,boolean utf8) {
        if(utf8) { if(count>32767) throw new AssertionError("UTF-8 fixture length too large");if(count>127) output.write(0x80|(count>>>8));output.write(count&255); }
        else { if(count>32767) { output.write((count>>>16)&255);output.write(0x80|(count>>>24)); }output.write(count&255);output.write((count>>>8)&255); }
    }
    private static byte[] encoded(String value,boolean utf8) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        length(output,value.length(),utf8);
        if(utf8) {
            ByteArrayOutputStream modified=new ByteArrayOutputStream();
            try(DataOutputStream data=new DataOutputStream(modified)) { data.writeUTF(value); }
            byte[] storage=modified.toByteArray();length(output,storage.length-2,true);output.write(storage,2,storage.length-2);output.write(0);
        } else {
            for(int i=0;i<value.length();i++) { char c=value.charAt(i);output.write(c&255);output.write(c>>>8); }
            output.write(0);output.write(0);
        }
        return output.toByteArray();
    }
    public static byte[] pool(String[] values,boolean utf8,boolean styles) throws IOException {
        ByteArrayOutputStream data=new ByteArrayOutputStream();int styleCount=styles?2:0;
        ByteBuffer offsets=ByteBuffer.allocate(4*(values.length+styleCount)).order(ByteOrder.LITTLE_ENDIAN);
        for(String value:values) { offsets.putInt(data.size());data.write(encoded(value,utf8)); }
        byte[] strings=aligned(data.toByteArray());
        int start=28+offsets.capacity(),styleStart=styles?start+strings.length:0;
        byte[] spans=new byte[0];
        if(styles) { offsets.putInt(-1).putInt(0);spans=words(2,0,values[1].length()-1,-1,-1,-1); }
        return chunk(1,28,words(values.length,styleCount,utf8?256:0,start,styleStart),join(offsets.array(),strings,spans));
    }
    private static byte[] value(int type,int data) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short)8).put((byte)0).put((byte)type).putInt(data).array();
    }
    public static byte[] xml(boolean utf8,boolean styles) throws IOException {
        String[] strings={"manifest","styled","b","message","Fixture Ω 東京 😀","embedded\0value","isolated-\ud800"};
        byte[] pool=pool(strings,utf8,styles);
        ByteBuffer extension=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        extension.putInt(-1).putInt(0).putShort((short)20).putShort((short)20).putShort((short)2).putShort((short)0).putShort((short)0).putShort((short)0);
        byte[] attributeOne=join(words(-1,3,4),value(3,4)),attributeTwo=join(words(-1,3,5),value(3,5));
        byte[] start=chunk(0x102,16,words(1,-1),join(extension.array(),attributeOne,attributeTwo));
        byte[] end=chunk(0x103,16,words(2,-1),words(-1,0));
        return chunk(3,8,new byte[0],join(pool,start,end));
    }
    public static byte[] longUtf16Xml() throws IOException {
        return chunk(3,8,new byte[0],pool(new String[]{"long-"+String.join("",Collections.nCopies(33000,"x"))},false,false));
    }
    public static byte[] pagedXml() throws IOException {
        String[] values=new String[2051];
        for(int i=0;i<values.length;i++) values[i]="pool-value-"+i;
        values[1500]="long-"+String.join("",Collections.nCopies(9000,"x"))+"-full-search-tail";
        return chunk(3,8,new byte[0],pool(values,false,false));
    }
    /** Generic wire tree for duplicate/ambiguous declarations a normal packager rejects. */
    public static byte[] xmlTree(String[] strings,int[] resourceIds,int[] elementNames,int[] depths,int[][][] attributes) throws IOException {
        ByteArrayOutputStream events=new ByteArrayOutputStream();List<Integer> stack=new ArrayList<>();
        for(int i=0;i<elementNames.length;i++) {
            if(depths[i]<0||depths[i]>stack.size())throw new AssertionError("Invalid fixture depth");
            while(stack.size()>depths[i]) { int name=stack.remove(stack.size()-1);events.write(chunk(0x103,16,words(2,-1),words(-1,name))); }
            ByteBuffer extension=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            extension.putInt(-1).putInt(elementNames[i]).putShort((short)20).putShort((short)20).putShort((short)attributes[i].length).putShort((short)0).putShort((short)0).putShort((short)0);
            ByteArrayOutputStream values=new ByteArrayOutputStream();
            for(int[] attribute:attributes[i])values.write(join(words(attribute[0],attribute[1],attribute[2]),value(attribute[3],attribute[4])));
            events.write(chunk(0x102,16,words(1,-1),join(extension.array(),values.toByteArray())));stack.add(elementNames[i]);
        }
        while(!stack.isEmpty()) {int name=stack.remove(stack.size()-1);events.write(chunk(0x103,16,words(2,-1),words(-1,name)));}
        return chunk(3,8,new byte[0],join(pool(strings,true,false),chunk(0x180,8,new byte[0],words(resourceIds)),events.toByteArray()));
    }
    private static byte[] entry(int key,int type,int data) throws IOException { return join(words(8,key),value(type,data)); }
    private static byte[] type(int flags,int[] indices,int[] offsets,byte[] entries) throws IOException {
        boolean sparse=(flags&1)!=0,shortOffsets=(flags&2)!=0;
        ByteBuffer array=ByteBuffer.allocate(offsets.length*(sparse||!shortOffsets?4:2)).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<offsets.length;i++) {
            if(sparse) array.putShort((short)indices[i]).putShort((short)offsets[i]);
            else if(shortOffsets) array.putShort((short)offsets[i]);else array.putInt(offsets[i]);
        }
        byte[] table=aligned(array.array());
        ByteBuffer header=ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte)1).put((byte)flags).putShort((short)0).putInt(offsets.length).putInt(48+table.length);
        header.putInt(28); // complete oldest configuration, with SDK=35 below
        header.putShort(36,(short)35);
        return chunk(0x201,48,header.array(),join(table,entries));
    }
    public static byte[] table() throws IOException { return table(false); }
    public static byte[] table(boolean legacyPackage) throws IOException {
        byte[] global=pool(new String[]{"Resource Ω","Second value"},true,false);
        byte[] typeNames=pool(new String[]{"string"},true,false);
        byte[] keyNames=pool(new String[]{"title","enabled","bag"},false,false);
        byte[] normal=join(entry(0,3,0),entry(1,18,-1));
        byte[] dense=type(0,null,new int[]{0,16,-1},normal);
        byte[] offset16=type(2,null,new int[]{0,4,0xffff},normal);
        byte[] sparse=type(1,new int[]{2,0,2},new int[]{0,4,0},normal);
        byte[] compact=type(0,null,new int[]{0},ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short)0).putShort((short)0x0308).putInt(0).array());
        byte[] bag=join(words(0x00010010,2,0x7f010000,2),words(0x01000000),value(3,0),words(0x01000000),value(3,1));
        byte[] complex=type(0,null,new int[]{0},bag);
        byte[] spec=chunk(0x202,16,new byte[]{1,0,0,0,3,0,0,0},words(0x40000000,0x40000000,0));
        int packageSize=legacyPackage?284:288;
        ByteBuffer packageHeader=ByteBuffer.allocate(packageSize-8).order(ByteOrder.LITTLE_ENDIAN);
        packageHeader.putInt(0x7f);
        byte[] name="org.diemobile.fixture".getBytes(StandardCharsets.UTF_16LE);packageHeader.put(name);packageHeader.position(260);
        packageHeader.putInt(packageSize).putInt(1).putInt(packageSize+typeNames.length).putInt(3);
        if(!legacyPackage) packageHeader.putInt(0);
        byte[] pkg=chunk(0x200,packageSize,packageHeader.array(),join(typeNames,keyNames,spec,dense,offset16,sparse,compact,complex));
        return chunk(2,12,words(1),join(global,pkg));
    }
    private ResourceFixtureFactory() {}
}
