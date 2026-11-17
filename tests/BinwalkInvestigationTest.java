// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Real Rust Binwalk readers/JNI, every-byte matching, overlap and range contracts. */
public final class BinwalkInvestigationTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static int find(byte[] data,byte[] pattern){outer:for(int i=0;i<=data.length-pattern.length;i++){for(int j=0;j<pattern.length;j++)if(data[i+j]!=pattern[j])continue outer;return i;}throw new AssertionError("Fixture pattern unavailable");}
    private static JSONObject candidate(JSONObject report,String name,long offset)throws Exception{JSONArray rows=report.getJSONArray("rows");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("name").equals(name)&&row.getLong("physicalOffset")==offset&&row.getBoolean("parserAccepted"))return row;}throw new AssertionError("Missing "+name+" @"+offset+" • "+rows);}
    public static void main(String[] args)throws Exception{
        require(BinwalkEngine.AVAILABLE,"Host Rust JNI unavailable: "+BinwalkEngine.ERROR);Path directory=Path.of(args[0]);
        byte[] image=MetadataFixtureFactory.jpeg(),png=MetadataFixtureFactory.png();byte[] icc=MetadataFixtureFactory.iccValue();int iccAt=find(image,Arrays.copyOf(icc,64)),nestedAt=iccAt+1024;System.arraycopy(png,0,image,nestedAt,png.length);
        ByteArrayOutputStream blob=new ByteArrayOutputStream();blob.write(new byte[37]);int jpegAt=blob.size();blob.write(image);blob.write(new byte[19]);int pngAt=blob.size();blob.write(png);blob.write(new byte[11]);int wavAt=blob.size();byte[] wav=MetadataFixtureFactory.wav();blob.write(wav);blob.write(new byte[13]);
        int gzipAt=blob.size();ByteArrayOutputStream gzipBytes=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(gzipBytes)){gzip.write("FOSS binary resource fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));}blob.write(gzipBytes.toByteArray());blob.write(new byte[17]);
        int elfAt=blob.size();ByteBuffer elf=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);elf.put(new byte[]{0x7f,'E','L','F',2,1,1,0});elf.putShort(16,(short)2);elf.putShort(18,(short)62);elf.putInt(20,1);elf.putShort(52,(short)64);blob.write(elf.array());blob.write(new byte[23]);
        int invalidPngAt=blob.size();blob.write(Arrays.copyOf(png,20)); // Full upstream magic, truncated IHDR/body.
        File input=directory.resolve("embedded-media.bin").toFile();byte[] original=blob.toByteArray();Files.write(input.toPath(),original);
        JSONObject report=new JSONObject(BinwalkEngine.scanPath(input.getAbsolutePath()));require(report.optBoolean("ok"),report.toString());
        require(report.getBoolean("fullBlobSearch")&&!report.getBoolean("nestedResultsPruned")&&!report.getBoolean("unknownExtentsInferred")&&!report.getBoolean("externalExtractorsExecuted"),"Mode semantics lost");
        JSONObject jpeg=candidate(report,"jpeg",jpegAt),nested=candidate(report,"png",jpegAt+nestedAt),outside=candidate(report,"png",pngAt);
        require(jpeg.getLong("reportedSizeBytes")==image.length,"JPEG extent ended at a false EOI inside ICC metadata");require(nested.getLong("reportedSizeBytes")==png.length&&outside.getLong("reportedSizeBytes")==png.length,"Nested PNG extent/pruning differs");
        JSONObject riff=candidate(report,"riff",wavAt);require(riff.getLong("reportedSizeBytes")==wav.length&&riff.getString("description").contains("WAVE"),"WAV RIFF size/type differs");require(candidate(report,"gzip",gzipAt).getLong("reportedSizeBytes")==gzipBytes.size(),"Gzip consumed size differs");
        JSONObject executable=candidate(report,"elf",elfAt);require(executable.isNull("reportedSizeBytes"),"Unknown ELF extent inferred to next candidate/EOF");
        boolean rejected=false;JSONArray rows=report.getJSONArray("rows");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("name").equals("png")&&row.getLong("magicOffset")==invalidPngAt&&!row.getBoolean("parserAccepted"))rejected=true;}require(rejected,"Malformed PNG magic disappeared from unresolved evidence");
        require(report.getInt("signatureCount")>50&&report.getJSONArray("patterns").length()==report.getInt("patternCount"),"Actual upstream pattern catalog incomplete");
        try(RandomAccessFile source=new RandomAccessFile(input,"r")){Field field=FileDescriptor.class.getDeclaredField("fd");field.setAccessible(true);int descriptor=field.getInt(source.getFD());JSONObject fdReport=new JSONObject(BinwalkEngine.scanFd(descriptor));require(fdReport.getBoolean("ok")&&fdReport.getInt("candidateCount")==report.getInt("candidateCount"),"Granted descriptor API differs from path fixture API");require(source.getFD().valid(),"JNI closed the caller's descriptor");source.seek(0);require(source.read()==0,"JNI changed input bytes");}
        require(!new JSONObject(BinwalkEngine.scanFd(-1)).getBoolean("ok"),"Invalid descriptor reported empty success");
        File empty=directory.resolve("empty.bin").toFile();Files.write(empty.toPath(),new byte[0]);require(new JSONObject(BinwalkEngine.scanPath(empty.getAbsolutePath())).getJSONArray("rows").length()==0,"Empty input has invented candidates");
        File large=directory.resolve("over-limit.bin").toFile();try(RandomAccessFile output=new RandomAccessFile(large,"rw")){output.setLength(BinwalkBackend.MAX_BYTES+1);}require(!new JSONObject(BinwalkEngine.scanPath(large.getAbsolutePath())).getBoolean("ok"),"Native input budget ignored");large.delete();
        BinaryRunner runner=(file,stop)->{EvidenceRows.check(stop);return BinwalkEngine.scanPath(file.getAbsolutePath());};File snapshots=directory.resolve("binary-inputs").toFile();
        try(BinwalkBackend backend=new BinwalkBackend(input,snapshots,"embedded-media.bin",runner)){
            String request=backend.initialRequest(()->false);EvidenceRows accepted=backend.open(request,()->false);require(accepted.size()>3,"Provider lost parser-accepted candidates");
            EvidenceRows.Row chosen=null;for(int i=0;i<accepted.size();i++){EvidenceRows.Row row=accepted.row(i,()->false);if(row.physicalOffset==jpegAt&&row.json().getString("name").equals("jpeg")){chosen=row;break;}}require(chosen!=null&&chosen.sizeBytes==image.length,"Provider altered known extent");
            EvidenceRows unresolved=backend.open("unresolved",()->false);require(unresolved.size()>0,"Provider hid unresolved headers");for(int i=0;i<unresolved.size();i++)require(unresolved.row(i,()->false).sizeBytes==null,"Rejected header obtained an extractable extent");
            EvidenceRows formats=backend.open("catalog",()->false);require(formats.size()==report.getInt("patternCount"),"Provider lost catalog patterns");
            StringWriter out=new StringWriter();accepted.writeReport(out,backend.provenance(),"no matches",0,10,()->false);JSONObject exported=new JSONObject(out.toString());require(exported.getJSONArray("rows").length()==accepted.size(),"Filter changed complete candidate export");
            FileRange range=new FileRange(original.length,chosen.physicalOffset,chosen.sizeBytes);PreparedExport staged=PreparedExport.range(directory.resolve("exports").toFile(),"carved.jpg",backend.byteSource(),range,()->false);Files.write(input.toPath(),new byte[]{1});ByteArrayOutputStream recovered=new ByteArrayOutputStream();staged.copyTo(recovered,()->false);staged.discard();require(Arrays.equals(image,recovered.toByteArray()),"Carved stored bytes changed/ended at nested marker");
            require(backend.provenance().getJSONObject("input").getString("sha256").length()==64,"Source identity missing");
        }
        require(Objects.requireNonNull(snapshots.list()).length==0,"Binary provider snapshot leaked");
        Files.write(input.toPath(),original);
        BinaryRunner incomplete=(file,stop)->{JSONObject corrupt=new JSONObject(report.toString());corrupt.getJSONArray("patterns").remove(corrupt.getJSONArray("patterns").length()-1);return corrupt.toString();};
        try(BinwalkBackend backend=new BinwalkBackend(input,snapshots,"transport-corruption.bin",incomplete)){
            try{backend.open("accepted",()->false);throw new AssertionError("Incomplete native catalog accepted");}catch(IOException expected){require(expected.getMessage().contains("incomplete candidate/catalog counts"),"Unexpected model rejection: "+expected.getMessage());}
        }
        require(Objects.requireNonNull(snapshots.list()).length==0,"Rejected native report leaked its input snapshot");
        System.out.println("Binwalk JNI fixtures passed: actual pattern catalog, JPEG/ICC false-EOI handling, overlapping nested PNG, PNG/WAV/gzip ranges, unknown ELF extent, unresolved headers, granted descriptor ownership, budgets, complete scoped export and exact immutable carving");
        System.out.println("Binwalk catalog: "+report.getInt("signatureCount")+" signatures / "+report.getInt("patternCount")+" magic patterns; "+report.getInt("rawMagicMatches")+" raw fixture matches");
    }
}
