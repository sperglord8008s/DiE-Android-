// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.diemobile.ApkSigningFixture.unsigned;

import com.android.apksig.ApkSigner;
import com.android.apksig.SigningCertificateLineage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

/** Real signatures over a generated binary-manifest APK; never installs an APK. */
public final class ApkSigningTest {
    private static final byte[] PAYLOAD =
            "DIE_APK_SIGNING_FIXTURE_PAYLOAD".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(dir.resolve("fixture.p12"))) {
            keys.load(in, "fixture-only".toCharArray());
        }
        PrivateKey key = (PrivateKey) keys.getKey("fixture", "fixture-only".toCharArray());
        X509Certificate cert = (X509Certificate) keys.getCertificate("fixture");
        ApkSigner.SignerConfig signer =
                new ApkSigner.SignerConfig.Builder("fixture", key, List.of(cert)).build();

        File all = signed(dir, signer, "all", 21, true, true, true);
        ApkSigningReport.Verification verified = ApkSigningReport.verify(all);
        require(verified.verified(), ApkSigningReport.describe(verified));
        require(verified.result.isVerifiedUsingV1Scheme(), "v1 not verified");
        require(verified.result.isVerifiedUsingV2Scheme(), "v2 not verified");
        require(verified.result.isVerifiedUsingV3Scheme(), "v3 not verified");
        String fingerprint =
                Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        require(ApkSigningReport.describe(verified).contains(fingerprint), "Wrong signer identity");
        require(
                Arrays.equals(
                        cert.getEncoded(),
                        verified.result.getSignerCertificates().get(0).getEncoded()),
                "Wrong signer bytes");

        require(
                ApkSigningReport.verify(signed(dir, signer, "v1", 21, true, false, false))
                        .verified(),
                "v1-only verification failed");
        require(
                ApkSigningReport.verify(signed(dir, signer, "v2", 24, false, true, false))
                        .verified(),
                "v2-only verification failed");
        require(
                ApkSigningReport.verify(signed(dir, signer, "v3", 28, false, false, true))
                        .verified(),
                "v3-only verification failed");

        File unsigned = unsigned(dir.resolve("unsigned.apk"), 21);
        require(!ApkSigningReport.verify(unsigned).verified(), "Unsigned APK verified");
        byte[] bytes = Files.readAllBytes(all.toPath());
        int at = indexOf(bytes, PAYLOAD);
        require(at >= 0, "Stored fixture payload not found");
        bytes[at] ^= 1;
        File tampered = dir.resolve("tampered.apk").toFile();
        Files.write(tampered.toPath(), bytes);
        ApkSigningReport.Verification negative = ApkSigningReport.verify(tampered);
        require(
                negative.completed() && !negative.verified(),
                "Tampered APK must complete with negative result");
        require(!negative.result.getAllErrors().isEmpty(), "Tamper errors missing");
        for (var issue : negative.result.getAllErrors()) {
            require(
                    ApkSigningReport.describe(negative).contains(issue.getIssue().name()),
                    "Signer-local error missing from report");
        }
        require(
                negative.result.getAllErrors().size() > negative.result.getErrors().size(),
                "Fixture must exercise signer-local errors");
        Files.write(dir.resolve("truncated.apk"), Arrays.copyOf(bytes, 64));
        require(
                !ApkSigningReport.verify(dir.resolve("truncated.apk").toFile()).verified(),
                "Truncated APK verified");
        require(
                !ApkSigningReport.verify(dir.resolve("missing.apk").toFile()).completed(),
                "I/O failure treated as completed verification");
        rotationAndDetached(dir, keys, signer, key, cert);
        backendContracts(dir, keys);
        for (String alias : List.of("ec", "dsa")) {
            var actual =
                    ApkSigningReport.verify(
                            signed(
                                    dir,
                                    ApkSigningFixture.signer(keys, alias),
                                    alias,
                                    24,
                                    false,
                                    true,
                                    false));
            require(
                    actual.verified(),
                    "Actual " + alias + " signature failed: " + ApkSigningReport.describe(actual));
            var evidence = ApkSigningEvidence.capture(actual, false, () -> false);
            var publicKey =
                    evidence.table("certificates").getJSONObject(0).getJSONObject("publicKey");
            require(
                    Arrays.equals(
                            ApkSigningFixture.certificate(keys, alias).getPublicKey().getEncoded(),
                            Base64.getDecoder().decode(publicKey.getString("spkiBase64"))),
                    "Exact " + alias + " SPKI changed");
            if (alias.equals("ec"))
                require(
                        publicKey.getInt("fieldBits") == 256
                                && publicKey.getString("fieldKind").equals("prime")
                                && !publicKey.getJSONObject("generator").getBoolean("infinity"),
                        "EC field/generator metadata missing");
            else
                require(
                        !publicKey.getString("pHex").isEmpty()
                                && !publicKey.getString("yHex").isEmpty(),
                        "DSA parameters missing");
        }
        System.out.println(
                "APK signing fixtures passed: v1/v2/v3/v3.1/v4/v4.1, rotation/capabilities, source"
                        + " stamp, exact certificates, unsigned/tampered/truncated/mismatched"
                        + " signatures.");
    }

    private static void rotationAndDetached(
            Path dir,
            KeyStore keys,
            ApkSigner.SignerConfig original,
            PrivateKey oldKey,
            X509Certificate oldCertificate)
            throws Exception {
        PrivateKey rotatedKey = (PrivateKey) keys.getKey("rotated", "fixture-only".toCharArray());
        X509Certificate rotatedCertificate = (X509Certificate) keys.getCertificate("rotated");
        ApkSigner.SignerConfig rotated =
                new ApkSigner.SignerConfig.Builder(
                                "rotated", rotatedKey, List.of(rotatedCertificate))
                        .build();
        ApkSigner.SignerConfig stamp =
                new ApkSigner.SignerConfig.Builder(
                                "stamp",
                                (PrivateKey) keys.getKey("stamp", "fixture-only".toCharArray()),
                                List.of((X509Certificate) keys.getCertificate("stamp")))
                        .build();
        SigningCertificateLineage lineage =
                new SigningCertificateLineage.Builder(
                                new SigningCertificateLineage.SignerConfig.Builder(
                                                oldKey, oldCertificate)
                                        .build(),
                                new SigningCertificateLineage.SignerConfig.Builder(
                                                rotatedKey, rotatedCertificate)
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
        for (int rotationApi : new int[] {28, 33, 35}) {
            String name = "rotation-" + rotationApi;
            File output = dir.resolve(name + ".apk").toFile(),
                    idsig = dir.resolve(name + ".idsig").toFile();
            new ApkSigner.Builder(List.of(original, rotated))
                    .setInputApk(unsigned(dir.resolve(name + "-unsigned.apk"), 21))
                    .setOutputApk(output)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setSigningCertificateLineage(lineage)
                    .setMinSdkVersionForRotation(rotationApi)
                    .setV4SigningEnabled(true)
                    .setV4ErrorReportingEnabled(true)
                    .setV4SignatureOutputFile(idsig)
                    .setSourceStampSignerConfig(stamp)
                    .setSourceStampTimestampEnabled(true)
                    .build()
                    .sign();
            require(idsig.length() > 0, "Detached signature not produced");
            ApkSigningReport.Verification check = ApkSigningReport.verify(output, idsig);
            require(check.verified(), ApkSigningReport.describe(check));
            require(check.result.isVerifiedUsingV3Scheme(), "v3 rotation not verified");
            require(
                    check.result.isVerifiedUsingV31Scheme() == (rotationApi >= 33),
                    "Wrong v3.1 state");
            require(check.result.isVerifiedUsingV4Scheme(), "Detached v4 verification failed");
            require(
                    check.result.getV4SchemeSigners().size() == (rotationApi >= 33 ? 2 : 1),
                    "Wrong v4.1 signer count");
            require(
                    check.result.getSigningCertificateLineage().size() == 2,
                    "Rotation history missing");
            SigningCertificateLineage retained = check.result.getSigningCertificateLineage();
            require(
                    Arrays.equals(
                            retained.getCertificatesInLineage().get(0).getEncoded(),
                            oldCertificate.getEncoded()),
                    "Wrong lineage order/origin");
            require(
                    Arrays.equals(
                            retained.getCertificatesInLineage().get(1).getEncoded(),
                            rotatedCertificate.getEncoded()),
                    "Wrong rotated certificate");
            SigningCertificateLineage.SignerCapabilities caps =
                    retained.getSignerCapabilities(oldCertificate);
            require(
                    caps.hasInstalledData()
                            && !caps.hasSharedUid()
                            && caps.hasPermission()
                            && !caps.hasRollback()
                            && !caps.hasAuth(),
                    "Capabilities changed");
            require(check.result.isSourceStampVerified(), "Distribution stamp not verified");
            require(
                    check.result.getSourceStampInfo().getTimestampEpochSeconds() > 0,
                    "Source stamp timestamp missing");
            require(
                    Arrays.equals(
                            check.result.getSourceStampInfo().getCertificate().getEncoded(),
                            keys.getCertificate("stamp").getEncoded()),
                    "Stamp confused with APK signer");
            String text = ApkSigningReport.describe(check);
            require(
                    text.contains("permission=true; rollback=false; auth=false")
                            && text.contains("STAMP_VERIFIED"),
                    "Rotation/stamp evidence missing from text");
            require(
                    text.contains(
                            Inspector.toHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(oldCertificate.getEncoded()))),
                    "Historical signer missing");
            ApkSigningEvidence evidence = ApkSigningEvidence.capture(check, true, () -> false);
            require(
                    evidence.table("summary").getJSONObject(0).getBoolean("verified"),
                    "Complete evidence lost verification");
            require(
                    evidence.table("certificates").length() == 3,
                    "Certificate relationships duplicated/missing");
            require(evidence.table("lineages").length() > 0, "Scoped lineages missing");
            for (int i = 0; i < evidence.table("lineages").length(); i++) {
                var row = evidence.table("lineages").getJSONObject(i);
                SigningCertificateLineage decoded =
                        SigningCertificateLineage.readFromV3AttributeValue(
                                Base64.getDecoder()
                                        .decode(row.getString("encodedAttributeBase64")));
                require(
                        decoded.size() == row.getJSONArray("nodes").length(),
                        "Upstream lineage encoding not preserved");
            }
            require(
                    ApkSigningReport.verify(output).verified()
                            && !ApkSigningReport.verify(output).result.isVerifiedUsingV4Scheme(),
                    "Absent idsig inferred automatically");
            if (rotationApi >= 33) {
                require(
                        check.result.getV31SchemeSigners().get(0).getMinSdkVersion() == rotationApi,
                        "Target SDK lost");
                require(
                        check.result.getV3SchemeSigners().get(0).getMaxSdkVersion()
                                == rotationApi - 1,
                        "Original signer range wrong");
                require(
                        check.result.getV3SchemeSigners().get(0).getSigningCertificateLineage()
                                        == null
                                || check.result
                                                .getV3SchemeSigners()
                                                .get(0)
                                                .getSigningCertificateLineage()
                                                .size()
                                        == 1,
                        "Signer lineage merged with global history");
            }
        }
        File apk = dir.resolve("rotation-33.apk").toFile();
        byte[] detached = Files.readAllBytes(dir.resolve("rotation-33.idsig"));
        detached[detached.length - 1] ^= 1;
        File corrupt = dir.resolve("corrupt.idsig").toFile();
        Files.write(corrupt.toPath(), detached);
        ApkSigningReport.Verification failure = ApkSigningReport.verify(apk, corrupt);
        require(failure.completed() && !failure.verified(), "Corrupted verity tree must fail");
        require(
                failure.result.getV4SchemeSigners().stream()
                        .anyMatch(s -> !s.getErrors().isEmpty()),
                "Missing v4-local error fixture");
        require(
                failure.result.isVerified() && !failure.result.isVerifiedUsingV4Scheme(),
                "Pinned aggregate v4 omission no longer reproduced");
        require(
                ApkSigningReport.errors(failure.result).size()
                        > failure.result.getAllErrors().size(),
                "Pinned v4 getAllErrors omission no longer reproduced");
        require(
                ApkSigningReport.describe(failure).contains("V4_SIG_APK_TREE_DID_NOT_VERIFY"),
                "Detached error missing from report");
        ApkSigningEvidence failed = ApkSigningEvidence.capture(failure, true, () -> false);
        require(
                !failed.table("summary").getJSONObject(0).getBoolean("verified")
                        && failed.table("summary")
                                .getJSONObject(0)
                                .getBoolean("apksigResultVerified"),
                "Failed idsig not distinguished from raw aggregate flag");
        require(
                failed.table("diagnostics").toString().contains("V4_SIG_APK_TREE_DID_NOT_VERIFY"),
                "v4-local error missing from complete diagnostics");
        require(
                !ApkSigningReport.verify(apk, dir.resolve("rotation-35.idsig").toFile()).verified(),
                "Another APK's idsig accepted");
        Files.write(dir.resolve("short.idsig"), new byte[] {1, 2, 3});
        require(
                !ApkSigningReport.verify(apk, dir.resolve("short.idsig").toFile()).verified(),
                "Truncated detached file accepted");
        require(
                !ApkSigningReport.verify(apk, dir.resolve("missing.idsig").toFile()).completed(),
                "Missing detached input silently ignored");
        byte[] intact = Files.readAllBytes(dir.resolve("rotation-33.idsig"));
        var layout =
                ApkSigningReport.verify(apk, dir.resolve("rotation-33.idsig").toFile())
                        .detachedLayout;
        File treeOmitted = dir.resolve("tree-omitted.idsig").toFile();
        Files.write(treeOmitted.toPath(), Arrays.copyOf(intact, (int) layout.treeOffset - 4));
        ApkSigningReport.Verification withoutTree = ApkSigningReport.verify(apk, treeOmitted);
        require(
                withoutTree.verified() && withoutTree.detachedLayout.treeOffset == -1,
                "Optional omitted verity tree rejected");
        File trailer = dir.resolve("trailing.idsig").toFile();
        byte[] trailing = Arrays.copyOf(intact, intact.length + 3);
        trailing[intact.length] = 42;
        Files.write(trailer.toPath(), trailing);
        var withTrailer = ApkSigningReport.verify(apk, trailer);
        require(
                withTrailer.verified() && withTrailer.detachedLayout.trailingSize == 3,
                "Ignored trailing bytes not disclosed");
        Files.write(dir.resolve("allocation.idsig"), new byte[] {2, 0, 0, 0, -1, -1, -1, 127});
        var allocation = ApkSigningReport.verify(apk, dir.resolve("allocation.idsig").toFile());
        require(
                !allocation.completed() && allocation.error.contains("allocation length"),
                "Untrusted top-level allocation length reached engine");
        boolean cancelled = false;
        try {
            ApkSigningReport.verify(apk, null, () -> true);
        } catch (InterruptedIOException expected) {
            cancelled = true;
        }
        require(cancelled, "Signing cancellation was converted to an ordinary parse result");
        byte[] stampBytes = ((X509Certificate) keys.getCertificate("stamp")).getEncoded(),
                stamped = Files.readAllBytes(apk.toPath());
        int certificateAt = indexOf(stamped, stampBytes),
                nameAt =
                        indexOf(
                                stampBytes,
                                "DiE disposable stamp fixture".getBytes(StandardCharsets.UTF_8));
        require(certificateAt >= 0 && nameAt >= 0, "Stamp certificate source not found");
        stamped[certificateAt + nameAt] ^= 1;
        File damagedStamp = dir.resolve("damaged-stamp.apk").toFile();
        Files.write(damagedStamp.toPath(), stamped);
        var damaged = ApkSigningReport.verify(damagedStamp);
        require(
                damaged.verified() && !damaged.result.isSourceStampVerified(),
                "Stamp-only damage not independent from embedded APK signatures");
        require(
                ApkSigningEvidence.capture(damaged, false, () -> false)
                        .table("stamp")
                        .getJSONObject(0)
                        .getString("status")
                        .equals("STAMP_VERIFICATION_FAILED"),
                "Wrong damaged stamp status: " + ApkSigningReport.describe(damaged));
    }

    private static void backendContracts(Path dir, KeyStore keys) throws Exception {
        File source = dir.resolve("owned-parent.apk").toFile(),
                snapshots = dir.resolve("signing-snapshots").toFile();
        Files.copy(dir.resolve("rotation-33.apk"), source.toPath());
        byte[] original = Files.readAllBytes(source.toPath()),
                detached = Files.readAllBytes(dir.resolve("rotation-33.idsig"));
        String sha = Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(original));
        ApkSigningBackend backend =
                new ApkSigningBackend(source, snapshots, "fixture.apk", sha, original.length, null);
        String initial = backend.initialRequest(() -> false);
        require(
                backend.open(initial, () -> false)
                        .row(0, () -> false)
                        .json()
                        .getBoolean("verified"),
                "Owned embedded APK verification failed");
        String savedBeforeReplacement = backend.retentionState();
        backend.acceptInput(
                "idsig", new ByteArrayInputStream(detached), "signature\uD800.idsig", () -> false);
        require(
                backend.open(initial, () -> false).row(0, () -> false).json().getBoolean("v4"),
                "Explicit detached input not used");
        var certificates =
                backend.open("{\"mode\":\"selected\",\"table\":\"certificates\"}", () -> false);
        require(certificates.size() == 3, "Certificate association count changed");
        for (int i = 0; i < certificates.size(); i++) {
            var row = certificates.row(i, () -> false);
            require(row.physicalOffset == null, "Derived certificate has invented physical offset");
            var fields = row.json();
            X509Certificate expected = null;
            for (String alias : List.of("fixture", "rotated", "stamp")) {
                X509Certificate candidate = (X509Certificate) keys.getCertificate(alias);
                if (fields.getString("certificateSha256")
                        .equals(
                                Inspector.toHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(candidate.getEncoded()))))
                    expected = candidate;
            }
            require(expected != null, "Unknown signing certificate");
            var links = backend.related("{}", certificates, row, () -> false);
            for (var link : links) {
                if (link.label.equals("Export derBase64"))
                    require(
                            Arrays.equals(
                                    expected.getEncoded(),
                                    backend.binaryValue(link.request, () -> false)),
                            "DER export changed");
                if (link.label.equals("Export SubjectPublicKeyInfo DER"))
                    require(
                            Arrays.equals(
                                    expected.getPublicKey().getEncoded(),
                                    backend.binaryValue(link.request, () -> false)),
                            "SPKI export changed");
            }
            for (int e = 0; e < fields.getJSONArray("extensions").length(); e++) {
                var extension = fields.getJSONArray("extensions").getJSONObject(e);
                require(
                        Arrays.equals(
                                expected.getExtensionValue(extension.getString("oid")),
                                Base64.getDecoder()
                                        .decode(extension.getString("derOctetStringBase64"))),
                        "Unknown/known certificate extension changed");
            }
            require(
                    fields.getJSONArray("extensions").length() >= 5
                            && fields.getJSONObject("publicKey").getInt("modulusBits") == 2048,
                    "Detailed certificate fields absent");
        }
        var all = backend.open("{\"mode\":\"selected\",\"table\":\"all\"}", () -> false);
        StringWriter output = new StringWriter();
        all.writeReport(output, backend.provenance(), "nonmatching filter", 3, 1, () -> false);
        var report = new org.json.JSONObject(output.toString());
        require(
                report.getJSONArray("rows").length() == all.size() && all.size() > 10,
                "Complete signing export followed UI filter/page");
        require(
                !report.toString().contains("sessionDirectory")
                        && !report.toString().contains("analysis-input-"),
                "Private restoration paths leaked into evidence");
        String before = backend.provenance().getJSONObject("detachedInput").getString("sha256");
        InputStream oversize =
                new InputStream() {
                    long left = ApkSigningInput.MAX_IDSIG_BYTES + 1;

                    public int read() {
                        if (left-- <= 0) return -1;
                        return 0;
                    }

                    public int read(byte[] b, int off, int length) {
                        if (left <= 0) return -1;
                        int amount = (int) Math.min(left, length);
                        Arrays.fill(b, off, off + amount, (byte) 0);
                        left -= amount;
                        return amount;
                    }
                };
        boolean rejected = false;
        try {
            backend.acceptInput("idsig", oversize, "too-large.idsig", () -> false);
        } catch (IOException expected) {
            rejected = true;
        }
        require(
                rejected
                        && backend.provenance()
                                .getJSONObject("detachedInput")
                                .getString("sha256")
                                .equals(before),
                "Oversize input replaced prior evidence");
        boolean cancelled = false;
        try {
            backend.acceptInput(
                    "idsig", new ByteArrayInputStream(detached), "cancel.idsig", () -> true);
        } catch (InterruptedIOException expected) {
            cancelled = true;
        }
        require(
                cancelled
                        && backend.provenance()
                                .getJSONObject("detachedInput")
                                .getString("sha256")
                                .equals(before),
                "Cancelled input replaced prior evidence");
        String detachedRequest = "{\"mode\":\"selected\",\"table\":\"detached-input\"}";
        EvidenceRows detachedView = backend.open(detachedRequest, () -> false);
        File cachedDetached = backend.byteSource();
        byte[] changed = detached.clone();
        changed[0] ^= 1;
        Files.write(cachedDetached.toPath(), changed);
        rejected = false;
        try {
            backend.related(
                    detachedRequest, detachedView, detachedView.row(0, () -> false), () -> false);
        } catch (IOException expected) {
            rejected = true;
        }
        require(rejected, "Same-length private cache mutation reached associated byte actions");
        byte[] corrupt = Files.readAllBytes(dir.resolve("corrupt.idsig"));
        backend.acceptInput(
                "idsig", new ByteArrayInputStream(corrupt), "corrupt.idsig", () -> false);
        require(
                !backend.open(initial, () -> false)
                        .row(0, () -> false)
                        .json()
                        .getBoolean("verified"),
                "Failed detached verification claimed success");
        require(
                backend.open("{\"mode\":\"embedded\",\"table\":\"summary\"}", () -> false)
                        .row(0, () -> false)
                        .json()
                        .getBoolean("verified"),
                "Independent embedded APK result lost");
        backend.acceptInput(
                "idsig", new ByteArrayInputStream(detached), "restored.idsig", () -> false);
        require(source.delete(), "Parent deletion failed");
        backend.close(true);
        // The saved session handle predates both replacements. Its atomic source pointer
        // must restore the latest complete pair, rather than a deleted old attachment.
        backend =
                new ApkSigningBackend(
                        source,
                        snapshots,
                        "fixture.apk",
                        sha,
                        original.length,
                        savedBeforeReplacement);
        require(
                backend.open(initial, () -> false).row(0, () -> false).json().getBoolean("v4"),
                "Latest owned pair did not restore across parent expiry");
        require(
                Arrays.equals(
                        detached,
                        backend.binaryValue(
                                "{\"mode\":\"selected\",\"table\":\"idsig-bytes\"}", () -> false)),
                "Exact detached input export changed");
        backend.acceptInput("clear-idsig", null, "", () -> false);
        require(
                backend.provenance().isNull("detachedInput")
                        && !backend.open(initial, () -> false)
                                .row(0, () -> false)
                                .json()
                                .getBoolean("v4"),
                "Detached clearing lost embedded evidence");
        backend.close();
        require(
                Objects.requireNonNull(snapshots.listFiles()).length == 0,
                "Signing session snapshots leaked");
        File outside = dir.resolve("unrelated").toFile();
        outside.mkdir();
        Files.writeString(new File(outside, "source-state.json").toPath(), "keep");
        ApkSigningBackend invalid =
                new ApkSigningBackend(
                        source,
                        snapshots,
                        "fixture.apk",
                        null,
                        -1,
                        new org.json.JSONObject()
                                .put("sessionDirectory", outside.getAbsolutePath())
                                .toString());
        rejected = false;
        try {
            invalid.open(initial, () -> false);
        } catch (IOException expected) {
            rejected = true;
        } finally {
            invalid.close();
        }
        require(
                rejected && new File(outside, "source-state.json").isFile(),
                "Invalid restoration descriptor read/deleted outside session");
        System.out.println(
                "Owned signing backend passed: complete metadata/export, no invented offsets,"
                        + " stream budgets, cancellation, atomic latest-pair restoration, clear and"
                        + " cleanup.");
    }

    private static File signed(
            Path dir,
            ApkSigner.SignerConfig signer,
            String name,
            int min,
            boolean v1,
            boolean v2,
            boolean v3)
            throws Exception {
        File input = unsigned(dir.resolve(name + "-unsigned.apk"), min);
        File output = dir.resolve(name + ".apk").toFile();
        new ApkSigner.Builder(List.of(signer))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(min)
                .setV1SigningEnabled(v1)
                .setV2SigningEnabled(v2)
                .setV3SigningEnabled(v3)
                .setV4SigningEnabled(false)
                .build()
                .sign();
        return output;
    }

    private static int indexOf(byte[] all, byte[] value) {
        outer:
        for (int i = 0; i <= all.length - value.length; i++) {
            for (int j = 0; j < value.length; j++) if (all[i + j] != value[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
