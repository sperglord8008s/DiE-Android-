// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Integration test: actual native tool calls on a known ELF fixture. */
public final class NativeToolsTest {
 static void require(boolean c,String message){if(!c)throw new AssertionError(message);}
 static String call(String path,int id,long offset,int mode){
  String s=Engine.tool(path,id,offset,mode,4);
  require(s!=null&&!s.isEmpty()&&!s.startsWith("ERROR:"),"Tool "+id+": "+s);
  return s;
 }
 static void contains(String output,String term){require(output.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT)),"Expected "+term+" in "+output.substring(0,Math.min(output.length(),1000)));}
 public static void main(String[] args)throws Exception{
  require(Engine.AVAILABLE,"JNI did not load");
  String p=args[0];String structures=call(p,0,-1,0);contains(structures,"ELF");
  String map=call(p,1,-1,0);contains(map,"ELF");contains(map,"off=");
  String sections=call(p,1,-1,1);contains(sections,".text");
  String imports=call(p,2,-1,0);contains(imports,"puts");
  String exports=call(p,3,-1,0);contains(exports,"die_fixture_export");
  String symbols=call(p,4,-1,0);contains(symbols,"die_fixture_export");
  String disasm=call(p,6,-1,0);contains(disasm,"NEXT=0x");
  String hashes=call(p,7,-1,0);contains(hashes,Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(p)))));
  String strings=call(p,8,-1,14);contains(strings,"DIE_FIXTURE_MARKER");
  // Unsupported input must fail explicitly, never invent a decoded structure.
  Path raw=Files.createTempFile("die-test-raw",".bin");
  try{Files.write(raw,new byte[]{1,2,3,4,5,6});String error=Engine.tool(raw.toString(),0,-1,0,4);require(error.startsWith("ERROR:"),"Raw structural result unexpected: "+error);}
  finally{Files.deleteIfExists(raw);}
  if(args.length>1){
   String db=args[1],custom=args[2];
   String types=Engine.desktop(p,db,custom,0,1|2|16|64|4096,0,"","","","");
   contains(types,"\"ok\":true");contains(types,"ELF");
   String facts=Engine.desktop(p,db,custom,5,1|2|16,0,"","","","");
   contains(facts,"\"ok\":true");contains(facts,"\"entryOffset\":");
   String signatures=Engine.desktop(p,db,custom,2,1|2|16,0,"","","","");
   contains(signatures,"\"ok\":true");contains(signatures,".sg");
   String scan=Engine.desktop(p,db,custom,1,1|2|16|64,0,"","","","");
   contains(scan,"\"ok\":true");contains(scan,"\"rows\":");
   System.out.println("Desktop backend database, type, facts and scan checks passed.");
  }
  String entropy=call(p,10,-1,0);require(!entropy.isEmpty(),"Entropy report empty");
  System.out.println("Native JNI fixture checks passed.");
 }
}
