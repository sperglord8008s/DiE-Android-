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
 static long number(String output,String key){java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\""+key+"\\\":(\\d+)").matcher(output);require(m.find(),"Missing numeric field "+key);return Long.parseLong(m.group(1));}
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
   Path rule=Path.of(custom,"ELF","tool_DiE_Fixture.sg");
   Files.createDirectories(rule.getParent());
   String source="meta(\"tool\", \"DIE_JNI_KNOWN_DETECTION\"); function detect() { bDetected = ELF.getSize() > 0; return result(); }";
   Files.writeString(rule,source);
   String types=Engine.desktop(p,db,custom,0,1|2|16|64|4096,0,"","","","");
   contains(types,"\"ok\":true");contains(types,"ELF");
   String facts=Engine.desktop(p,db,custom,5,1|2|16,0,"","","","");
   contains(facts,"\"ok\":true");contains(facts,"\"entryOffset\":");
   String signatures=Engine.desktop(p,db,custom,2,1|2|16,0,"","","","");
   contains(signatures,"\"ok\":true");contains(signatures,".sg");
   String scan=Engine.desktop(p,db,custom,1,1|2|16|64,0,"","","","");
   contains(scan,"\"ok\":true");contains(scan,"\"rows\":");
   require(!scan.contains("DIE_JNI_KNOWN_DETECTION"),"Disabled custom database contributed a match");
   String enabled=Engine.desktop(p,db,custom,1,1|2|16|64|2048|8192,0,"","","","");
   contains(enabled,"DIE_JNI_KNOWN_DETECTION");contains(enabled,rule.toString());
   contains(enabled,"\"cacheHits\":0");
   String cached=Engine.desktop(p,db,custom,1,1|2|16|64|2048|8192,0,"","","","");
   contains(cached,"DIE_JNI_KNOWN_DETECTION");contains(cached,"\"cacheHits\":1");
   String ruleSource=Engine.desktop(p,db,custom,3,1|2|16|2048,0,"","",rule.toString(),"");
   contains(ruleSource,source.substring(0,4));contains(ruleSource,"DIE_JNI_KNOWN_DETECTION");
   String single=Engine.desktop(p,db,custom,4,1|2|16|2048,0,"","",rule.toString(),source);
   contains(single,"\"ok\":true");contains(single,"DIE_JNI_KNOWN_DETECTION");
   String filtered=Engine.desktop(p,db,custom,1,16|2048,0,"DIE_NO_SUCH_TAG","","","");
   require(!filtered.contains("DIE_JNI_KNOWN_DETECTION"),"Include-tag filter was ignored");
   // Changing file bytes must miss the cache even if size and path are unchanged.
   Path copy=Path.of(args[3],"cache-fixture");Files.copy(Path.of(p),copy);
   String beforeChange=Engine.desktop(copy.toString(),db,custom,1,16|8192,0,"","","","");
   byte[] changed=Files.readAllBytes(copy);changed[changed.length-1]^=1;Files.write(copy,changed);
   String refreshed=Engine.desktop(copy.toString(),db,custom,1,16|8192,0,"","","","");
   require(number(refreshed,"cacheMisses")==number(beforeChange,"cacheMisses")+1,"Changed bytes reused cached scan");
   System.out.println("Desktop backend database, type, facts and scan checks passed.");
   for(String fixture:new String[]{"pe32.exe","pe64.exe","mach64.bin"}){
    String binary=Path.of(args[3],fixture).toString();
    contains(call(binary,0,-1,0),fixture.startsWith("pe")?"IMAGE":"mach");
    contains(call(binary,1,-1,fixture.startsWith("pe")?1:2),fixture.startsWith("pe")?".text":"__TEXT");
    String factsJson=Engine.desktop(binary,db,custom,5,16,0,"","","","");
    contains(factsJson,"\"ok\":true");
    contains(factsJson,fixture.startsWith("pe")?"\"entryOffset\":512":"\"entryOffset\":4096");
    contains(call(binary,6,-1,0),"NEXT=0x");
   }
   for(String fixture:new String[]{"empty.dex","archive.zip"}){
    String typesJson=Engine.desktop(Path.of(args[3],fixture).toString(),db,custom,0,16,0,"","","","");
    contains(typesJson,fixture.endsWith("dex")?"DEX":"ZIP");
   }
   for(String fixture:new String[]{"truncated-elf.bin","truncated-pe.bin","truncated-mach.bin"}){
    String invalid=Engine.tool(Path.of(args[3],fixture).toString(),0,-1,0,4);
    require(invalid.startsWith("ERROR:"),"Malformed fixture produced a structural report: "+fixture+" "+invalid);
   }
   System.out.println("PE32/PE64/Mach-O maps, facts and disassembly; DEX/ZIP identification; malformed headers passed.");
  }
  String entropy=call(p,10,-1,0);require(!entropy.isEmpty(),"Entropy report empty");
  System.out.println("Native JNI fixture checks passed.");
 }
}
