// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Acquires every selected APK before deriving package relationships; inspected code never runs. */
final class PackageSetDocument implements AutoCloseable {
    static final int MAX_INPUTS = 64;
    static final long MAX_APK = 256L * 1024 * 1024, MAX_APK_TOTAL = 512L * 1024 * 1024;
    static final long MAX_MANIFEST = 8L * 1024 * 1024, MAX_MANIFEST_TOTAL = 64L * 1024 * 1024;
    static final long MAX_MODEL_CHARACTERS = 64L * 1024 * 1024;

    static final class Input {
        final File file;
        final String name, expectedSha, error;
        final long expectedSize;
        final boolean restored;

        Input(File file, String name, String expectedSha, long expectedSize) {
            this(file, name, expectedSha, expectedSize, false, "");
        }

        Input(
                File file,
                String name,
                String expectedSha,
                long expectedSize,
                boolean restored,
                String error) {
            this.file = file;
            this.name = name;
            this.expectedSha = expectedSha;
            this.expectedSize = expectedSize;
            this.restored = restored;
            this.error = error;
        }
    }

    static final class Source {
        final int index;
        final String name;
        final JSONArray errors = new JSONArray();
        InputSnapshot apk;
        ArchiveStore.Extracted xml;
        JSONObject manifestMember = new JSONObject();
        PackageManifest manifest;
        ApkSigningEvidence signing;
        Long declaredBytes;
        boolean identityStable;
        boolean retainedFailure;

        Source(int index, String name) {
            this.index = index;
            this.name = name;
        }

        void error(String scope, Throwable error) throws Exception {
            errors.put(new JSONObject().put("scope", scope).put("error", error.toString()));
        }

        JSONObject identity() throws Exception {
            JSONObject result =
                    new JSONObject()
                            .put("sourceIndex", index)
                            .put("name", name)
                            .put(
                                    "declaredBytes",
                                    declaredBytes == null ? JSONObject.NULL : declaredBytes)
                            .put("acquired", apk != null)
                            .put("identityStableDuringAnalysis", identityStable)
                            .put("retainedPriorAcquisitionFailure", retainedFailure)
                            .put("errors", errors)
                            .put("manifestParsed", manifest != null)
                            .put("signingResultCollected", signing != null);
            result.put(
                    "declaredManifestMember",
                    manifestMember.length() == 0 ? JSONObject.NULL : manifestMember);
            if (apk != null) result.put("sizeBytes", apk.size).put("sha256", apk.sha256);
            if (xml != null)
                result.put(
                        "manifestInput",
                        new JSONObject()
                                .put("sizeBytes", xml.size)
                                .put("sha256", xml.sha256)
                                .put("archiveMember", manifestMember));
            if (manifest != null)
                result.put(
                                "packageName",
                                manifest.packageName() == null
                                        ? JSONObject.NULL
                                        : manifest.packageName())
                        .put("splitIdentityKnown", manifest.splitKnown())
                        .put(
                                "splitName",
                                manifest.splitName() == null
                                        ? JSONObject.NULL
                                        : manifest.splitName())
                        .put(
                                "versionCode64UnsignedDecimal",
                                manifest.versionCode64() == null
                                        ? JSONObject.NULL
                                        : manifest.versionCode64())
                        .put("declarationDiagnostics", manifest.diagnostics);
            if (signing != null)
                result.put("signingSummary", signing.table("summary").getJSONObject(0));
            return result;
        }

        boolean verified() throws Exception {
            return signing != null
                    && signing.table("summary").getJSONObject(0).getBoolean("verified");
        }

        Set<String> signerDer() throws Exception {
            Set<String> values = new LinkedHashSet<>();
            if (!verified()) return values;
            JSONArray signers =
                    signing.table("summary")
                            .getJSONObject(0)
                            .getJSONArray("verifiedSignerCertificateIndices");
            for (int i = 0; i < signers.length(); i++)
                values.add(
                        signing.table("certificates")
                                .getJSONObject(signers.getInt(i))
                                .getString("derBase64"));
            return values;
        }

