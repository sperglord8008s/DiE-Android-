// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.apksig.ApkVerifier;
import com.android.apksig.SigningCertificateLineage;

import java.io.File;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Independent, offline AOSP verification. No package installation is involved. */
final class ApkSigningReport {
    static final String VERSION = "8.9.2";
    static final String BINARY_SHA256 =
            "c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89";
    static final String SOURCES_SHA256 =
            "c7a0f43d1e40fdf39725ac090f5c7bbaeae82d1e0bfd9cf2ee507a70eb5136b5";
    // An explicit upper bound makes exported results reproducible across devices.
    static final int MAX_CHECKED_API = 35;
    private static final int DISPLAY_LIMIT = 200;

    private ApkSigningReport() {}

    /** A parser failure is distinct from a completed negative verification. */
    static final class Verification {
        final ApkVerifier.Result result;
        final String error;
        final boolean idsigSupplied;
        final ApkSigningInput.V4Layout detachedLayout;

        Verification(
                ApkVerifier.Result result,
                String error,
                boolean idsigSupplied,
                ApkSigningInput.V4Layout detachedLayout) {
            this.result = result;
            this.error = error;
            this.idsigSupplied = idsigSupplied;
            this.detachedLayout = detachedLayout;
        }

        boolean completed() {
            return result != null;
        }

        boolean verified() {
            if (!completed() || !result.isVerified()) return false;
            // 8.9.2 Result.containsErrors/getAllErrors omit v4 signer errors. An
            // intact APK with a corrupt idsig can therefore have isVerified=true.
            if (idsigSupplied && !result.isVerifiedUsingV4Scheme()) return false;
            for (ApkVerifier.Result.V4SchemeSignerInfo signer : result.getV4SchemeSigners())
                if (signer.containsErrors()) return false;
            return true;
        }
    }

    static Verification verify(File input) {
        return verify(input, null);
    }

    /** The detached signature is explicitly selected; no sibling files are discovered. */
    static Verification verify(File input, File idsig) {
        try {
            return verify(input, idsig, () -> false);
        } catch (InterruptedIOException e) {
            return new Verification(null, e.toString(), idsig != null, null);
        }
    }

    static Verification verify(File input, File idsig, BooleanSupplier stop)
            throws InterruptedIOException {
        ApkSigningInput.V4Layout layout = null;
        try (ApkSigningInput checked = new ApkSigningInput(input, idsig, stop)) {
            layout = checked.layout;
            ApkVerifier.Result result =
                    new ApkVerifier.Builder(checked.source)
                            .setMaxCheckedPlatformVersion(MAX_CHECKED_API)
                            .setV4SignatureFile(idsig)
                            .build()
                            .verify();
            checked.check();
            return new Verification(result, "", idsig != null, layout);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            EvidenceRows.check(stop);
            return new Verification(null, e.toString(), idsig != null, layout);
        }
    }

    static String analyze(File input) {
        return describe(verify(input));
    }

