// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

final class KnowledgeReport {
 static final int MAX_ENTRIES=10000, MAX_CLASSES=120000, MAX_DEX=32*1024*1024;
 static final long MAX_TOTAL_DEX=96L*1024*1024;
 static final class Output {
  StringBuilder guide=new StringBuilder(), apk=new StringBuilder();
  void section(String s){apk.append("\n").append(s).append("\n");}
  void item(String s){apk.append("• ").append(s).append("\n");}
 }
 static String read(InputStream in,int max)throws IOException{
  return new String(bytes(in,max),StandardCharsets.UTF_8);
 }
 static byte[] bytes(InputStream in,int max)throws IOException{
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[32768];int n;
  while((n=in.read(buf))!=-1){if(n>max-out.size())throw new IOException("Size limit exceeded");out.write(buf,0,n);}
  return out.toByteArray();
 }
 static JSONObject asset(Context c,String path)throws Exception{
  try(InputStream in=c.getAssets().open(path)){return new JSONObject(read(in,16*1024*1024));}
 }
 static Output analyze(Context c,File file,Inspector.Result r,String displayName){
  Output o=new Output();
  o.guide.append("FILE 101\n\n").append(displayName).append("\n\n");
  o.guide.append("Observed format: ").append(r.type).append("\n");
  String t=r.type;
  if(t.startsWith("ELF"))o.guide.append("An Executable and Linkable Format file holds native machine code or related data. Android uses ELF for executables and .so shared libraries. Architecture tells you which processor instruction set the code targets, not what it does. A library can expose functions to other programs; file type alone cannot reveal behavior.\n");
  else if(t.contains("DEX"))o.guide.append("Dalvik Executable (DEX) contains Android bytecode, normally executed by ART. One APK may contain several DEX files. Class names can reveal bundled SDKs, but names can be renamed by obfuscation and do not prove that a class is called.\n");
  else if(t.contains("ZIP"))o.guide.append("A ZIP container groups files, often compressing them. An APK is a specialized ZIP containing an Android manifest, code, resources and possibly native libraries. An ordinary ZIP is not necessarily an app.\n");
  else if(t.contains("MZ"))o.guide.append("MZ is a historical executable header also used at the beginning of Windows PE files. It is a candidate marker—not sufficient by itself to establish a valid executable or processor type.\n");
  else if(t.equals("PDF"))o.guide.append("PDF is a document format which can include text, images, forms, embedded files and scripts. This scanner does not render or execute its content.\n");
  else if(t.equals("PNG"))o.guide.append("PNG is a losslessly compressed raster image format. A magic-byte match does not validate every chunk or establish what the picture contains.\n");
  else o.guide.append("No recognized header in the local inspector. It may be an unsupported format, raw data, a fragment, compressed data or encrypted data. Check the CDiE results for additional format evidence.\n");
  o.guide.append("\nSIZE AND IDENTITY\n").append(r.size).append(" bytes. Size is the amount of stored data, not a measure of complexity.\nSHA-256: ").append(r.sha256).append("\nA content fingerprint: matching hashes strongly indicate identical bytes, but a hash alone does not tell you who made the file or if it is safe.\n\nENTROPY\n");
  o.guide.append(String.format(Locale.ROOT,"%.4f of 8 bits per byte. ",r.entropy));
  o.guide.append("Entropy estimates how evenly byte values occur. Compression, encryption, images and random data can all produce high entropy. Low entropy may reflect repeated data or padding. Neither establishes malware or encryption. Use the graph to locate transitions, then inspect the corresponding content.\n\nSIGNATURES\nCDiE compares file structures and patterns against its database. A matching compiler, packer or SDK identifies a possible technology, not a verified author or runtime behavior. A missed match does not establish absence.\n\nSTRINGS AND HEX\nStrings are printable byte sequences and may expose names or configuration; they may also be unused or coincidental. Hex displays original bytes; an offset is a position in the file, not always a virtual memory address. Previews are bounded.\n\nLIMITS\nStatic analysis only: no app is installed, executed, or submitted online. Results are not a safety verdict. Malformed, encrypted, split or obfuscated content may be missed. Individual detection details in Scan show underlying rule output.\n");
  if(t.startsWith("ELF"))try{elf(file,o);}catch(Exception e){o.guide.append("\nELF header detail unavailable: ").append(e.getMessage()).append("\n");}
  if(t.contains("ZIP"))try{archive(c,file,o);}catch(Exception e){o.item("Archive inspection incomplete: "+e.getMessage());}
  else if(t.contains("DEX"))try{Set<String> classes=new TreeSet<>();try(InputStream in=new FileInputStream(file)){dex(bytes(in,MAX_DEX),classes);}o.section("DEX class inspection");o.item("Defined classes inspected: "+classes.size());trackers(c,classes,o); }catch(Exception e){o.item("DEX inspection incomplete: "+e.getMessage());}
  else o.apk.append("Not a recognized ZIP/APK or DEX input. Use File info / File 101 for general interpretation.\n");
  return o;
 }
 static void elf(File file,Output o)throws IOException{
  try(RandomAccessFile in=new RandomAccessFile(file,"r")){
   byte[] b=new byte[(int)Math.min(64,in.length())];in.readFully(b);
   if(b.length<16||(b[4]!=1&&b[4]!=2)||(b[5]!=1&&b[5]!=2))throw new IOException("Unsupported ELF class or byte order");
   boolean bits=b[4]==2,le=b[5]==1;
   if(b.length<(bits?64:52))throw new IOException("Truncated header");
   int type=(int)num(b,16,2,le);long entry=num(b,24,bits?8:4,le);
   int ph=(int)num(b,bits?56:44,2,le),sh=(int)num(b,bits?60:48,2,le);
   o.guide.append("\nELF HEADER (direct observation)\n").append(bits?"64-bit":"32-bit").append(", ").append(le?"little-endian":"big-endian").append("\nObject type: ").append(type==1?"relocatable object":type==2?"executable":type==3?"shared object / position-independent executable":type==4?"core dump":"numeric "+type).append("\nEntry point: 0x").append(Long.toUnsignedString(entry,16)).append("\nProgram headers: ").append(ph).append(" (loader mapping descriptions)\nSection headers: ").append(sh).append(" (linking/analysis descriptions; zero can indicate absence or extended numbering)\nAn entry point is a virtual address, not a file offset.\n");
  }
 }
 static long num(byte[] b,int off,int n,boolean le)throws IOException{
  if(off<0||n<1||off>b.length-n)throw new IOException("Header offset out of range");long v=0;
  for(int i=0;i<n;i++)v=(v<<8)|(b[off+(le?n-1-i:i)]&255L);return v;
 }
 static int u32(byte[] b,int off)throws IOException{long n=num(b,off,4,true);if(n>Integer.MAX_VALUE)throw new IOException("DEX offset too large");return (int)n;}
 static void table(byte[] b,int off,int count,int width)throws IOException{if(off<0||count<0||((long)off+(long)count*width)>b.length)throw new IOException("DEX table outside file");}
 static void dex(byte[] b,Set<String> out)throws IOException{
  if(b.length<112||b[0]!='d'||b[1]!='e'||b[2]!='x'||b[3]!='\n'||num(b,40,4,true)!=0x12345678L)throw new IOException("Unsupported/truncated DEX");
  int ns=u32(b,56),so=u32(b,60),nt=u32(b,64),to=u32(b,68),nc=u32(b,96),co=u32(b,100);
  table(b,so,ns,4);table(b,to,nt,4);table(b,co,nc,32);
  if(nc>MAX_CLASSES||out.size()+nc>MAX_CLASSES)throw new IOException("Class inspection limit exceeded");
  for(int i=0;i<nc;i++){
   int ti=u32(b,co+i*32);if(ti>=nt)throw new IOException("Invalid class type");
   int si=u32(b,to+ti*4);if(si>=ns)throw new IOException("Invalid descriptor index");
   int p=u32(b,so+si*4),len=0;boolean terminated=false;
   for(int j=0;j<5&&p<b.length;j++){int x=b[p++]&255;if((x&128)==0){terminated=true;break;}}
   if(!terminated)throw new IOException("Invalid DEX string length");
   int start=p;while(p<b.length&&b[p]!=0&&len<2048){p++;len++;}
   if(p>=b.length||b[p]!=0)continue;
   if(len>2&&b[start]=='L'&&b[p-1]==';'){
    // Class identifiers used by current literal signature matcher are ASCII.
    String d=new String(b,start+1,len-2,StandardCharsets.UTF_8).replace('/','.');
    if(d.matches("[A-Za-z0-9_.$]+"))out.add(d);
   }
  }
 }
 static void archive(Context c,File file,Output o)throws Exception{
  Set<String> nativeNames=new TreeSet<>(),abis=new TreeSet<>(),classes=new TreeSet<>();List<String> entries=new ArrayList<>();
  boolean manifest=false;int count=0,dexCount=0;long total=0,dexBytes=0;boolean partial=false;
  try(ZipFile zip=new ZipFile(file)){
   Enumeration<? extends ZipEntry> es=zip.entries();
   while(es.hasMoreElements()){
    ZipEntry e=es.nextElement();if(++count>MAX_ENTRIES){partial=true;break;}
    String n=e.getName();if(e.getSize()>0&&Long.MAX_VALUE-total>=e.getSize())total+=e.getSize();
    if(entries.size()<1000)entries.add(n+"  ["+e.getSize()+" bytes]");
    if(n.equals("AndroidManifest.xml"))manifest=true;
    if(n.startsWith("lib/")&&n.endsWith(".so")){String[] parts=n.split("/");if(parts.length==3){abis.add(parts[1]);nativeNames.add(parts[2]);}}
    if(n.matches("classes([0-9]+)?\\.dex")){
     dexCount++;
     if(e.getSize()<0||e.getSize()>MAX_DEX||dexBytes+e.getSize()>MAX_TOTAL_DEX){partial=true;continue;}
     try(InputStream in=zip.getInputStream(e)){byte[] b=bytes(in,MAX_DEX);dexBytes+=b.length;dex(b,classes);}catch(Exception ex){partial=true;}
    }
   }
  }
  o.section(manifest?"APK structure":"ZIP structure");o.item("Entries inspected: "+Math.min(count,MAX_ENTRIES));o.item("Declared expanded bytes: "+total+" (header values, not verified decompressed size)");
  o.item("DEX files encountered: "+dexCount+"; ASCII defined class names inspected: "+classes.size());
  o.item("Native ABI folders: "+(abis.isEmpty()?"none seen":abis));if(partial)o.item("PARTIAL: archive/DEX limits or parser errors; results are not exhaustive.");
  JSONObject rules=null;try{rules=asset(c,"knowledge/libchecker.json");}catch(Exception e){o.item("LibChecker rule database unavailable: "+e.getMessage());}
  o.section("Native libraries — machine-code modules loaded by applications");
  for(String n:nativeNames){o.item(n);match(o,rules,"native-libs",n);}
  if(nativeNames.isEmpty())o.item("No conventional lib/<ABI>/*.so entries seen; code could be stored elsewhere.");
  if(manifest){o.guide.append("\nAPK FOUND\nThis archive contains AndroidManifest.xml. See APK / SDKs for package identity, permissions, exposed components, library evidence and community package guidance. The report describes requested capabilities, not actions observed in use.\n");pkg(c,file,o,rules);}
  if(dexCount>0)trackers(c,classes,o);
  o.section("Archive inventory (first 1,000 entries; no extraction)");for(String n:entries)o.item(n);
  if(count>entries.size())o.item("Inventory truncated.");
 }
 static void match(Output o,JSONObject rules,String kind,String evidence){
  if(rules==null)return;JSONObject m=rules.optJSONObject(kind+":"+evidence);if(m==null)return;
  o.item("LibChecker exact-name match: "+m.optString("label","Unnamed rule")+" — "+m.optString("dev_team","Unknown team"));
  String desc=m.optString("description");if(!desc.isEmpty())o.item(desc);
  String link=m.optString("source_link");if(!link.isEmpty())o.item("Source: "+link);
  o.item("Evidence: "+kind+" / "+evidence+"; name matching only, not verified code identity.");
 }
 static void pkg(Context c,File f,Output o,JSONObject rules){
  try{
   int flags=PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS|PackageManager.GET_PROVIDERS|PackageManager.GET_PERMISSIONS;
   flags|=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
   PackageManager pm=c.getPackageManager();PackageInfo p=pm.getPackageArchiveInfo(f.getAbsolutePath(),flags);
   if(p==null){o.item("Android PackageManager could not parse this APK. It may be a split, malformed, unsupported or incomplete package.");return;}
   o.section("Package identity");o.item("Package ID: "+p.packageName);o.item("Version: "+p.versionName+" ("+(Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode)+")");
   if(p.applicationInfo!=null){ApplicationInfo a=p.applicationInfo;o.item("Minimum Android API: "+a.minSdkVersion+"; target API: "+a.targetSdkVersion+" (compatibility behavior, not a security rating)");o.item("Debuggable flag: "+((a.flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0)+" — allows debugging when platform conditions permit.");o.item("Allow backup flag: "+((a.flags&ApplicationInfo.FLAG_ALLOW_BACKUP)!=0)+" — actual backup depends on OS, policies and app rules.");}
   o.section("Signing identity");Signature[] sig=Build.VERSION.SDK_INT>=28?(p.signingInfo==null?null:p.signingInfo.getApkContentsSigners()):p.signatures;
   if(sig!=null){for(Signature x:sig)o.item("Platform-reported certificate SHA-256: "+Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(x.toByteArray())));}
   else o.item("No certificate exposed by PackageManager.");
   o.item("A certificate fingerprint can be compared to a trusted release. This is not an independent APK signing-scheme verification or a trusted-publisher verdict.");
   o.section("Requested permissions (not necessarily granted or exercised)");if(p.requestedPermissions!=null)for(String perm:p.requestedPermissions){
    String desc="";
    try{PermissionInfo pi=pm.getPermissionInfo(perm,0);CharSequence d=pi.loadDescription(pm);if(d!=null)desc=d.toString();int base=pi.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE;desc+=(desc.isEmpty()?"":" ")+"Protection: "+(base==PermissionInfo.PROTECTION_DANGEROUS?"runtime/dangerous":base==PermissionInfo.PROTECTION_NORMAL?"normal":base==PermissionInfo.PROTECTION_SIGNATURE?"signature-restricted":"other")+".";}catch(Exception ignored){}
    o.item(perm+" — "+permission(perm)+(desc.isEmpty()?"":" "+desc));
   }else o.item("No requested permissions returned.");
   components(o,rules,"activities-libs","Activities — screens or entry points",p.activities);
   components(o,rules,"services-libs","Services — background or bound work",p.services);
   components(o,rules,"receivers-libs","Receivers — handle broadcast events",p.receivers);
   components(o,rules,"providers-libs","Providers — structured data access interfaces",p.providers);
   o.section("Debloat knowledge (UAD-NG; read-only)");
   try{JSONObject d=asset(c,"knowledge/uad.json").optJSONObject(p.packageName);if(d==null)o.item("No exact package ID match in this pinned catalog. Absence is not a removal recommendation.");else{for(String k:new String[]{"list","description","removal","dependencies","neededBy","needed_by","labels"})if(d.has(k)&&!d.isNull(k))o.item(k+": "+d.optString(k));o.item("Community guidance, not a tested result on this device. Package IDs can be reused; removal can break boot, calls, updates or data. No removal action is provided.");}}catch(Exception e){o.item("UAD database unavailable: "+e.getMessage());}
  }catch(Exception e){o.item("Package metadata incomplete: "+e.getMessage());}
 }
 static void components(Output o,JSONObject rules,String kind,String label,ComponentInfo[] a){
  o.section(label);if(a==null||a.length==0){o.item("None returned.");return;}
  o.item(a.length+" declared entries. Exported means other apps may be able to address the component, subject to permission and platform restrictions.");
  for(int i=0;i<Math.min(a.length,500);i++){ComponentInfo v=a[i];String protection="";
   if(v instanceof ActivityInfo)protection=((ActivityInfo)v).permission;
   if(v instanceof ServiceInfo)protection=((ServiceInfo)v).permission;
   if(v instanceof ProviderInfo){ProviderInfo p=(ProviderInfo)v;protection="read="+p.readPermission+", write="+p.writePermission+", authority="+p.authority;}
   o.item(v.name+" [exported="+v.exported+", enabled="+v.enabled+"]"+(protection==null||protection.isEmpty()?"":" "+protection));match(o,rules,kind,v.name);
  }if(a.length>500)o.item("Component list truncated to 500.");
 }
 static String permission(String p){
  if(p.equals("android.permission.INTERNET"))return "Can open network sockets; destination and transmitted data are not established.";
  if(p.contains("LOCATION"))return "Location-related capability; accuracy and background access depend on the specific grant and operating system.";
  if(p.equals("android.permission.RECORD_AUDIO"))return "Can request microphone access; actual use requires runtime conditions.";
  if(p.equals("android.permission.CAMERA"))return "Can request camera access; does not show that images were recorded.";
  if(p.contains("CONTACTS"))return "Contact data access or modification, depending on the permission.";
  if(p.contains("SMS")||p.contains("CALL_LOG"))return "Messaging or call-history capability; access can be tightly restricted by Android.";
  if(p.equals("android.permission.QUERY_ALL_PACKAGES"))return "Can request broad visibility of installed package identities.";
  if(p.contains("READ_MEDIA")||p.contains("EXTERNAL_STORAGE"))return "Media or storage capability; access depends on Android version, scope and grants.";
  if(p.contains("BLUETOOTH")||p.contains("NEARBY"))return "Nearby device connectivity or discovery capability.";
  if(p.equals("android.permission.REQUEST_INSTALL_PACKAGES"))return "Can request user-mediated package installation; not permission for silent installation.";
  return "Declared permission; consult platform or defining application's documentation for its exact scope.";
 }
 static void trackers(Context c,Set<String> classes,Output o){
  o.section("Exodus tracker evidence — offline imported database");
  File f=new File(c.getFilesDir(),"exodus-trackers.json");
  if(!f.isFile()){o.item("No Exodus snapshot imported. Use Options > Import Exodus database (JSON). No tracker result is available.");return;}
  try(InputStream in=new FileInputStream(f)){
   JSONObject all=new JSONObject(read(in,8*1024*1024)).getJSONObject("trackers");Iterator<String> it=all.keys();int matched=0,skipped=0,checked=0;
   while(it.hasNext()&&checked<2000){String id=it.next();checked++;JSONObject rule=all.optJSONObject(id);if(rule==null)continue;
    String sig=rule.optString("code_signature","");
    // Literal package names only; no untrusted regex evaluation. Alternatives with operators are not silently treated as matches.
    if(!sig.matches("[A-Za-z0-9_$]+(\\.[A-Za-z0-9_$]+)+\\.?")){skipped++;continue;}
    String prefix=sig.endsWith(".")?sig.substring(0,sig.length()-1):sig;
    String hit=null;for(String n:classes)if(n.equals(prefix)||n.startsWith(prefix+".")||n.startsWith(prefix+"$")){hit=n;break;}
    if(hit!=null){matched++;o.item(rule.optString("name",id)+" [Exodus ID "+id+"] — "+rule.optString("categories","")+"; evidence class "+hit+"; signature "+sig);String d=rule.optString("description");if(!d.isEmpty())o.item(d);String link=rule.optString("website");if(!link.isEmpty())o.item("Source: "+link);}
   }
   o.item(matched+" literal signature matches; "+skipped+" unsupported or empty signatures skipped; "+checked+" database entries inspected"+(it.hasNext()?" (limit reached)":"")+".");
   o.item("Imported Exodus database is ODbL 1.0 / individual contents DbCL 1.0. This is a limited local matcher, not the full Exodus engine. A matching class does not establish use; obfuscation, missing splits and complex signatures can produce false negatives.");
  }catch(Exception e){o.item("Exodus snapshot could not be analyzed: "+e.getMessage());}
 }
}
