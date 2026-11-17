// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;
import java.io.*;
import java.nio.file.*;
public class InspectorTest {
 public static void main(String[] args)throws Exception{
  File f=File.createTempFile("inspect",".bin");try{
   Files.write(f.toPath(),new byte[4096]);Inspector.Result a=Inspector.scan(f,4);
   if(a.entropy!=0||a.size!=4096||a.blocks.length!=1)throw new AssertionError("zeros");
   byte[] bytes=new byte[4096];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;Files.write(f.toPath(),bytes);
   if(Math.abs(Inspector.scan(f,4).entropy-8)>1e-9)throw new AssertionError("uniform");
   Files.write(f.toPath(),"hello".getBytes(java.nio.charset.StandardCharsets.US_ASCII));a=Inspector.scan(f,4);
   if(!a.sha256.equals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")||!a.strings.contains("hello"))throw new AssertionError("hash or EOF string");
   byte[] elf=new byte[20];elf[0]=127;elf[1]='E';elf[2]='L';elf[3]='F';elf[4]=2;elf[5]=1;elf[18]=(byte)183;
   if(!Inspector.identify(elf,20).contains("AArch64"))throw new AssertionError("ELF");
   Files.write(f.toPath(),new byte[128*1024]);
   java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
   try{Inspector.scan(f,4,()->checks.incrementAndGet()>=3);throw new AssertionError("Inspection ignored cancellation");}
   catch(InterruptedIOException expected){}
   if(Inspector.scan(f,4).size!=128*1024)throw new AssertionError("Inspection did not recover after cancellation");
   Files.write(f.toPath(),new byte[0]);
   try{Inspector.scan(f,4,()->true);throw new AssertionError("Empty-input pre-cancellation ignored");}
   catch(InterruptedIOException expected){}
   System.out.println("Inspector tests passed");
  }finally{f.delete();}
 }
}
