// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.adobe.xmp.*;
import com.adobe.xmp.properties.XMPPropertyInfo;
import com.drew.imaging.*;
import com.drew.metadata.*;
import com.drew.metadata.xmp.XmpDirectory;
import java.io.*;
import java.lang.reflect.Array;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.diemobile.metadata.XmlGate;
import org.json.*;

/** Every returned directory/tag/error and XMP property, with original value types. */
final class MetadataDocument implements AutoCloseable {
    static final String ENGINE = "metadata-extractor", VERSION = "2.22.0", XMP_REVISION = "c60127d527f8438b89f9e292200639ec14d1c3cf";
    static final long MAX_INPUT_BYTES = 128L * 1024 * 1024;
    private static final int MAX_DIRECTORIES = 20000, MAX_TAGS = 200000, MAX_ERRORS = 10000, MAX_XMP = 100000;
    private static final Set<FileType> READERS = Collections.unmodifiableSet(EnumSet.of(FileType.Jpeg,FileType.Tiff,FileType.Arw,FileType.Cr2,FileType.Nef,FileType.Orf,FileType.Rw2,FileType.Dng,FileType.GoPro,
        FileType.Kdc,FileType.ThreeFR,FileType.Pef,FileType.Srw,FileType.Psd,FileType.Png,FileType.Bmp,FileType.Gif,FileType.Ico,FileType.Pcx,FileType.WebP,FileType.Raf,FileType.Avi,FileType.Wav,
        FileType.Crx,FileType.QuickTime,FileType.Mp4,FileType.Mp3,FileType.Eps,FileType.Heif,FileType.Avif));
    final InputSnapshot snapshot;
    final String name, detectedType, parseStatus;
    private final List<Directory> directories = new ArrayList<>();
    private final IdentityHashMap<Directory,Integer> directoryIds = new IdentityHashMap<>();
    private final List<TagAt> tags = new ArrayList<>();
    private final List<ErrorAt> errors = new ArrayList<>();
    private final List<Property> properties = new ArrayList<>();
    private static final class TagAt {
        final int directory, type;
        TagAt(int directory, int type) { this.directory = directory; this.type = type; }
    }
    private static final class ErrorAt {
        final int directory;
        final String text;
        ErrorAt(int directory, String text) { this.directory = directory; this.text = text; }
    }
    private static final class Property {
        final int directory, options;
        final String namespace, path, value, language, nodeKind;
        Property(int directory, XMPPropertyInfo info) {
            this.directory = directory; options = info.getOptions().getOptions(); namespace = info.getNamespace(); path = info.getPath();
            value = (String)info.getValue(); language = info.getLanguage();
            nodeKind = info.getOptions().isSchemaNode() ? "schema" : info.getOptions().isQualifier() ? "qualifier" : info.getOptions().isArray() ? "array" : info.getOptions().isStruct() ? "struct" : "property";
        }
    }
    static MetadataDocument read(File input, File directory, String name, BooleanSupplier stop) throws Exception {
        InputSnapshot snapshot = InputSnapshot.acquire(input,directory,MAX_INPUT_BYTES,stop); boolean success = false;
        try { MetadataDocument result = new MetadataDocument(snapshot,name,stop); success = true; return result; }
        catch (StackOverflowError error) { throw new IOException("Metadata parser recursion exhausted; no complete model returned",error); }
        finally { if (!success) snapshot.close(); }
    }
    private MetadataDocument(InputSnapshot snapshot, String name, BooleanSupplier stop) throws Exception {
        this.snapshot = snapshot; this.name = name; Metadata metadata = new Metadata(); String type = "Unknown", status = "parsed";
        try (AutoCloseable scope = XmlGate.cancellation(stop); InputStream file = new FileInputStream(snapshot.file);
             BufferedInputStream input = new BufferedInputStream(new FilterInputStream(file) {
                 @Override public int read() throws IOException { EvidenceRows.check(stop); return super.read(); }
                 @Override public int read(byte[] data, int offset, int length) throws IOException { EvidenceRows.check(stop); return in.read(data,offset,length); }
                 @Override public long skip(long length) throws IOException { EvidenceRows.check(stop); return super.skip(length); }
             },65536)) {
            FileType detected = FileTypeDetector.detectFileType(input,name); type = detected.toString();
            metadata = ImageMetadataReader.readMetadata(input,snapshot.size,name);
            if (!READERS.contains(detected)) { status = "identification-only"; errors.add(new ErrorAt(-1,"The engine recognized " + type + " but ImageMetadataReader has no metadata reader for this type. File-type tags are identification only.")); }
        } catch (ImageProcessingException | EOFException error) { status = "unsupported-or-invalid"; errors.add(new ErrorAt(-1,error.getMessage())); }
        EvidenceRows.check(stop); detectedType = type;
        for (Directory directory : metadata.getDirectories()) {
            EvidenceRows.check(stop); if (directories.size() == MAX_DIRECTORIES) throw new IOException("Metadata exceeds 20,000 directories");
            directoryIds.put(directory,directories.size()); directories.add(directory);
        }
        long xmpUnits = 0;
        for (int id = 0; id < directories.size(); id++) {
            EvidenceRows.check(stop); Directory directory = directories.get(id);
            for (Tag tag : directory.getTags()) { EvidenceRows.check(stop); if (tags.size() == MAX_TAGS) throw new IOException("Metadata exceeds 200,000 tags"); tags.add(new TagAt(id,tag.getTagType())); }
            for (String error : directory.getErrors()) { if (errors.size() == MAX_ERRORS) throw new IOException("Metadata exceeds 10,000 diagnostics"); errors.add(new ErrorAt(id,error)); }
            if (directory instanceof XmpDirectory) {
                XMPIterator values = ((XmpDirectory)directory).getXMPMeta().iterator();
                while (values.hasNext()) {
                    EvidenceRows.check(stop); if (properties.size() == MAX_XMP) throw new IOException("Metadata exceeds 100,000 XMP property/schema/qualifier nodes");
                    Property property = new Property(id,(XMPPropertyInfo)values.next());
                    xmpUnits += length(property.namespace)+length(property.path)+length(property.value)+length(property.language);
                    if (xmpUnits > 8L*1024*1024) throw new IOException("XMP property index exceeds eight Mi UTF-16 units"); properties.add(property);
                }
            }
        }
        parseStatus = !errors.isEmpty() && status.equals("parsed") ? "engine-diagnostics-present" : status;
    }
    private static int length(String value) { return value == null ? 0 : value.length(); }
    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }
    JSONObject provenance() throws Exception {
        return new JSONObject().put("engine",ENGINE).put("engineVersion",VERSION).put("engineSourceSha256","db4027ec3925959ecd26933ec1fbf4e48e01ad2b338dded702e1a6e62226cccc")
            .put("xmpEngine","Adobe XMP Core source from AOSP").put("xmpVersion",XMPMetaFactory.getVersionInfo().getMessage()).put("xmpSourceRevision",XMP_REVISION)
            .put("adapterVersion",1).put("networkUsed",false).put("executionObserved",false)
            .put("input",new JSONObject().put("name",name).put("sizeBytes",snapshot.size).put("sha256",snapshot.sha256));
    }
    JSONObject metadata() throws Exception {
        return provenance().put("detectedType",detectedType).put("parseStatus",parseStatus).put("directories",directories.size()).put("tags",tags.size()).put("diagnostics",errors.size()).put("xmpNodes",properties.size())
            .put("maxInputBytes",MAX_INPUT_BYTES).put("maxDirectories",MAX_DIRECTORIES).put("maxTags",MAX_TAGS).put("maxXmpNodes",MAX_XMP)
            .put("scope","Every directory, tag, engine diagnostic and XMP schema/property/qualifier returned by these readers. Unsupported or unrecognized fields/containers can remain absent; this is not all possible metadata in every format.")
            .put("locations","The engine does not provide reliable physical offsets for general tags. No tag offsets are invented. Binary value/chunk offsets are within the returned value, not the source file.")
            .put("rawValues","Interactive values/descriptions have disclosed previews. Tag/value JSON exports stream fullRawValue and fullEngineDescription. Binary values also support exact byte export. Unknown value types explicitly disclose string-only representation.")
            .put("engineSemantics","Tag IDs are scoped by directory instance/class. Repeated directories are retained; duplicate IDs within one directory may already be overwritten by the upstream engine. XMP is its normalized data model, not the original packet serialization.")
            .put("xmlPolicy","Source-built BSD XMP with bounded SAX preflight, no DTD/external entities/URI-only sources; 8 MiB bytes or 4 Mi UTF-16 units, depth 64 and 100,000 elements per packet. App model budgets fail explicitly.");
    }
    private String description(TagAt tag) {
        try { return directories.get(tag.directory).getDescription(tag.type); }
        catch (RuntimeException error) { return "Engine description failed: " + error.getClass().getSimpleName()+": "+error.getMessage(); }
    }
    private JSONObject tagFields(TagAt tag, BooleanSupplier stop) throws Exception {
        Directory directory = directories.get(tag.directory); String description = description(tag); Object value = directory.getObject(tag.type);
        return new JSONObject().put("directoryIndex",tag.directory).put("directoryName",directory.getName()).put("directoryClass",directory.getClass().getName())
            .put("tagType",tag.type).put("tagTypeHex",String.format(Locale.ROOT,"0x%08x",tag.type)).put("tagName",directory.getTagName(tag.type))
            .put("engineDescription",description == null ? JSONObject.NULL : description.substring(0,Math.min(description.length(),16384))).put("descriptionPreviewComplete",description == null || description.length() <= 16384)
            .put("rawValue",MetadataValues.preview(value,stop)).put("fullValueInTableExport",true).put("physicalLocationKnown",false);
    }
    Object rawValue(int directory, int tag) throws IOException {
        if (directory < 0 || directory >= directories.size() || !directories.get(directory).containsTag(tag)) throw new IOException("Metadata value selector unavailable");
        return directories.get(directory).getObject(tag);
    }
    EvidenceRows tags(int directoryId) throws Exception {
        List<Integer> values = new ArrayList<>(); for (int i = 0; i < tags.size(); i++) if (directoryId < 0 || tags.get(i).directory == directoryId) values.add(i);
        return new EvidenceRows("metadata-tags",directoryId < 0 ? "All collected metadata tags" : "Metadata tags • " + directories.get(directoryId).getName()) {
            int size() { return values.size(); }
            Row row(int i, BooleanSupplier stop) throws Exception {
                EvidenceRows.check(stop); int original = values.get(i); TagAt tag = tags.get(original); JSONObject fields = tagFields(tag,stop);
                return new Row(original,fields.getString("directoryName")+" • "+fields.getString("tagName"),fields.optString("engineDescription"),null,null,fields);
            }
            @Override void writeSelectedRow(Writer out, Row row, BooleanSupplier stop) throws Exception {
                JSONObject fields = row.json(); TagAt tag = new TagAt(fields.getInt("directoryIndex"),fields.getInt("tagType"));
                String encoded = row.encoded; out.write(encoded,0,encoded.length()-1); out.write(",\"fullRawValue\":");
                MetadataValues.write(out,rawValue(tag.directory,tag.type),stop); out.write(",\"fullEngineDescription\":"); String description = description(tag);
                if (description == null) out.write("null"); else MetadataValues.quoted(out,description); out.write('}');
            }
            JSONObject metadata() throws Exception { return MetadataDocument.this.metadata().put("selectedDirectoryIndex",directoryId < 0 ? JSONObject.NULL : directoryId); }
        };
    }
    EvidenceRows directories() {
        return new EvidenceRows("metadata-directories","Metadata directory instances / relationships") {
            int size() { return directories.size(); }
            Row row(int i, BooleanSupplier stop) throws Exception {
                EvidenceRows.check(stop); Directory directory = directories.get(i); Integer parent = directoryIds.get(directory.getParent());
                return new Row(i,directory.getName(),directory.getTagCount()+" tags • "+directory.getErrorCount()+" diagnostics",null,null,
                    new JSONObject().put("directoryIndex",i).put("directoryClass",directory.getClass().getName()).put("tagCount",directory.getTagCount()).put("diagnosticCount",directory.getErrorCount())
                        .put("parentDirectoryIndex",nullable(parent)).put("parentOutsideResult",directory.getParent()!=null && parent==null).put("hasXmp",directory instanceof XmpDirectory));
            }
            JSONObject metadata() throws Exception { return MetadataDocument.this.metadata(); }
        };
    }
    EvidenceRows errors(int directoryId) {
        List<Integer> selected = new ArrayList<>(); for (int i = 0; i < errors.size(); i++) if (directoryId < 0 || errors.get(i).directory==directoryId) selected.add(i);
        return new EvidenceRows("metadata-errors","Metadata reader diagnostics") {
            int size() { return selected.size(); }
            Row row(int i, BooleanSupplier stop) throws Exception { EvidenceRows.check(stop); ErrorAt error = errors.get(selected.get(i)); return new Row(selected.get(i),error.text,"Engine diagnostic; scope may be incomplete",null,null,new JSONObject().put("directoryIndex",error.directory).put("message",error.text)); }
            JSONObject metadata() throws Exception { return MetadataDocument.this.metadata().put("selectedDirectoryIndex",directoryId); }
        };
    }
    EvidenceRows xmp(int directoryId) {
        List<Integer> selected = new ArrayList<>(); for (int i = 0; i < properties.size(); i++) if (directoryId < 0 || properties.get(i).directory==directoryId) selected.add(i);
        return new EvidenceRows("metadata-xmp","XMP schemas / properties / arrays / qualifiers") {
            int size() { return selected.size(); }
            Row row(int i, BooleanSupplier stop) throws Exception {
                EvidenceRows.check(stop); Property property = properties.get(selected.get(i));
                JSONObject fields = new JSONObject().put("propertyIndex",selected.get(i)).put("directoryIndex",property.directory).put("namespace",nullable(property.namespace)).put("propertyPath",nullable(property.path))
                    .put("value",property.value == null ? JSONObject.NULL : property.value.substring(0,Math.min(4096,property.value.length()))).put("valueUtf16Units",length(property.value)).put("previewComplete",length(property.value)<=4096)
                    .put("fullValueInTableExport",true).put("nodeKind",property.nodeKind).put("language",nullable(property.language)).put("optionsRaw",property.options).put("optionsHex",String.format(Locale.ROOT,"0x%08x",property.options))
                    .put("locationMeaning","Normalized XMP data-model node; paths/indices/qualifiers do not identify physical XML byte offsets");
                return new Row(selected.get(i),property.path == null ? String.valueOf(property.namespace) : property.path,String.valueOf(property.value),null,null,fields);
            }
            @Override void writeSelectedRow(Writer out, Row row, BooleanSupplier stop) throws Exception { EvidenceRows.check(stop); String encoded = row.encoded; out.write(encoded,0,encoded.length()-1); out.write(",\"fullPropertyValue\":"); String value = properties.get(row.index).value; if (value == null) out.write("null"); else MetadataValues.quoted(out,value); out.write('}'); }
            JSONObject metadata() throws Exception { return MetadataDocument.this.metadata().put("selectedDirectoryIndex",directoryId); }
        };
    }
    static byte[] bytes(Object value) { return value instanceof byte[] ? (byte[])value : value instanceof StringValue ? ((StringValue)value).getBytes() : null; }
    static String text(Object value) { return value instanceof String ? (String)value : value instanceof StringValue ? value.toString() : null; }
    EvidenceRows value(int directory, int tag) throws Exception {
        Object raw = rawValue(directory,tag); byte[] bytes = bytes(raw); String text = text(raw);
        boolean array = raw != null && raw.getClass().isArray(); List<?> list = raw instanceof Collection ? new ArrayList<>((Collection<?>)raw) : null;
        int count = bytes != null ? Math.max(1,(bytes.length+65535)/65536) : text != null ? Math.max(1,(text.length()+4095)/4096) : array ? Array.getLength(raw) : list != null ? list.size() : 1;
        if (count > 1000000) throw new IOException("Metadata value view exceeds one million rows");
        return new EvidenceRows("metadata-value","Complete value • "+directories.get(directory).getTagName(tag)) {
            int size() { return count; }
            private Object part(int i) { return bytes != null ? Arrays.copyOfRange(bytes,i*65536,Math.min(bytes.length,(i+1)*65536)) : text != null ? text.substring(i*4096,Math.min(text.length(),(i+1)*4096)) : array ? Array.get(raw,i) : list != null ? list.get(i) : raw; }
            Row row(int i, BooleanSupplier stop) throws Exception {
                EvidenceRows.check(stop); Object value = part(i); JSONObject fields = new JSONObject().put("directoryIndex",directory).put("tagType",tag).put("valueIndex",i)
                    .put("valueByteOffset",bytes == null ? JSONObject.NULL : i*65536L).put("valueUtf16Offset",text == null ? JSONObject.NULL : i*4096L).put("rawValue",MetadataValues.preview(value,stop))
                    .put("fullValueInTableExport",true).put("locationMeaning","Offsets index a returned metadata value, not bytes in the source file");
                return new Row(i,"Value part "+i,bytes != null ? ((byte[])value).length+" value bytes" : String.valueOf(value),null,null,fields);
            }
            @Override void writeSelectedRow(Writer out, Row row, BooleanSupplier stop) throws Exception { String encoded = row.encoded; out.write(encoded,0,encoded.length()-1); out.write(",\"fullRawValue\":"); MetadataValues.write(out,part(row.index),stop); out.write('}'); }
            JSONObject metadata() throws Exception { return MetadataDocument.this.metadata().put("directoryIndex",directory).put("tagType",tag).put("valueKind",bytes != null ? "binary chunks" : text != null ? "UTF-16 chunks" : array || list != null ? "array elements" : "scalar").put("valueParts",count); }
        };
    }
    String xmpText(int index) throws IOException { if (index < 0 || index >= properties.size()) throw new IOException("XMP property index expired"); return properties.get(index).value; }
    @Override public void close() { snapshot.close(); }
}