        JSONArray signerHashes() throws Exception {
            JSONArray values = new JSONArray();
            if (!verified()) return values;
            JSONArray signers =
                    signing.table("summary")
                            .getJSONObject(0)
                            .getJSONArray("verifiedSignerCertificateIndices");
            for (int i = 0; i < signers.length(); i++)
                values.put(
                        signing.table("certificates")
                                .getJSONObject(signers.getInt(i))
                                .getString("certificateSha256"));
            return values;
        }
    }

    final List<Source> sources = new ArrayList<>();
    final File directory;
    private long reservedApkBytes, reservedManifestBytes, modelCharacters;
    private final long started = System.nanoTime();
    private final BooleanSupplier stop;
    private final long apkReservationLimit;

    private PackageSetDocument(File directory, BooleanSupplier stop, long apkReservationLimit) {
        this.directory = directory;
        this.stop = stop;
        this.apkReservationLimit = apkReservationLimit;
    }

    static PackageSetDocument read(
            List<Input> inputs, File directory, BinaryRunner runner, BooleanSupplier stop)
            throws Exception {
        return read(inputs, directory, runner, stop, MAX_APK_TOTAL);
    }

    /** A caller may lower the aggregate budget, never exceed the production ceiling. */
    static PackageSetDocument read(
            List<Input> inputs,
            File directory,
            BinaryRunner runner,
            BooleanSupplier stop,
            long apkReservationLimit)
            throws Exception {
        if (apkReservationLimit < 0 || apkReservationLimit > MAX_APK_TOTAL)
            throw new IOException("Invalid package-set aggregate APK budget");
        if (inputs.isEmpty() || inputs.size() > MAX_INPUTS)
            throw new IOException("Package set requires 1–64 explicit APK inputs");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create package-set snapshot directory");
        PackageSetDocument document = new PackageSetDocument(directory, stop, apkReservationLimit);
        boolean success = false;
        try {
            for (Input input : inputs) {
                document.check();
                if (input.name == null || input.name.length() > 4096)
                    throw new IOException("Package input name exceeds its budget");
                Source source = new Source(document.sources.size(), input.name);
                document.sources.add(source);
                document.acquire(source, input, runner);
            }
            document.check();
            success = true;
            return document;
        } finally {
            if (!success) document.close();
        }
    }

    private BooleanSupplier cancellation() {
        return () -> stop.getAsBoolean() || System.nanoTime() - started > 90_000_000_000L;
    }

    private void check() throws InterruptedIOException {
        EvidenceRows.check(stop);
        if (System.nanoTime() - started > 90_000_000_000L)
            throw new InterruptedIOException(
                    "Package-set cooperative 90-second acquisition deadline exceeded");
    }

