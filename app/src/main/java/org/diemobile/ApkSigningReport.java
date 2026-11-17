// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/** Independent, offline AOSP verification. No package installation is involved. */
final class ApkSigningReport {
    static final String VERSION = "8.9.2";
    // An explicit upper bound makes exported results reproducible across devices.
    static final int MAX_CHECKED_API = 35;
    private static final int DISPLAY_LIMIT = 200;

    private ApkSigningReport() {}

    /** A parser failure is distinct from a completed negative verification. */
    static final class Verification {
        final ApkVerifier.Result result;
        final String error;

        Verification(ApkVerifier.Result result, String error) {
            this.result = result;
            this.error = error;
        }

        boolean completed() { return result != null; }
        boolean verified() { return completed() && result.isVerified(); }
    }

    static Verification verify(File input) {
        try {
            return new Verification(new ApkVerifier.Builder(input)
                .setMaxCheckedPlatformVersion(MAX_CHECKED_API).build().verify(), "");
        } catch (Exception | LinkageError e) {
            return new Verification(null, e.toString());
        }
    }

    static String analyze(File input) {
        return describe(verify(input));
    }

    static String describe(Verification verification) {
        StringBuilder report = new StringBuilder("\nIndependent APK signature verification (AOSP apksig ")
            .append(VERSION).append(")\n")
            .append("Checked platform range: manifest minSdkVersion through API ")
            .append(MAX_CHECKED_API).append(". Newer platforms are not covered.\n");
        if (!verification.completed()) {
            report.append("Verification unavailable / input could not be verified: ")
                .append(verification.error).append('\n');
        } else {
            ApkVerifier.Result result = verification.result;
            report.append("Verified: ").append(result.isVerified()).append('\n')
                .append("v1 (JAR signing): ").append(result.isVerifiedUsingV1Scheme()).append('\n')
                .append("v2 (APK Signing Block): ").append(result.isVerifiedUsingV2Scheme()).append('\n')
                .append("v3 (signer/rotation metadata): ").append(result.isVerifiedUsingV3Scheme()).append('\n')
                .append("v3.1 (SDK-targeted rotation): ").append(result.isVerifiedUsingV31Scheme()).append('\n')
                .append("Scheme booleans mean successfully verified using that scheme in this platform range; false does not by itself mean the scheme is absent.\n");

            List<X509Certificate> signers = result.getSignerCertificates();
            report.append("Verified signer certificates: ").append(signers.size()).append('\n');
            for (int i = 0; i < Math.min(signers.size(), DISPLAY_LIMIT); i++) {
                X509Certificate cert = signers.get(i);
                report.append("\nSigner certificate ").append(i + 1)
                    .append("\n  Subject: ").append(cert.getSubjectX500Principal().getName())
                    .append("\n  Issuer: ").append(cert.getIssuerX500Principal().getName())
                    .append("\n  Serial: 0x").append(cert.getSerialNumber().toString(16))
                    .append("\n  Valid from: ").append(cert.getNotBefore().toInstant())
                    .append("\n  Valid until: ").append(cert.getNotAfter().toInstant())
                    .append("\n  Public key algorithm: ").append(cert.getPublicKey().getAlgorithm())
                    .append("\n  Certificate signature algorithm: ").append(cert.getSigAlgName());
                try {
                    report.append("\n  Certificate SHA-256: ").append(sha256(cert.getEncoded()))
                        .append("\n  SubjectPublicKeyInfo SHA-256: ")
                        .append(sha256(cert.getPublicKey().getEncoded())).append('\n');
                } catch (Exception e) {
                    report.append("\n  Certificate digest unavailable: ").append(e).append('\n');
                }
            }
            if (signers.size() > DISPLAY_LIMIT) report.append("Additional certificates omitted by display limit.\n");

            // getErrors() alone omits signer-local failures, including digest mismatches.
            appendIssues(report, "Verifier errors (including signer errors)", result.getAllErrors());
            appendIssues(report, "Verifier warnings (including signer warnings)", warnings(result));
        }
        report.append("\nInterpretation: verification checks integrity and signer identity according to the supported signing schemes and stated platform range. It does not establish publisher trust, software safety, or device compatibility. Android APK signers commonly use self-signed certificates. The v4 detached .idsig is not supplied or verified. Rotation lineage is not independently presented here.\n");
        return report.toString();
    }

    private static List<ApkVerifier.IssueWithParams> warnings(ApkVerifier.Result result) {
        List<ApkVerifier.IssueWithParams> all = new ArrayList<>(result.getWarnings());
        for (ApkVerifier.Result.V1SchemeSignerInfo s : result.getV1SchemeSigners()) all.addAll(s.getWarnings());
        for (ApkVerifier.Result.V1SchemeSignerInfo s : result.getV1SchemeIgnoredSigners()) all.addAll(s.getWarnings());
        for (ApkVerifier.Result.V2SchemeSignerInfo s : result.getV2SchemeSigners()) all.addAll(s.getWarnings());
        for (ApkVerifier.Result.V3SchemeSignerInfo s : result.getV3SchemeSigners()) all.addAll(s.getWarnings());
        for (ApkVerifier.Result.V3SchemeSignerInfo s : result.getV31SchemeSigners()) all.addAll(s.getWarnings());
        if (result.getSourceStampInfo() != null) all.addAll(result.getSourceStampInfo().getWarnings());
        return all;
    }

    private static void appendIssues(StringBuilder report, String title, List<ApkVerifier.IssueWithParams> issues) {
        report.append('\n').append(title).append(": ").append(issues.size()).append('\n');
        for (int i = 0; i < Math.min(issues.size(), DISPLAY_LIMIT); i++) {
            ApkVerifier.IssueWithParams issue = issues.get(i);
            report.append("  ").append(issue.getIssue().name()).append(": ").append(issue).append('\n');
        }
        if (issues.size() > DISPLAY_LIMIT) report.append("  PARTIAL: ")
            .append(issues.size() - DISPLAY_LIMIT).append(" additional issues omitted.\n");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return Inspector.toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
