// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;
import org.json.*;

/** Actual JNI digests against JCA, RFC vectors and OpenSSL's independent MD4. */
public final class HashInvestigationTest {
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static String scan(File input) throws Exception {
        try(RandomAccessFile source=new RandomAccessFile(input,"r")) {
            source.seek(Math.min(2,source.length()));
            Field field=FileDescriptor.class.getDeclaredField("fd");field.setAccessible(true);
            String result=HashEngine.scanFd(field.getInt(source.getFD()));
            require(source.getFilePointer()==Math.min(2,source.length()),"JNI moved borrowed descriptor cursor");
            return result;
        }
    }
    private static BinaryRunner runner() { return (file,stop)->{EvidenceRows.check(stop);String report=scan(file);EvidenceRows.check(stop);return report;}; }
    private static String sha(byte[] bytes,String algorithm) throws Exception { return Inspector.toHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
    private static JSONObject report(File file,byte[] input) throws Exception {
        Files.write(file.toPath(),input);JSONObject report=new JSONObject(scan(file));
        require(report.getBoolean("ok")&&report.getBoolean("wholeInputComplete")&&report.getLong("bytesRead")==input.length,"Native hash coverage differs: "+report);
        JSONArray digests=report.getJSONArray("digests");require(digests.length()==8,"Native digest omitted");
        CRC32 crc=new CRC32();crc.update(input);
        require(digests.getJSONObject(0).getString("hex").equals(String.format(Locale.ROOT,"%08x",crc.getValue())),"CRC-32/ISO-HDLC differs");
        for(int i=2;i<8;i++) {
            JSONObject digest=digests.getJSONObject(i);String algorithm=digest.getString("algorithm");
            require(digest.getString("hex").equals(sha(input,algorithm)),"Independent JCA "+algorithm+" differs at length "+input.length);
        }
        Process process=new ProcessBuilder("openssl","dgst","-provider","default","-provider","legacy","-md4",file.getAbsolutePath()).redirectErrorStream(true).start();
        String independent=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
        require(process.waitFor()==0,"OpenSSL 3 legacy-provider MD4 reference unavailable: "+independent);
        require(independent.endsWith("= "+digests.getJSONObject(1).getString("hex")),"Independent OpenSSL MD4 differs at length "+input.length);
        return report;
    }
    private static void empty(File directory) { require(directory.list()==null||Objects.requireNonNull(directory.list()).length==0,"Hash snapshot leaked"); }
    public static void main(String[] args) throws Exception {
        require(HashEngine.AVAILABLE,"Real host hash JNI unavailable");
        Path temporary=Path.of(args[0]);File file=temporary.resolve("input.bin").toFile(),cache=temporary.resolve("hash-inputs").toFile();
        Map<String,String> rfc=new LinkedHashMap<>();
        rfc.put("","31d6cfe0d16ae931b73c59d7e0c089c0");rfc.put("a","bde52cb31de33e46245e05fbdbd6fb24");
        rfc.put("abc","a448017aaf21d8525fc10ae87aa6729d");rfc.put("message digest","d9130a8164549fe818874806e1c7014b");
        rfc.put("abcdefghijklmnopqrstuvwxyz","d79e1c308aa5bbcdeea8ed63df412da9");
        rfc.put("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789","043f8582f241db351ce627e153e7f0e4");
        rfc.put("1234567890".repeat(8),"e33b4ddc9c38f2199c3e7b164fcc0536");
        for(Map.Entry<String,String> vector:rfc.entrySet()) {
            JSONObject result=report(file,vector.getKey().getBytes(StandardCharsets.US_ASCII));
            require(result.getJSONArray("digests").getJSONObject(1).getString("hex").equals(vector.getValue()),"RFC 1320 MD4 vector differs");
        }
        for(int count:new int[]{55,56,63,64,65,111,112,127,128,129,65535,65536,65537,1048583}) {
            byte[] input=new byte[count];new Random(0xD1E+count).nextBytes(input);report(file,input);
        }
        byte[] original="owned FOSS hash fixture Ω 東京 😀".getBytes(StandardCharsets.UTF_8);Files.write(file.toPath(),original);
        try(HashDocument document=HashDocument.read(file,cache,"input.bin",runner(),()->false)) {
            EvidenceRows rows=document.rows();require(rows.size()==8,"Typed digest table incomplete");
            require(rows.select("SHA512",()->false).count==1,"Algorithm search includes shared references");
            require(rows.row(7,()->false).physicalOffset==null&&rows.row(7,()->false).sizeBytes==null,"Computed digest assigned a false stored range");
            require(rows.row(4,()->false).json().getString("mechanism").contains("not a truncated SHA-256"),"SHA-224 distinction absent");
            StringWriter output=new StringWriter();rows.writeReport(output,document.provenance(),"unmatched",4,()->false);
            JSONObject exported=new JSONObject(output.toString());require(exported.getJSONArray("rows").length()==8,"Filtered export dropped digests");
            require(exported.getJSONObject("provenance").getJSONObject("input").getString("sha256").equals(sha(original,"SHA-256")),"Measured full-input identity missing");
            require(new JSONObject(document.legacyReport("SHA-512",true)).getJSONObject("data").getJSONObject("Hash").length()==1,"Legacy selector omitted/added algorithms");
            try { document.legacyReport("invalid",true);throw new AssertionError("Unsupported hash selector accepted"); } catch(IOException expected) {}
            Files.write(file.toPath(),new byte[]{0});require(Arrays.equals(Files.readAllBytes(document.snapshot.file.toPath()),original),"Hash report borrows mutable caller bytes");
        }
        empty(cache);Files.write(file.toPath(),original);
        try(HashBackend backend=new HashBackend(file,cache,"input.bin",runner())) {
            String initial=backend.initialRequest(()->false);EvidenceRows rows=backend.open(initial,()->false);
            InvestigationBackend.Link value=backend.related(initial,rows,rows.row(7,()->false),()->false).get(0);
            StringWriter output=new StringWriter();backend.writeText(value.request,output,()->false);
            require(output.toString().equals(sha(original,"SHA-512")+"\n"),"Complete digest text export differs");
            require(!backend.byteSource().equals(file),"Hash hex source is not owned");
        }
        empty(cache);
        for(String failure:new String[]{"bytesRead","algorithm","sha256"}) {
            BinaryRunner corrupt=(input,stop)->{
                JSONObject actual=new JSONObject(scan(input));
                if(failure.equals("bytesRead")) actual.put("bytesRead",0);
                else if(failure.equals("algorithm")) actual.getJSONArray("digests").getJSONObject(1).put("algorithm","MD5");
                else actual.getJSONArray("digests").getJSONObject(5).put("hex","0".repeat(64));
                return actual.toString();
            };
            try { HashDocument.read(file,cache,"input.bin",corrupt,()->false);throw new AssertionError("Incomplete/mismatched worker report accepted: "+failure); }
            catch(IOException expected) {}
            empty(cache);
        }
        try { HashDocument.read(file,cache,"input.bin",runner(),()->true);throw new AssertionError("Hash acquisition ignored cancellation"); }
        catch(InterruptedIOException expected) {}
        empty(cache);
        System.out.println("Whole-file hash fixtures passed: actual eight-algorithm JNI, all RFC MD4 vectors, JCA/OpenSSL independent padding/block/read-boundary comparisons, exact full-input identity, typed/legacy/complete export, owned bytes, cancellation and malformed-report cleanup.");
    }
}