    private void acquire(Source source, Input input, BinaryRunner runner) throws Exception {
        try {
            if (!input.error.isEmpty()) {
                source.retainedFailure = true;
                JSONArray original = new JSONArray(input.error);
                for (int i = 0; i < original.length(); i++) {
                    check();
                    source.errors.put(original.getJSONObject(i));
                }
                if (input.expectedSize >= 0) {
                    source.declaredBytes = input.expectedSize;
                    if (input.expectedSize <= MAX_APK
                            && input.expectedSize <= apkReservationLimit - reservedApkBytes)
                        reservedApkBytes += input.expectedSize;
                }
                return;
            }
            if (!input.file.isFile())
                throw new IOException("Selected APK is unavailable or not a regular file");
            long declared = input.restored ? input.expectedSize : input.file.length();
            source.declaredBytes = declared;
            if (declared < 0
                    || declared > MAX_APK
                    || declared > apkReservationLimit - reservedApkBytes)
                throw new IOException("APK exceeds per-file / aggregate declared-byte reservation");
            reservedApkBytes += declared;
            source.apk =
                    input.restored
                            ? InputSnapshot.restore(
                                    input.file,
                                    directory,
                                    declared,
                                    input.expectedSha,
                                    cancellation())
                            : InputSnapshot.acquire(
                                    input.file, directory, declared, cancellation());
            if (source.apk.size != declared
                    || input.expectedSize >= 0 && input.expectedSize != source.apk.size
                    || input.expectedSha != null && !input.expectedSha.equals(source.apk.sha256))
                throw new IOException(
                        "APK differs from its captured size / SHA-256 or changed during"
                                + " inventory/acquisition");
        } catch (Exception error) {
            check();
            source.error("acquisition", error);
            if (source.apk != null) {
                source.errors
                        .getJSONObject(source.errors.length() - 1)
                        .put(
                                "observedSnapshotIdentity",
                                new JSONObject()
                                        .put("sizeBytes", source.apk.size)
                                        .put("sha256", source.apk.sha256))
                        .put(
                                "expectedSizeBytes",
                                input.expectedSize < 0 ? JSONObject.NULL : input.expectedSize)
                        .put(
                                "expectedSha256",
                                input.expectedSha == null ? JSONObject.NULL : input.expectedSha);
                source.apk.close();
                source.apk = null;
            }
            return;
        }
        try {
            ArchiveStore.Inventory archive = ArchiveStore.read(source.apk.file, cancellation());
            ArchiveStore.Member manifest = null;
            for (ArchiveStore.Member member : archive.members)
                if (member.name.equals("AndroidManifest.xml")) {
                    if (manifest != null)
                        throw new IOException("Repeated AndroidManifest.xml names are ambiguous");
                    manifest = member;
                }
            if (manifest == null) throw new IOException("No exact root AndroidManifest.xml member");
            if (manifest.size < 0
                    || manifest.size > MAX_MANIFEST
                    || manifest.size > MAX_MANIFEST_TOTAL - reservedManifestBytes)
                throw new IOException(
                        "Compiled manifest exceeds per-file / aggregate declared-byte reservation");
            reservedManifestBytes += manifest.size;
            source.manifestMember =
                    new JSONObject()
                            .put("index", manifest.index)
                            .put("name", manifest.name)
                            .put("declaredSizeBytes", manifest.size)
                            .put("compressedSizeBytes", manifest.compressedSize)
                            .put("crc32Unsigned", manifest.crc32)
                            .put("compressionMethod", manifest.method);
            source.xml = ArchiveStore.extract(archive, manifest, directory, cancellation());
            AndroidResourceDocument parsed =
                    new AndroidResourceDocument(
                            source.xml.file,
                            "AndroidManifest.xml",
                            source.xml.sha256,
                            runner.scan(source.xml.file, cancellation()),
                            cancellation());
            PackageManifest declarations = new PackageManifest(parsed, cancellation());
            reserveModel(declarations.json());
            source.manifest = declarations;
        } catch (Exception | StackOverflowError error) {
            check();
            source.error("compiled-manifest", error);
        }
        try {
            ApkSigningEvidence signing =
                    ApkSigningEvidence.capture(
                            ApkSigningReport.verify(source.apk.file, null, cancellation()),
                            false,
                            cancellation());
            reserveModel(signing.data);
            source.signing = signing;
        } catch (Exception | LinkageError | StackOverflowError error) {
            check();
            source.error("signing", error);
        }
        try {
            source.apk.checkIdentity(cancellation());
            source.identityStable = true;
        } catch (Exception error) {
            check();
            source.error("final-input-identity", error);
            source.manifest = null;
            source.signing = null;
        }
    }

    private void reserveModel(JSONObject value) throws Exception {
        check();
        int count = value.toString().length();
        if (count > MAX_MODEL_CHARACTERS - modelCharacters)
            throw new IOException(
                    "Package set exceeds its 64 Mi-character collected-model budget; this scope"
                            + " remains unknown");
        modelCharacters += count;
    }

    JSONObject acquisition() throws Exception {
        return new JSONObject()
                .put("reservedApkBytes", reservedApkBytes)
                .put("apkDeclaredReservationLimit", apkReservationLimit)
                .put("reservedManifestBytes", reservedManifestBytes)
                .put("collectedModelCharacters", modelCharacters)
                .put("sequentialAcquisition", true)
                .put(
                        "scope",
                        "Explicit selected inputs captured sequentially; no atomic OS package"
                                + " snapshot, installed-state validation or automatic search for"
                                + " missing splits");
    }

    @Override
    public void close() {
        for (Source source : sources) {
            if (source.apk != null) source.apk.close();
            if (source.xml != null) source.xml.file.delete();
        }
    }
}
