// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.apksig.ApkSigner;
import com.android.apksig.SigningCertificateLineage;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Development-only signed data. keytool creates disposable keys; no inspected code runs. */
public final class ApkSigningFixture {
    public static final byte[] PAYLOAD =
            "DIE_APK_SIGNING_FIXTURE_PAYLOAD".getBytes(StandardCharsets.US_ASCII);

    private ApkSigningFixture() {}

    public static void main(String[] args) throws Exception {
        generateKeys(Path.of(args[0]));
    }

    public static void generateKeys(Path path) throws Exception {
        Path log = path.resolveSibling("keytool-fixture.log");
        for (String alias : List.of("fixture", "rotated", "stamp", "ec", "dsa")) {
            List<String> arguments =
                    new ArrayList<>(
                            List.of(
                                    Path.of(System.getProperty("java.home"), "bin", "keytool")
                                            .toString(),
                                    "-genkeypair",
                                    "-alias",
                                    alias,
                                    "-keystore",
                                    path.toString(),
                                    "-storetype",
                                    "PKCS12",
                                    "-storepass",
                                    "fixture-only",
                                    "-keypass",
                                    "fixture-only",
                                    "-dname",
                                    "CN=DiE disposable " + alias + " fixture",
                                    "-keyalg",
                                    alias.equals("ec") ? "EC" : alias.equals("dsa") ? "DSA" : "RSA",
                                    "-validity",
                                    "30",
                                    "-ext",
                                    "BC=ca:false",
                                    "-ext",
                                    "KU=digitalSignature",
                                    "-ext",
                                    "EKU=codeSigning",
                                    "-ext",
                                    "SAN=dns:fixture.example,uri:https://example.invalid/signing"));
            arguments.addAll(
                    alias.equals("ec")
                            ? List.of("-groupname", "secp256r1")
                            : List.of("-keysize", "2048"));
            Process process =
                    new ProcessBuilder(arguments)
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile())
                            .start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Disposable fixture keytool timed out");
            }
            if (process.exitValue() != 0)
                throw new IOException(
                        "Disposable fixture keytool failed: "
                                + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }
        Files.deleteIfExists(log);
    }

    public static KeyStore keys(Path path) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            keys.load(input, "fixture-only".toCharArray());
        }
        return keys;
    }

    public static X509Certificate certificate(KeyStore keys, String alias) throws Exception {
        return (X509Certificate) keys.getCertificate(alias);
    }

    public static PrivateKey key(KeyStore keys, String alias) throws Exception {
        return (PrivateKey) keys.getKey(alias, "fixture-only".toCharArray());
    }

    public static ApkSigner.SignerConfig signer(KeyStore keys, String alias) throws Exception {
        return new ApkSigner.SignerConfig.Builder(
                        alias, key(keys, alias), List.of(certificate(keys, alias)))
                .build();
    }

    public static File rotated(Path dir, KeyStore keys, int api) throws Exception {
        SigningCertificateLineage lineage =
                new SigningCertificateLineage.Builder(
                                new SigningCertificateLineage.SignerConfig.Builder(
                                                key(keys, "fixture"), certificate(keys, "fixture"))
                                        .build(),
                                new SigningCertificateLineage.SignerConfig.Builder(
                                                key(keys, "rotated"), certificate(keys, "rotated"))
                                        .build())
                        .setOriginalCapabilities(
                                new SigningCertificateLineage.SignerCapabilities.Builder()
                                        .setInstalledData(true)
                                        .setSharedUid(false)
                                        .setPermission(true)
                                        .setRollback(false)
                                        .setAuth(false)
                                        .build())
                        .build();
        File output = dir.resolve("rotation-" + api + ".apk").toFile();
        new ApkSigner.Builder(List.of(signer(keys, "fixture"), signer(keys, "rotated")))
                .setInputApk(unsigned(dir.resolve("unsigned-" + api + ".apk"), 21))
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setSigningCertificateLineage(lineage)
                .setMinSdkVersionForRotation(api)
                .setV4SigningEnabled(true)
                .setV4ErrorReportingEnabled(true)
                .setV4SignatureOutputFile(dir.resolve("rotation-" + api + ".idsig").toFile())
                .setSourceStampSignerConfig(signer(keys, "stamp"))
                .setSourceStampTimestampEnabled(true)
                .build()
                .sign();
        return output;
    }

    public static File unsigned(Path path, int min) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            entry(out, "AndroidManifest.xml", manifest(min));
            entry(out, "assets/fixture.txt", PAYLOAD);
        }
        return path.toFile();
    }

    private static void entry(ZipOutputStream out, String name, byte[] data) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc.getValue());
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    /** Minimal AOSP ResXMLTree with typed SDK attributes and a UTF-8 string pool. */
    private static byte[] manifest(int min) throws IOException {
        String[] strings = {
            "minSdkVersion",
            "targetSdkVersion",
            "manifest",
            "package",
            "org.diemobile.fixture",
            "uses-sdk",
            "android",
            "http://schemas.android.com/apk/res/android"
        };
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = data.size();
            byte[] s = strings[i].getBytes(StandardCharsets.UTF_8);
            data.write(strings[i].length());
            data.write(s.length);
            data.write(s);
            data.write(0);
        }
        while (data.size() % 4 != 0) data.write(0);
        ByteBuffer pool = buffer(28 + offsets.length * 4 + data.size());
        header(pool, 1, 28, pool.capacity());
        pool.putInt(strings.length)
                .putInt(0)
                .putInt(0x100)
                .putInt(28 + offsets.length * 4)
                .putInt(0);
        for (int offset : offsets) pool.putInt(offset);
        pool.put(data.toByteArray());
        ByteBuffer map = buffer(16);
        header(map, 0x180, 8, 16);
        map.putInt(0x0101020c).putInt(0x01010270);
        byte[][] chunks = {
            pool.array(),
            map.array(),
            namespace(0x100),
            start(2, new int[][] {{-1, 3, 4, 3, 4}}),
            start(5, new int[][] {{7, 0, -1, 0x10, min}, {7, 1, -1, 0x10, 28}}),
            end(5),
            end(2),
            namespace(0x101)
        };
        int size = 8;
        for (byte[] chunk : chunks) size += chunk.length;
        ByteBuffer xml = buffer(size);
        header(xml, 3, 8, size);
        for (byte[] chunk : chunks) xml.put(chunk);
        return xml.array();
    }

    private static byte[] start(int name, int[][] attributes) {
        ByteBuffer b = buffer(36 + attributes.length * 20);
        node(b, 0x102);
        b.putInt(-1)
                .putInt(name)
                .putShort((short) 20)
                .putShort((short) 20)
                .putShort((short) attributes.length)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) 0);
        for (int[] a : attributes)
            b.putInt(a[0])
                    .putInt(a[1])
                    .putInt(a[2])
                    .putShort((short) 8)
                    .put((byte) 0)
                    .put((byte) a[3])
                    .putInt(a[4]);
        return b.array();
    }

    private static byte[] end(int name) {
        ByteBuffer b = buffer(24);
        node(b, 0x103);
        b.putInt(-1).putInt(name);
        return b.array();
    }

    private static byte[] namespace(int type) {
        ByteBuffer b = buffer(24);
        node(b, type);
        b.putInt(6).putInt(7);
        return b.array();
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void header(ByteBuffer b, int type, int headerSize, int size) {
        b.putShort((short) type).putShort((short) headerSize).putInt(size);
    }

    private static void node(ByteBuffer b, int type) {
        header(b, type, 16, b.capacity());
        b.putInt(1).putInt(-1);
    }
}
