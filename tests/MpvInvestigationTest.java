// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Actual libmpv JNI: demuxers/codecs, exact typed values, references and ownership. */
public final class MpvInvestigationTest {
    private static final BooleanSupplier RUN = () -> false;
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static String scan(File input, BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        try (RandomAccessFile file = new RandomAccessFile(input,"r")) {
            file.seek(Math.min(11,file.length()));
            long position = file.getFilePointer();
            Field descriptor = FileDescriptor.class.getDeclaredField("fd"); descriptor.setAccessible(true);
            String report = MpvEngine.scanFd(descriptor.getInt(file.getFD()));
            require(file.getFD().valid() && file.getFilePointer() == position,"MPV closed or repositioned the caller's descriptor");
            EvidenceRows.check(stop);
            return report;
        }
    }
    static JSONObject property(JSONObject report, String name) throws Exception {
        JSONArray properties = report.getJSONArray("properties");
        for (int i = 0; i < properties.length(); i++) if (properties.getJSONObject(i).getString("name").equals(name)) return properties.getJSONObject(i);
        throw new AssertionError("MPV property capture missing: "+name);
    }
    static JSONObject child(JSONObject map, String key) throws Exception {
        JSONArray children = map.getJSONArray("children");
        for (int i = 0; i < children.length(); i++) if (children.getJSONObject(i).getString("key").equals(key)) return children.getJSONObject(i).getJSONObject("node");
        throw new AssertionError("MPV map key missing: "+key);
    }
    private static void command(Path directory, String... arguments) throws Exception {
        Path log = directory.resolve("ffmpeg.log");
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Fixture generation timed out"); }
        require(process.exitValue()==0,"Fixture generation failed: "+Files.readString(log));
    }
    public static void main(String[] args) throws Exception {
        require(MpvEngine.AVAILABLE,"Real host libmpv JNI unavailable: "+MpvEngine.ERROR);
        Path directory = Path.of(args[0]);
        File wav = directory.resolve("tagged.wav").toFile();
        Files.write(wav.toPath(),MetadataFixtureFactory.wav());
        byte[] original = Files.readAllBytes(wav.toPath());
        JSONObject report = new JSONObject(scan(wav,RUN));
        require(report.optBoolean("ok") && report.getBoolean("fileProbeComplete"),"WAV did not produce a complete real probe: "+report.optString("error"));
        require(property(report,"file-format").getJSONObject("node").getString("value").equals("wav"),"WAV format recognition differs");
        JSONObject track = property(report,"track-list").getJSONObject("node").getJSONArray("children").getJSONObject(0);
        require(child(track,"codec").getString("value").equals("pcm_s16le"),"Real PCM codec recognition differs");
        require(child(track,"demux-samplerate").getLong("value")==44100 && child(track,"demux-channel-count").getLong("value")==2,"PCM channel/rate evidence missing");
        require(property(report,"decoder-list").getJSONObject("node").getJSONArray("children").length()>100,"Runtime decoder catalog is incomplete");
        require(report.getJSONObject("propertyCatalog").getJSONArray("children").length()==report.getInt("catalogCount"),"Property-name catalog incomplete");
        require(Arrays.equals(original,Files.readAllBytes(wav.toPath())),"Probe modified the original input");
        require(report.getLong("bytesRead")>0 && report.getLong("bytesRead")<=32L*1024*1024 && report.getLong("ungrantedCallbackUris")==0,"Read/URI budgets were not recorded");

        String title = "FOSS media / München 東京 😀";
        String longComment = "metadata-begin-"+"x".repeat(10000)+"-metadata-end";
        Path subtitles = directory.resolve("subtitles.srt"), chapters = directory.resolve("chapters.ffmeta");
        Files.writeString(subtitles,"1\n00:00:00,000 --> 00:00:00,800\nBonjour 世界\n",StandardCharsets.UTF_8);
        Files.writeString(chapters,";FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=1000\ntitle=Chapter Ω\n",StandardCharsets.UTF_8);
        File matroska = directory.resolve("streams.mkv").toFile();
        command(directory,"ffmpeg","-v","error","-f","lavfi","-i","color=c=blue:s=16x16:r=5","-f","lavfi","-i","anullsrc=r=8000:cl=mono",
            "-i",subtitles.toString(),"-i",chapters.toString(),"-map","0:v","-map","1:a","-map","2:s","-map_chapters","3","-t","1",
            "-c:v","ffv1","-c:a","pcm_s16le","-c:s","srt","-metadata","title="+title,"-metadata","comment="+longComment,
            "-metadata:s:a:0","language=eng","-metadata:s:s:0","language=fra","-y",matroska.toString());
        JSONObject media = new JSONObject(scan(matroska,RUN));
        require(media.getBoolean("ok") && media.getBoolean("fileProbeComplete"),"Matroska probe failed");
        require(property(media,"file-format").getJSONObject("node").getString("value").contains("matroska"),"Container recognition differs");
        JSONArray tracks = property(media,"track-list").getJSONObject("node").getJSONArray("children");
        require(tracks.length()==3,"Video/audio/subtitle tracks were dropped");
        Set<String> codecs = new HashSet<>();
        for (int i = 0; i < tracks.length(); i++) codecs.add(child(tracks.getJSONObject(i),"codec").getString("value"));
        require(codecs.contains("ffv1") && codecs.contains("pcm_s16le") && codecs.contains("subrip"),"Stream codec identities differ: "+codecs);
        JSONObject metadata = property(media,"metadata").getJSONObject("node");
        require(child(metadata,"title").getString("value").equals(title),"Unicode media title changed");
        require(new String(MpvDocument.decodeHex(child(metadata,"title").getString("utf8Hex"),RUN),StandardCharsets.UTF_8).equals(title),"Exact engine UTF-8 title bytes changed");
        require(property(media,"chapter-list").getJSONObject("node").getJSONArray("children").length()==1,"Chapter metadata disappeared");

        File snapshots = directory.resolve("mpv-inputs").toFile();
        BinaryRunner runner = MpvInvestigationTest::scan;
        try (MpvBackend backend = new MpvBackend(matroska,snapshots,"streams.mkv",runner)) {
            EvidenceRows rows = backend.open(backend.initialRequest(RUN),RUN);
            require(rows.size()>20,"File property evidence was reduced to a superficial summary");
            EvidenceRows.Row selected = null;
            for (int i = 0; i < rows.size(); i++) if (rows.row(i,RUN).name.equals("metadata")) selected=rows.row(i,RUN);
            require(selected!=null && selected.physicalOffset==null,"MPV tag has an invented physical offset");
            int index = selected.json().getInt("propertyIndex");
            String valueRequest = MpvBackend.request("node","",index,new JSONArray());
            EvidenceRows values = backend.open(valueRequest,RUN);
            EvidenceRows.Row comment = null;
            for (int i = 0; i < values.size(); i++) if (values.row(i,RUN).name.equalsIgnoreCase("comment")) comment=values.row(i,RUN);
            require(comment!=null && !comment.json().getJSONObject("nodePreview").getBoolean("valuePreviewComplete"),"Long media metadata lacks an explicit bounded preview");
            StringWriter selectedJson = new StringWriter(); values.writeSelectedRow(selectedJson,comment,RUN);
            require(new JSONObject(selectedJson.toString()).getJSONObject("fullNativeNode").getString("value").equals(longComment),"Selected record export truncated long media metadata");
            String leaf = MpvBackend.request("node","",index,comment.json().getJSONArray("nodePath"));
            StringWriter text = new StringWriter(); backend.writeText(leaf,text,RUN); require(text.toString().equals(longComment),"Complete media text export changed");
            require(new String(backend.binaryValue(leaf,RUN),StandardCharsets.UTF_8).equals(longComment),"Exact UTF-8 value export changed");
            StringWriter full = new StringWriter(); rows.writeReport(full,backend.provenance(),"deliberately unmatched",0,1,RUN);
            require(new JSONObject(full.toString()).getJSONArray("rows").length()==rows.size(),"Filter/page truncated the complete scoped export");
            require(full.toString().contains("metadata-end"),"Full property export lost complete tag data");
            Files.write(matroska.toPath(),new byte[]{0}); require(backend.byteSource().length()>1,"Input expiry altered the retained source");
            require(backend.provenance().getJSONObject("input").getString("sha256").length()==64,"Media provenance lost its source hash");
        }
        require(Objects.requireNonNull(snapshots.list()).length==0,"MPV source snapshot leaked");

        AtomicInteger networkRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        server.createContext("/",exchange->{networkRequests.incrementAndGet();exchange.sendResponseHeaders(200,original.length);exchange.getResponseBody().write(original);exchange.close();});
        server.start();
        try {
            String address="http://127.0.0.1:"+server.getAddress().getPort()+"/sample.wav";
            File playlist = directory.resolve("untrusted.m3u8").toFile();
            Files.writeString(playlist.toPath(),"#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:1,\n"+address+"\n#EXT-X-ENDLIST\n");
            JSONObject refused = new JSONObject(scan(playlist,RUN));
            require(refused.getBoolean("ok") && !refused.getBoolean("fileProbeComplete"),"Reference-bearing playlist was treated as successfully inspected media");
            require(networkRequests.get()==0,"MPV accessed a referenced network endpoint");
        } finally { server.stop(0); }
        File invalid = directory.resolve("invalid.bin").toFile(); Files.write(invalid.toPath(),new byte[]{1,2,3,4});
        JSONObject unsupported = new JSONObject(scan(invalid,RUN));
        require(unsupported.getBoolean("ok") && !unsupported.getBoolean("fileProbeComplete") && !unsupported.isNull("loadError"),"Unsupported file became empty media success");
        require(!new JSONObject(MpvEngine.scanFd(-1)).getBoolean("ok"),"Invalid MPV descriptor accepted");
        File large = directory.resolve("large.bin").toFile();
        try (RandomAccessFile file = new RandomAccessFile(large,"rw")) { file.setLength(MpvDocument.MAX_INPUT_BYTES+1); }
        require(!new JSONObject(scan(large,RUN)).getBoolean("ok"),"Native MPV input limit ignored"); Files.delete(large.toPath());

        BinaryRunner truncated = (file,stop)-> { JSONObject value = new JSONObject(report.toString()); value.getJSONArray("properties").remove(0); return value.toString(); };
        try (MpvBackend backend = new MpvBackend(wav,snapshots,"bad-transport.wav",truncated)) {
            try { backend.open(backend.initialRequest(RUN),RUN); throw new AssertionError("Incomplete MPV transport accepted"); }
            catch (IOException expected) { require(expected.getMessage().contains("incomplete"),"Unexpected transport error: "+expected); }
        }
        require(Objects.requireNonNull(snapshots.list()).length==0,"Rejected MPV report leaked its source");
        require(MpvReference.brief("duration").contains("estimate"),"Pinned upstream duration mechanism/reference missing");
        System.out.println("MPV JNI fixtures passed: actual PCM/FFV1/SubRip codecs, Matroska/WAV containers, Unicode/long tags, chapters, decoder/property catalogs, exact typed exports, read-only descriptor ownership, retained input, input/transport limits and no referenced HTTP access.");
        System.out.println("Actual host runtime: "+property(report,"mpv-version").getJSONObject("node").getString("value")+"; independently pinned ARM64 runtime requires device verification.");
    }
}
