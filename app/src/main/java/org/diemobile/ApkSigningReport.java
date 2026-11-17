// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Independent APK verification using the AOSP apksig library. This does not
 * install the package or equate a cryptographic signature with publisher trust.
 */
final class ApkSigningReport {
    private ApkSigningReport() {}

    static String analyze(File input) {
        StringBuilder report = new StringBuilder();
        report.append("\nIndependent APK signature verification (AOSP apksig 8.9.2)\n");
        try {
            ApkVerifier.Result result = new ApkVerifier.Builder(input).build().verify();
            report.append("Verified: ").append(result.isVerified()).append('\n')
                .append("v1 (JAR signing): ").append(result.isVerifiedUsingV1Scheme()).append('\n')
                .append("v2 (APK Signing Block): ").append(result.isVerifiedUsingV2Scheme()).append('\n')
                .append("v3 (signer/rotation metadata): ").append(result.isVerifiedUsingV3Scheme()).append('\n');
            List<X509Certificate> signers = result.getSignerCertificates();
            int index = 0;
            for (X509Certificate cert : signers) {
                report.append("\nSigner certificate ").append(++index)
                    .append("\n  Subject: ").append(cert.getSubjectX500Principal().getName())
                    .append("\n  Issuer: ").append(cert.getIssuerX500Principal().getName())
                    .append("\n  Serial: 0x").append(cert.getSerialNumber().toString(16))
                    .append("\n  Valid from: ").append(cert.getNotBefore())
                    .append("\n  Valid until: ").append(cert.getNotAfter())
                    .append("\n  Public key algorithm: ").append(cert.getPublicKey().getAlgorithm())
                    .append("\n  Certificate signature algorithm: ").append(cert.getSigAlgName())
                    .append("\n  Certificate SHA-256: ").append(Inspector.toHex(
                        MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())))
                    .append("\n  SubjectPublicKeyInfo SHA-256: ").append(Inspector.toHex(
                        MessageDigest.getInstance("SHA-256").digest(cert.getPublicKey().getEncoded())))
                    .append('\n');
            }
            report.append("\nVerifier errors:\n");
            int count=0;
            for (ApkVerifier.IssueWithParams issue:result.getErrors()) {
                if(++count>100){report.append("  Additional errors omitted\n");break;}
                report.append("  ").append(issue.toString()).append('\n');
            }
            if(count==0)report.append("  None reported\n");
            report.append("Verifier warnings:\n");
            count=0;
            for (ApkVerifier.IssueWithParams issue:result.getWarnings()) {
                if(++count>100){report.append("  Additional warnings omitted\n");break;}
                report.append("  ").append(issue.toString()).append('\n');
            }
            if(count==0)report.append("  None reported\n");
        } catch (Exception | LinkageError e) {
            report.append("Verification unavailable or failed: ").append(e).append('\n');
        }
        report.append("\nInterpretation: verification checks integrity and signer identity according to the signing schemes and platform range supported by the verifier. It does not establish that the signer is trustworthy, that the software is safe, or that an APK is compatible with a particular device. Android APK signers commonly use self-signed certificates. The v4 detached .idsig is not supplied or verified by this workflow.\n");
        return report.toString();
    }
}
