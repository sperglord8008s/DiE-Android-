// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.apksig.ApkSigner;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

/** Real signatures over a generated binary-manifest APK; never installs an APK. */
public final class ApkSigningTest {
    private static final byte[] PAYLOAD = "DIE_APK_SIGNING_FIXTURE_PAYLOAD".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(dir.resolve("fixture.p12"))) {
            keys.load(in, "fixture-only".toCharArray());
        }
        PrivateKey key = (PrivateKey)keys.getKey("fixture", "fixture-only".toCharArray());
        X509Certificate cert = (X509Certificate)keys.getCertificate("fixture");
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("fixture", key, List.of(cert)).build();

        File all = signed(dir, signer, "all", 21, true, true, true);
        ApkSigningReport.Verification verified = ApkSigningReport.verify(all);
        require(verified.verified(), ApkSigningReport.describe(verified));
        require(verified.result.isVerifiedUsingV1Scheme(), "v1 not verified");
        require(verified.result.isVerifiedUsingV2Scheme(), "v2 not verified");
        require(verified.result.isVerifiedUsingV3Scheme(), "v3 not verified");
        String fingerprint = Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        require(ApkSigningReport.describe(verified).contains(fingerprint), "Wrong signer identity");
        require(Arrays.equals(cert.getEncoded(), verified.result.getSignerCertificates().get(0).getEncoded()), "Wrong signer bytes");

        require(ApkSigningReport.verify(signed(dir, signer, "v1", 21, true, false, false)).verified(), "v1-only verification failed");
        require(ApkSigningReport.verify(signed(dir, signer, "v2", 24, false, true, false)).verified(), "v2-only verification failed");
        require(ApkSigningReport.verify(signed(dir, signer, "v3", 28, false, false, true)).verified(), "v3-only verification failed");

        File unsigned = unsigned(dir.resolve("unsigned.apk"), 21);
        require(!ApkSigningReport.verify(unsigned).verified(), "Unsigned APK verified");
        byte[] bytes = Files.readAllBytes(all.toPath());
        int at = indexOf(bytes, PAYLOAD);
        require(at >= 0, "Stored fixture payload not found");
        bytes[at] ^= 1;
        File tampered = dir.resolve("tampered.apk").toFile();
        Files.write(tampered.toPath(), bytes);
        ApkSigningReport.Verification negative = ApkSigningReport.verify(tampered);
        require(negative.completed() && !negative.verified(), "Tampered APK must complete with negative result");
        require(!negative.result.getAllErrors().isEmpty(), "Tamper errors missing");
        for (var issue : negative.result.getAllErrors()) {
            require(ApkSigningReport.describe(negative).contains(issue.getIssue().name()), "Signer-local error missing from report");
        }
        require(negative.result.getAllErrors().size() > negative.result.getErrors().size(), "Fixture must exercise signer-local errors");
        Files.write(dir.resolve("truncated.apk"), Arrays.copyOf(bytes, 64));
        require(!ApkSigningReport.verify(dir.resolve("truncated.apk").toFile()).verified(), "Truncated APK verified");
        require(!ApkSigningReport.verify(dir.resolve("missing.apk").toFile()).completed(), "I/O failure treated as completed verification");
        System.out.println("APK signing fixtures passed: v1, v2, v3, combined, certificate, unsigned, tampered and truncated.");
    }

    private static File signed(Path dir, ApkSigner.SignerConfig signer, String name, int min,
                               boolean v1, boolean v2, boolean v3) throws Exception {
        File input = unsigned(dir.resolve(name + "-unsigned.apk"), min);
        File output = dir.resolve(name + ".apk").toFile();
        new ApkSigner.Builder(List.of(signer)).setInputApk(input).setOutputApk(output)
            .setMinSdkVersion(min).setV1SigningEnabled(v1).setV2SigningEnabled(v2)
            .setV3SigningEnabled(v3).setV4SigningEnabled(false).build().sign();
        return output;
    }

    private static File unsigned(Path path, int min) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            entry(out, "AndroidManifest.xml", manifest(min));
            entry(out, "assets/fixture.txt", PAYLOAD);
        }
        return path.toFile();
    }

    private static void entry(ZipOutputStream out, String name, byte[] data) throws IOException {
        CRC32 crc = new CRC32(); crc.update(data);
        ZipEntry entry = new ZipEntry(name); entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length); entry.setCompressedSize(data.length); entry.setCrc(crc.getValue());
        out.putNextEntry(entry); out.write(data); out.closeEntry();
    }

    /** Minimal AOSP ResXMLTree with typed SDK attributes and a UTF-8 string pool. */
    private static byte[] manifest(int min) throws IOException {
        String[] strings = {"minSdkVersion", "targetSdkVersion", "manifest", "package",
            "org.diemobile.fixture", "uses-sdk", "android", "http://schemas.android.com/apk/res/android"};
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = data.size(); byte[] s = strings[i].getBytes(StandardCharsets.UTF_8);
            data.write(strings[i].length()); data.write(s.length); data.write(s); data.write(0);
        }
        while (data.size() % 4 != 0) data.write(0);
        ByteBuffer pool = buffer(28 + offsets.length * 4 + data.size());
        header(pool, 1, 28, pool.capacity());
        pool.putInt(strings.length).putInt(0).putInt(0x100).putInt(28 + offsets.length * 4).putInt(0);
        for (int offset : offsets) pool.putInt(offset);
        pool.put(data.toByteArray());
        ByteBuffer map = buffer(16); header(map, 0x180, 8, 16); map.putInt(0x0101020c).putInt(0x01010270);
        byte[][] chunks = {pool.array(), map.array(), namespace(0x100),
            start(2, new int[][]{{-1,3,4,3,4}}),
            start(5, new int[][]{{7,0,-1,0x10,min},{7,1,-1,0x10,28}}),
            end(5), end(2), namespace(0x101)};
        int size = 8; for (byte[] chunk : chunks) size += chunk.length;
        ByteBuffer xml = buffer(size); header(xml, 3, 8, size);
        for (byte[] chunk : chunks) xml.put(chunk);
        return xml.array();
    }

    private static byte[] start(int name, int[][] attributes) {
        ByteBuffer b = buffer(36 + attributes.length * 20); node(b, 0x102);
        b.putInt(-1).putInt(name).putShort((short)20).putShort((short)20)
            .putShort((short)attributes.length).putShort((short)0).putShort((short)0).putShort((short)0);
        for (int[] a : attributes) b.putInt(a[0]).putInt(a[1]).putInt(a[2])
            .putShort((short)8).put((byte)0).put((byte)a[3]).putInt(a[4]);
        return b.array();
    }
    private static byte[] end(int name) {
        ByteBuffer b = buffer(24); node(b, 0x103); b.putInt(-1).putInt(name); return b.array();
    }
    private static byte[] namespace(int type) {
        ByteBuffer b = buffer(24); node(b, type); b.putInt(6).putInt(7); return b.array();
    }
    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static void header(ByteBuffer b, int type, int headerSize, int size) {
        b.putShort((short)type).putShort((short)headerSize).putInt(size);
    }
    private static void node(ByteBuffer b, int type) { header(b, type, 16, b.capacity()); b.putInt(1).putInt(-1); }
    private static int indexOf(byte[] all, byte[] value) {
        outer: for (int i = 0; i <= all.length - value.length; i++) {
            for (int j = 0; j < value.length; j++) if (all[i+j] != value[j]) continue outer;
            return i;
        }
        return -1;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