    static String describe(Verification verification) {
        StringBuilder report =
                new StringBuilder("\nIndependent APK signature verification (AOSP apksig ")
                        .append(VERSION)
                        .append(")\n")
                        .append("Checked platform range: manifest minSdkVersion through API ")
                        .append(MAX_CHECKED_API)
                        .append(". Newer platforms are not covered.\n")
                        .append("Detached signature explicitly supplied: ")
                        .append(verification.idsigSupplied)
                        .append('\n');
        if (!verification.completed()) {
            report.append("Verification unavailable / input could not be verified: ")
                    .append(verification.error)
                    .append('\n');
        } else {
            ApkVerifier.Result result = verification.result;
            report.append("Verified (including any supplied detached signature): ")
                    .append(verification.verified())
                    .append('\n')
                    .append("Raw apksig aggregate verified flag: ")
                    .append(result.isVerified())
                    .append('\n')
                    .append("v1 (JAR signing): ")
                    .append(result.isVerifiedUsingV1Scheme())
                    .append('\n')
                    .append("v2 (APK Signing Block): ")
                    .append(result.isVerifiedUsingV2Scheme())
                    .append('\n')
                    .append("v3 (signer/rotation metadata): ")
                    .append(result.isVerifiedUsingV3Scheme())
                    .append('\n')
                    .append("v3.1 (SDK-targeted rotation): ")
                    .append(result.isVerifiedUsingV31Scheme())
                    .append('\n')
                    .append("v4 (explicitly supplied detached signature): ")
                    .append(result.isVerifiedUsingV4Scheme())
                    .append('\n')
                    .append(
                            "Scheme booleans mean successfully verified using that scheme in this"
                                + " platform range; false does not by itself mean the scheme is"
                                + " absent.\n");

            List<X509Certificate> signers = result.getSignerCertificates();
            report.append("Verified signer certificates: ").append(signers.size()).append('\n');
            for (int i = 0; i < Math.min(signers.size(), DISPLAY_LIMIT); i++) {
                X509Certificate cert = signers.get(i);
                report.append("\nSigner certificate ")
                        .append(i + 1)
                        .append("\n  Subject: ")
                        .append(cert.getSubjectX500Principal().getName())
                        .append("\n  Issuer: ")
                        .append(cert.getIssuerX500Principal().getName())
                        .append("\n  Serial: 0x")
                        .append(cert.getSerialNumber().toString(16))
                        .append("\n  Valid from: ")
                        .append(cert.getNotBefore().toInstant())
                        .append("\n  Valid until: ")
                        .append(cert.getNotAfter().toInstant())
                        .append("\n  Public key algorithm: ")
                        .append(cert.getPublicKey().getAlgorithm())
                        .append("\n  Certificate signature algorithm: ")
                        .append(cert.getSigAlgName());
                try {
                    report.append("\n  Certificate SHA-256: ")
                            .append(sha256(cert.getEncoded()))
                            .append("\n  SubjectPublicKeyInfo SHA-256: ")
                            .append(sha256(cert.getPublicKey().getEncoded()))
                            .append('\n');
                } catch (Exception e) {
                    report.append("\n  Certificate digest unavailable: ").append(e).append('\n');
                }
            }
            if (signers.size() > DISPLAY_LIMIT)
                report.append("Additional certificates omitted by display limit.\n");

            appendLineage(
                    report, "Combined rotation lineage", result.getSigningCertificateLineage());
            appendTargetedSigners(report, "v3", result.getV3SchemeSigners());
            appendTargetedSigners(report, "v3.1", result.getV31SchemeSigners());
            ApkVerifier.Result.SourceStampInfo stamp = result.getSourceStampInfo();
            report.append("\nSource stamp verified: ")
                    .append(result.isSourceStampVerified())
                    .append('\n');
            if (stamp != null) {
                report.append("Source stamp status: ")
                        .append(stamp.getSourceStampVerificationStatus())
                        .append('\n')
                        .append(
                                "Source stamp timestamp (reported Unix seconds; zero may mean"
                                    + " absent): ")
                        .append(stamp.getTimestampEpochSeconds())
                        .append('\n');
                if (stamp.getCertificate() != null)
                    try {
                        report.append("Source stamp certificate SHA-256: ")
                                .append(sha256(stamp.getCertificate().getEncoded()))
                                .append('\n');
                    } catch (Exception e) {
                        report.append("Source stamp certificate unavailable: ")
                                .append(e)
                                .append('\n');
                    }
                report.append("Source stamp certificate-lineage entries: ")
                        .append(stamp.getCertificatesInLineage().size())
                        .append('\n');
                appendIssues(
                        report, "Source stamp informational messages", stamp.getInfoMessages());
            }
            // Even getAllErrors() omits v4 and ignored JAR signer errors in pinned 8.9.2.
            appendIssues(
                    report,
                    "Verifier errors (including every returned signer scope)",
                    errors(result));
            appendIssues(report, "Verifier warnings (including signer warnings)", warnings(result));
        }
        report.append(
                "\n"
                    + "Interpretation: verification checks integrity and signer identity according"
                    + " to the supported signing schemes and stated platform range. It does not"
                    + " establish publisher trust, software safety, or device compatibility."
                    + " Android APK signers commonly use self-signed certificates. The main scan"
                    + " supplies no detached .idsig. A lineage describes signing-key succession and"
                    + " capabilities, not simultaneous co-signers or a device's actual"
                    + " update/rollback decision. A source stamp identifies a distribution signer,"
                    + " which can differ from the APK signer; its timestamp is a signed claim, not"
                    + " independent time attestation. The complete signing viewer retains parsed"
                    + " certificates, scoped diagnostics and lineage data beyond this text"
                    + " preview.\n");
        return report.toString();
    }

    private static void appendTargetedSigners(
            StringBuilder report,
            String scheme,
            List<ApkVerifier.Result.V3SchemeSignerInfo> signers) {
        report.append('\n')
                .append(scheme)
                .append(" targeted signer records: ")
                .append(signers.size())
                .append('\n');
        for (int i = 0; i < Math.min(signers.size(), DISPLAY_LIMIT); i++) {
            ApkVerifier.Result.V3SchemeSignerInfo signer = signers.get(i);
            report.append("Signer index ")
                    .append(signer.getIndex())
                    .append("; target API ")
                    .append(signer.getMinSdkVersion())
                    .append("–")
                    .append(signer.getMaxSdkVersion())
                    .append("; rotation targets development release: ")
                    .append(signer.getRotationTargetsDevRelease())
                    .append("; signer errors: ")
                    .append(signer.containsErrors())
                    .append('\n');
            appendLineage(
                    report,
                    scheme + " signer " + signer.getIndex() + " lineage",
                    signer.getSigningCertificateLineage());
        }
        if (signers.size() > DISPLAY_LIMIT)
            report.append("PARTIAL: additional targeted signers omitted from text preview.\n");
    }

