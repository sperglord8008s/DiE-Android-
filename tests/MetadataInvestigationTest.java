// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.drew.lang.Rational;
import com.drew.metadata.StringValue;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.diemobile.metadata.XmlGate;
import org.json.*;
import org.xml.sax.*;

/** Real source-built readers, rich generated image/audio values and XML boundaries. */
public final class MetadataInvestigationTest {
    private static void require(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    private static EvidenceRows.Row find(EvidenceRows table,String name) throws Exception {
        for(int i=0;i<table.size();i++){EvidenceRows.Row row=table.row(i,()->false);if(row.name.contains(name))return row;}
        StringBuilder sample=new StringBuilder();for(int i=0;i<Math.min(20,table.size());i++)sample.append(table.row(i,()->false).name).append(" | ");
        throw new AssertionError("Missing "+name+" in "+table.title+" • "+sample);
    }
    private static EvidenceRows.Row tag(EvidenceRows table,String className,int type) throws Exception {
        for(int i=0;i<table.size();i++){EvidenceRows.Row row=table.row(i,()->false);JSONObject value=row.json();if(value.optString("directoryClass").endsWith(className)&&value.optInt("tagType")==type)return row;}
        throw new AssertionError("Missing "+className+" tag "+Integer.toHexString(type));
    }
    private static JSONObject report(EvidenceRows table,JSONObject provenance)throws Exception {StringWriter out=new StringWriter();table.writeReport(out,provenance,"unmatched filter",0,10,()->false);return new JSONObject(out.toString());}
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]);File stages=directory.resolve("metadata-inputs").toFile();File jpeg=directory.resolve("rich.jpg").toFile();byte[] original=MetadataFixtureFactory.jpeg();Files.write(jpeg.toPath(),original);
        Files.write(directory.resolve("rich.png"),MetadataFixtureFactory.png());Files.write(directory.resolve("rich.wav"),MetadataFixtureFactory.wav());
        try(MetadataDocument document=MetadataDocument.read(jpeg,stages,"rich.jpg",()->false)){
            require(document.detectedType.equals("Jpeg"),"JPEG detection differs: "+document.detectedType);EvidenceRows tags=document.tags(-1),dirs=document.directories(),xmp=document.xmp(-1);
            require(tags.size()>20&&dirs.size()>5,"Real metadata directories/tags absent");
            require(document.snapshot.sha256.equals(Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(original))),"Source SHA-256 differs");
            require(!document.snapshot.file.equals(jpeg),"Metadata byte source borrows expiring input");
            require(tag(tags,"ExifIFD0Directory",0x0112).json().getJSONObject("rawValue").getString("value").equals("6"),"Orientation raw integer changed");
            JSONObject exposure=tag(tags,"ExifSubIFDDirectory",0x829a).json().getJSONObject("rawValue");require(exposure.getString("numerator").equals("1")&&exposure.getString("denominator").equals("125"),"Exposure rational rounded");
            JSONObject fnumber=tag(tags,"ExifSubIFDDirectory",0x829d).json().getJSONObject("rawValue");require(fnumber.getString("numerator").equals("28")&&fnumber.getString("denominator").equals("10"),"Rational representation simplified/lost");
            JSONObject latitude=tag(tags,"GpsDirectory",2).json().getJSONObject("rawValue");require(latitude.getInt("elementCount")==3&&latitude.getJSONArray("values").getJSONObject(0).getString("numerator").equals("37"),"GPS raw coordinate units lost");
            EvidenceRows.Row gps=find(dirs,"GPS");require(!gps.json().isNull("parentDirectoryIndex"),"GPS parent relationship lost");
            int comments=0;for(int i=0;i<dirs.size();i++)if(dirs.row(i,()->false).json().getString("directoryClass").endsWith("JpegCommentDirectory"))comments++;require(comments>=2,"Repeated comment directories collapsed");
            require(xmp.size()>1005,"XMP ancestry/properties silently clipped");
            require(find(xmp,"DocumentAncestors[1005]").json().getString("value").equals("ancestor-1004"),"Last ancestry element omitted");
            JSONObject qualifier=find(xmp,"dc:title[1]/xml:lang").json();require(qualifier.getString("value").equals("x-default")&&qualifier.getString("nodeKind").equals("qualifier"),"XMP language qualifier absent");
            EvidenceRows.Row large=find(xmp,"die:Large");require(!large.json().getBoolean("previewComplete")&&large.json().getInt("valueUtf16Units")==MetadataFixtureFactory.LARGE.length(),"Large XMP preview not disclosed");
            JSONObject xmpReport=report(xmp,document.provenance());boolean largeExported=false;
            for(int i=0;i<xmpReport.getJSONArray("rows").length();i++){JSONObject value=xmpReport.getJSONArray("rows").getJSONObject(i);if(value.optString("propertyPath").equals("die:Large")){largeExported=true;require(value.getString("fullPropertyValue").equals(MetadataFixtureFactory.LARGE),"Full XMP export truncated");}}
            require(largeExported,"Large XMP property missing from report");
            EvidenceRows.Row icc=tag(tags,"IccDirectory",MetadataFixtureFactory.ICC_TAG);JSONObject raw=icc.json().getJSONObject("rawValue");require(raw.getInt("byteLength")==70000&&!raw.getBoolean("previewComplete"),"Large ICC binary preview scope wrong");
            require(icc.physicalOffset==null&&icc.sizeBytes==null,"Engine value was assigned invented source offsets");
            StringWriter selected=new StringWriter();tags.writeSelectedRow(selected,icc,()->false);JSONObject full=new JSONObject(selected.toString());
            require(Arrays.equals(MetadataFixtureFactory.iccValue(),Base64.getDecoder().decode(full.getJSONObject("fullRawValue").getString("base64"))),"Selected record export omitted raw binary bytes");
            JSONObject all=report(tags,document.provenance());require(all.getJSONArray("rows").length()==tags.size(),"UI filter changed complete tag export");
            EvidenceRows chunks=document.value(icc.json().getInt("directoryIndex"),MetadataFixtureFactory.ICC_TAG);require(chunks.size()==2&&chunks.row(1,()->false).json().getLong("valueByteOffset")==65536,"Complete value chunk offsets wrong");
            JSONObject chunkReport=report(chunks,document.provenance());ByteArrayOutputStream joined=new ByteArrayOutputStream();
            for(int i=0;i<chunkReport.getJSONArray("rows").length();i++)joined.write(Base64.getDecoder().decode(chunkReport.getJSONArray("rows").getJSONObject(i).getJSONObject("fullRawValue").getString("base64")));
            require(Arrays.equals(joined.toByteArray(),MetadataFixtureFactory.iccValue()),"Value table dropped bytes");
            Files.write(jpeg.toPath(),new byte[]{1});require(Arrays.equals(Files.readAllBytes(document.snapshot.file.toPath()),original),"Source mutation invalidated private bytes");
        }
        require(Objects.requireNonNull(stages.list()).length==0,"Closed metadata snapshot leaked");Files.write(jpeg.toPath(),original);
        try(MetadataDocument png=MetadataDocument.read(directory.resolve("rich.png").toFile(),stages,"rich.png",()->false)){
            require(png.detectedType.equals("Png"),"PNG type missing");JSONObject rows=report(png.tags(-1),png.provenance());String encoded=rows.toString();require(encoded.contains("FOSS community")&&encoded.contains("Second retained value"),"Repeated PNG text values lost");
        }
        try(MetadataDocument wav=MetadataDocument.read(directory.resolve("rich.wav").toFile(),stages,"rich.wav",()->false)){
            require(wav.detectedType.equals("Wav"),"WAV type missing");String encoded=report(wav.tags(-1),wav.provenance()).toString();require(encoded.contains("44100")&&encoded.contains("FOSS community")&&encoded.contains("Fixture tone"),"WAV audio/INFO facts missing");
        }
        File unknown=directory.resolve("unknown.bin").toFile();Files.write(unknown.toPath(),new byte[32]);
        try(MetadataDocument doc=MetadataDocument.read(unknown,stages,"unknown.bin",()->false)){require(doc.parseStatus.equals("unsupported-or-invalid")&&doc.errors(-1).size()>0,"Unsupported input reported empty success");}
        File pdf=directory.resolve("identified.pdf").toFile();Files.writeString(pdf.toPath(),"%PDF-1.7\n1 0 obj <<>> endobj\n%%EOF\n");
        try(MetadataDocument doc=MetadataDocument.read(pdf,stages,"identified.pdf",()->false)){require(doc.parseStatus.equals("identification-only")&&doc.errors(-1).size()>0,"Recognition was mislabeled as PDF metadata parsing");}
        Path secret=directory.resolve("must-not-read.txt");Files.writeString(secret,"DIE_XML_ENTITY_SECRET_SENTINEL");
        String dtd="<!DOCTYPE x:xmpmeta [<!ENTITY xxe SYSTEM \""+secret.toUri()+"\">]><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:description>&xxe;</dc:description></rdf:Description></rdf:RDF></x:xmpmeta>";
        File hostile=directory.resolve("dtd.jpg").toFile();Files.write(hostile.toPath(),MetadataFixtureFactory.jpeg(dtd));
        try(MetadataDocument doc=MetadataDocument.read(hostile,stages,"dtd.jpg",()->false)){require(doc.xmp(-1).size()==0,"DTD-derived XMP properties were accepted");String errors=report(doc.errors(-1),doc.provenance()).toString();require(errors.contains("DTD declarations are prohibited")&&!errors.contains("DIE_XML_ENTITY_SECRET_SENTINEL"),"DTD policy lost or external data exposed: "+errors);}
        for(byte[] xml:Arrays.asList(dtd.getBytes(StandardCharsets.UTF_8),dtd.getBytes(StandardCharsets.UTF_16))){try{XmlGate.validate(new InputSource(new ByteArrayInputStream(xml)));throw new AssertionError("DTD allowed");}catch(SAXException expected){require(expected.getMessage().contains("DTD"),"Wrong DTD rejection");}}
        String nested=String.join("",Collections.nCopies(65,"<a>"))+String.join("",Collections.nCopies(65,"</a>"));
        try{XmlGate.validate(new InputSource(new StringReader(nested)));throw new AssertionError("XML depth budget ignored");}catch(SAXException expected){require(expected.getMessage().contains("budget"),"Wrong XML depth failure");}
        try{XmlGate.validate(new InputSource(secret.toUri().toString()));throw new AssertionError("URI-only XML input allowed");}catch(SAXException expected){}
        String exact="exact\0é😀\uD800"+"x".repeat(9000);JSONObject preview=MetadataValues.preview(exact,()->false);require(!preview.getBoolean("previewComplete"),"Long value preview not disclosed");StringWriter text=new StringWriter();MetadataValues.write(text,exact,()->false);require(new JSONObject(text.toString()).getString("value").equals(exact),"Raw UTF-16 value changed");
        StringWriter numbers=new StringWriter();MetadataValues.write(numbers,new Object[]{Long.MAX_VALUE,-0.0,Float.NaN,new Rational(Long.MAX_VALUE,3),new StringValue(new byte[]{(byte)0xe9},StandardCharsets.ISO_8859_1)},()->false);JSONObject values=new JSONObject(numbers.toString());JSONArray array=values.getJSONArray("values");
        require(array.getJSONObject(0).getString("value").equals(Long.toString(Long.MAX_VALUE))&&array.getJSONObject(1).getString("ieeeBitsHex").equals("8000000000000000")&&array.getJSONObject(2).getString("value").equals("NaN"),"Numeric precision/signed zero/nonfinite value lost");
        require(array.getJSONObject(3).getString("numerator").equals(Long.toString(Long.MAX_VALUE))&&array.getJSONObject(4).getString("charset").equals("ISO-8859-1"),"Typed rational/text bytes lost");
        AtomicInteger cancel=new AtomicInteger();try{MetadataDocument.read(jpeg,stages,"rich.jpg",()->cancel.incrementAndGet()>8);throw new AssertionError("Metadata ignored cancellation");}catch(InterruptedIOException expected){require(Objects.requireNonNull(stages.list()).length==0,"Cancelled metadata acquisition leaked");}
        MetadataBackend backend=new MetadataBackend(jpeg,stages,"rich.jpg");String root=backend.initialRequest(()->false);EvidenceRows tags=backend.open(root,()->false);EvidenceRows.Row icc=tag(tags,"IccDirectory",MetadataFixtureFactory.ICC_TAG);
        InvestigationBackend.Link blob=backend.related(root,tags,icc,()->false).stream().filter(link->link.binary).findFirst().orElseThrow();byte[] payload=backend.binaryValue(blob.request,()->false);require(Arrays.equals(payload,MetadataFixtureFactory.iccValue()),"Provider binary export wrong");
        PreparedExport staged=PreparedExport.bytes(directory.resolve("exports").toFile(),blob.textName,payload,()->false);Arrays.fill(payload,(byte)0);ByteArrayOutputStream output=new ByteArrayOutputStream();staged.copyTo(output,()->false);require(Arrays.equals(output.toByteArray(),MetadataFixtureFactory.iccValue()),"Picker bytes remain mutable");staged.discard();backend.close();
        require(Objects.requireNonNull(stages.list()).length==0,"Provider ownership leaked");
        System.out.println("Metadata fixtures passed: actual JPEG/EXIF/GPS/ICC/XMP/PNG/WAV readers, repeated directories/text, raw rationals/numeric bits, 1,005 ancestry items/qualifiers, complete streamed values, binary chunks/exports, source ownership, cancellation, unsupported inputs and XML DTD/entity/depth boundaries");
    }
}