    private static void appendLineage(
            StringBuilder report, String title, SigningCertificateLineage lineage) {
        report.append('\n')
                .append(title)
                .append(": ")
                .append(lineage == null ? "not returned" : lineage.size() + " entries")
                .append('\n');
        if (lineage == null) return;
        List<X509Certificate> certificates = lineage.getCertificatesInLineage();
        for (int i = 0; i < Math.min(certificates.size(), DISPLAY_LIMIT); i++) {
            X509Certificate certificate = certificates.get(i);
            try {
                SigningCertificateLineage.SignerCapabilities caps =
                        lineage.getSignerCapabilities(certificate);
                report.append("  Position ")
                        .append(i)
                        .append("; certificate SHA-256: ")
                        .append(sha256(certificate.getEncoded()))
                        .append("; installedData=")
                        .append(caps.hasInstalledData())
                        .append("; sharedUid=")
                        .append(caps.hasSharedUid())
                        .append("; permission=")
                        .append(caps.hasPermission())
                        .append("; rollback=")
                        .append(caps.hasRollback())
                        .append("; auth=")
                        .append(caps.hasAuth())
                        .append('\n');
            } catch (Exception e) {
                report.append("  Lineage entry unavailable: ").append(e).append('\n');
            }
        }
        if (certificates.size() > DISPLAY_LIMIT)
            report.append("PARTIAL: additional lineage entries omitted from text preview.\n");
    }

    static List<ApkVerifier.IssueWithParams> errors(ApkVerifier.Result result) {
        List<ApkVerifier.IssueWithParams> all = new ArrayList<>(result.getErrors());
        for (ApkVerifier.Result.V1SchemeSignerInfo s : result.getV1SchemeSigners())
            all.addAll(s.getErrors());
        for (ApkVerifier.Result.V1SchemeSignerInfo s : result.getV1SchemeIgnoredSigners())
            all.addAll(s.getErrors());
        for (ApkVerifier.Result.V2SchemeSignerInfo s : result.getV2SchemeSigners())
            all.addAll(s.getErrors());
        for (ApkVerifier.Result.V3SchemeSignerInfo s : result.getV3SchemeSigners())
            all.addAll(s.getErrors());
        for (ApkVerifier.Result.V3SchemeSignerInfo s : result.getV31SchemeSigners())
            all.addAll(s.getErrors());
        for (ApkVerifier.Result.V4SchemeSignerInfo s : result.getV4SchemeSigners())
            all.addAll(s.getErrors());
        if (result.getSourceStampInfo() != null)
            all.addAll(result.getSourceStampInfo().getErrors());
        return all;
    }

    static List<ApkVerifier.IssueWithParams> warnings(ApkVerifier.Result result) {
        List<ApkVerifier.IssueWithParams> all = new ArrayList<>(result.getWarnings());
        for (ApkVerifier.Result.V1SchemeSignerInfo s : result.getV1SchemeSigners())
            all.addAll(s.getWarnings());
        for (ApkVerifier.Result.V1SchemeSignerInfo s : result.getV1SchemeIgnoredSigners())
            all.addAll(s.getWarnings());
        for (ApkVerifier.Result.V2SchemeSignerInfo s : result.getV2SchemeSigners())
            all.addAll(s.getWarnings());
        for (ApkVerifier.Result.V3SchemeSignerInfo s : result.getV3SchemeSigners())
            all.addAll(s.getWarnings());
        for (ApkVerifier.Result.V3SchemeSignerInfo s : result.getV31SchemeSigners())
            all.addAll(s.getWarnings());
        for (ApkVerifier.Result.V4SchemeSignerInfo s : result.getV4SchemeSigners())
            all.addAll(s.getWarnings());
        if (result.getSourceStampInfo() != null)
            all.addAll(result.getSourceStampInfo().getWarnings());
        return all;
    }

    private static void appendIssues(
            StringBuilder report, String title, List<ApkVerifier.IssueWithParams> issues) {
        report.append('\n').append(title).append(": ").append(issues.size()).append('\n');
        for (int i = 0; i < Math.min(issues.size(), DISPLAY_LIMIT); i++) {
            ApkVerifier.IssueWithParams issue = issues.get(i);
            report.append("  ")
                    .append(issue.getIssue().name())
                    .append(": ")
                    .append(issue)
                    .append('\n');
        }
        if (issues.size() > DISPLAY_LIMIT)
            report.append("  PARTIAL: ")
                    .append(issues.size() - DISPLAY_LIMIT)
                    .append(" additional issues omitted.\n");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
