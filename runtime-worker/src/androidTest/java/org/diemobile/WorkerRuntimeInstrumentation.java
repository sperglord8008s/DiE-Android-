// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.system.*;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;

/**
 * Real Android processes, Binder/PFDs and unchanged production JNI; no host transport substitute.
 */
public final class WorkerRuntimeInstrumentation extends Instrumentation {
    private final JSONArray checks = new JSONArray();
    private Context target;
    private File directory;
    private long started;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        started = SystemClock.elapsedRealtime();
        target = getTargetContext();
        Bundle result = new Bundle();
        boolean success = false;
        JSONObject report = new JSONObject();
        try {
            report.put("schema", "org.diemobile.android-worker-runtime")
                    .put("schemaVersion", 1)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)))
                    .put("applicationId", target.getPackageName())
                    .put("applicationUid", target.getApplicationInfo().uid)
                    .put("instrumentationUid", android.os.Process.myUid())
                    .put("sourceRevision", BuildConfig.SOURCE_REVISION)
                    .put("sourceDirty", BuildConfig.SOURCE_DIRTY)
                    .put(
                            "scope",
                            "Actual x86_64 Android production worker, hash/archive JNI, Binder,"
                                + " descriptors, token cancellation and the production platform"
                                + " media preview helper. This does not verify ARM64, UI, MPV"
                                + " playback/PDF or 16 KiB-page runtime.");
            require(
                    android.os.Process.myUid() == target.getApplicationInfo().uid,
                    "Instrumentation must run as the normal target app UID");
            directory = new File(target.getCacheDir(), "worker-runtime-fixtures");
            require(
                    directory.isDirectory() || directory.mkdirs(),
                    "Cannot create private fixture directory");
            File abc = new File(directory, "abc.bin");
            write(abc, "abc".getBytes(StandardCharsets.US_ASCII));
            JSONObject small = hash(abc);
            require(
                    digests(small).get("MD4").equals("a448017aaf21d8525fc10ae87aa6729d"),
                    "Actual Android native MD4 abc vector differs");
            require(
                    digests(small).get("CRC32").equals("352441c2"),
                    "Actual Android native CRC32 abc vector differs");
            record("isolated-native-hash-vectors", small);
            largeHash();
            archive();
            mediaPreview();
            cancelBlockedReporter(abc);
            record("normal-client-recovers-after-worker-death", hash(abc));
            success = true;
        } catch (Throwable failure) {
            try {
                StringWriter text = new StringWriter();
                failure.printStackTrace(new PrintWriter(text));
                report.put("failure", text.toString());
            } catch (Exception ignored) {
            }
        } finally {
            try {
                report.put("passed", success)
                        .put("checks", checks)
                        .put("elapsedMs", SystemClock.elapsedRealtime() - started);
                File output = new File(target.getFilesDir(), "worker-runtime-report.json");
                try (Writer out =
                        new OutputStreamWriter(
                                new FileOutputStream(output), StandardCharsets.UTF_8)) {
                    EvidenceJson.write(out, report, () -> false);
                }
                result.putString("runtimePassed", Boolean.toString(success));
                result.putString("runtimeReport", output.getAbsolutePath());
                result.putString("stream", report.toString(2));
            } catch (Throwable failure) {
                success = false;
                result.putString("runtimePassed", "false");
                result.putString("stream", failure.toString());
            }
            if (directory != null) {
                File[] files = directory.listFiles();
                if (files != null) for (File file : files) if (file.isFile()) file.delete();
            }
            finish(
                    success ? android.app.Activity.RESULT_OK : android.app.Activity.RESULT_CANCELED,
                    result);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private static byte[] read(File file) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[65536];
            int count;
            while ((count = in.read(bytes)) >= 0) output.write(bytes, 0, count);
        }
        return output.toByteArray();
    }

    private void record(String name, JSONObject evidence) throws Exception {
        checks.put(
                new JSONObject().put("name", name).put("passed", true).put("evidence", evidence));
    }

    private void worker(JSONObject report, String engine) throws Exception {
        JSONObject identity = report.getJSONObject("worker");
        require(
                identity.getBoolean("isolatedUid"),
                "Production worker did not confirm UID isolation");
        require(
                identity.getInt("workerUid") != android.os.Process.myUid(),
                "Parser ran under ordinary app UID");
        require(
                identity.getInt("applicationUid") == target.getApplicationInfo().uid,
                "Worker belongs to a different app UID");
        require(identity.getString("engine").equals(engine), "Worker engine identity differs");
    }

    private JSONObject hash(File file) throws Exception {
        try (BinaryWorkerClient client = new BinaryWorkerClient(target, "hashes")) {
            JSONObject report = new JSONObject(client.scan(file, () -> false));
            worker(report, "hashes");
            require(report.getBoolean("ok"), report.optString("error"));
            require(
                    report.getBoolean("wholeInputComplete")
                            && report.getLong("bytesRead") == file.length(),
                    "Hash report is not complete");
            require(report.getLong("fileSizeBytes") == file.length(), "Hash source size differs");
            return report;
        }
    }

    private static Map<String, String> digests(JSONObject report) throws Exception {
        Map<String, String> values = new HashMap<>();
        JSONArray rows = report.getJSONArray("digests");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject value = rows.getJSONObject(i);
            require(
                    values.put(value.getString("algorithm"), value.getString("hex")) == null,
                    "Duplicate digest algorithm");
        }
        require(values.size() == 8, "Incomplete native digest set");
        return values;
    }

    private void largeHash() throws Exception {
        File file = new File(directory, "fully-written-16MiB-plus.bin");
        long size = 16L * 1024 * 1024 + 17;
        Map<String, MessageDigest> expected = new LinkedHashMap<>();
        for (String name : new String[] {"MD5", "SHA1", "SHA224", "SHA256", "SHA384", "SHA512"})
            expected.put(
                    name,
                    MessageDigest.getInstance(
                            name.startsWith("SHA") ? name.replace("SHA", "SHA-") : name));
        CRC32 crc = new CRC32();
        byte[] block = new byte[65536];
        for (int i = 0; i < block.length; i++) block[i] = (byte) (i * 37 + 11);
        try (OutputStream out = new FileOutputStream(file)) {
            for (long left = size; left > 0; ) {
                int count = (int) Math.min(left, block.length);
                out.write(block, 0, count);
                crc.update(block, 0, count);
                for (MessageDigest digest : expected.values()) digest.update(block, 0, count);
                left -= count;
            }
        }
        JSONObject report = hash(file);
        Map<String, String> got = digests(report);
        require(
                got.get("CRC32").equals(String.format(Locale.ROOT, "%08x", crc.getValue())),
                "Large CRC differs");
        for (Map.Entry<String, MessageDigest> entry : expected.entrySet())
            require(
                    got.get(entry.getKey()).equals(Inspector.toHex(entry.getValue().digest())),
                    "Large native " + entry.getKey() + " differs from independent JCA");
        record("fully-written-large-file-through-real-isolated-fd", report);
        require(file.delete(), "Large fixture cleanup failed");
    }

    private void archive() throws Exception {
        byte[] payload =
                "Exact Android isolated archive payload 世界 😀\u0000\n"
                        .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(zipped)) {
            gzip.write(payload);
        }
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        source.write(new byte[37]);
        source.write(zipped.toByteArray());
        File original = new File(directory, "archive-disguised-as-image.jpeg");
        write(original, source.toByteArray());
        try (InputSnapshot input =
                        InputSnapshot.acquire(
                                original, directory, NativeArchiveStore.MAX_INPUT, () -> false);
                BinaryWorkerClient client = new BinaryWorkerClient(target, "archive")) {
            NativeArchiveStore.Inventory inventory =
                    NativeArchiveStore.inspect(input, 37, input.size - 37, client, () -> false);
            worker(inventory.json(), "archive");
            require(
                    inventory.complete && inventory.members.size() == 1,
                    "Actual native archive inventory incomplete");
            JSONObject evidence = new JSONObject().put("inspection", inventory.json());
            try (NativeArchiveStore.Acquired member =
                    NativeArchiveStore.acquire(
                            inventory, inventory.members.get(0), directory, client, () -> false)) {
                require(
                        Arrays.equals(payload, read(member.input.file)),
                        "Actual Android writable-PFD decoded bytes differ");
                evidence.put("acquisition", new JSONObject(member.extractionReport))
                        .put(
                                "independentPayloadSha256",
                                Inspector.toHex(
                                        MessageDigest.getInstance("SHA-256").digest(payload)));
                worker(new JSONObject(member.extractionReport), "archive");
            }
            String aliasRefusal = null;
            try {
                client.extract(input.file, input.file, 37, input.size - 37, 0, () -> false);
            } catch (IOException refused) {
                aliasRefusal = refused.getMessage();
            }
            require(
                    "Archive output must be a separate empty private file".equals(aliasRefusal),
                    "Client did not refuse input/output alias before worker dispatch");
            input.checkIdentity(() -> false);
            evidence.put("clientAliasRefusal", aliasRefusal);
            record("real-isolated-archive-read-write-descriptors", evidence);
        }
    }

    private byte[] asset(String name) throws Exception {
        try (InputStream in = target.getAssets().open("test-fixtures/" + name);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            int count;
            while ((count = in.read(block)) >= 0) {
                require(bytes.size() + count <= 65536, "Owned media fixture exceeds its budget");
                bytes.write(block, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private void mediaPreview() throws Exception {
        JSONObject evidence = new JSONObject();
        for (String fixture : new String[] {"green-64x48.mp4", "pcm-500ms.wav"}) {
            byte[] payload = asset(fixture);
            ByteArrayOutputStream blob = new ByteArrayOutputStream();
            blob.write(new byte[37]);
            blob.write(payload);
            blob.write(new byte[43]);
            File source = new File(directory, "media-" + fixture + ".blob");
            write(source, blob.toByteArray());
            try (InputSnapshot input =
                    InputSnapshot.acquire(source, directory, 65536, () -> false)) {
                MediaFrame.Result preview =
                        MediaFrame.read(
                                input,
                                new FileRange(input.size, 37, payload.length),
                                24,
                                () -> false);
                try {
                    JSONObject report = preview.report();
                    require(
                            report.getLong("physicalOffset") == 37
                                    && report.getLong("storedSizeBytes") == payload.length,
                            "Platform preview lost its exact embedded interval");
                    require(
                            report.getString("sourceSha256").equals(input.sha256),
                            "Platform preview source identity differs");
                    require(
                            report.isNull("actualTimestampUs"),
                            "Preview invented a frame timestamp");
                    if (fixture.endsWith(".mp4")) {
                        require(
                                "yes".equals(preview.metadata.get("hasVideo")),
                                "No actual video track");
                        require(
                                "64".equals(preview.metadata.get("videoWidth"))
                                        && "48".equals(preview.metadata.get("videoHeight")),
                                "Actual platform video dimensions differ from generated fixture");
                        long duration = Long.parseLong(preview.metadata.get("durationMs"));
                        require(
                                duration >= 900 && duration <= 1100,
                                "Actual video duration differs");
                        if (Build.VERSION.SDK_INT >= 27) {
                            require(
                                    preview.bitmap != null,
                                    "Actual scaled platform video frame unavailable: " + report);
                            require(
                                    preview.bitmap.getWidth() <= 24
                                            && preview.bitmap.getHeight() <= 24,
                                    "Actual video frame exceeded requested dimensions");
                            int pixel =
                                    preview.bitmap.getPixel(
                                            preview.bitmap.getWidth() / 2,
                                            preview.bitmap.getHeight() / 2);
                            int red = android.graphics.Color.red(pixel),
                                    green = android.graphics.Color.green(pixel),
                                    blue = android.graphics.Color.blue(pixel);
                            require(
                                    green > 80 && red < 60 && blue < 60,
                                    "Actual decoded fixture pixel is not independently expected"
                                        + " green");
                            report.put(
                                    "independentCenterArgb",
                                    String.format(Locale.ROOT, "%08x", pixel));
                        } else {
                            require(
                                    preview.bitmap == null
                                            && preview.frameStatus.contains("API 27+"),
                                    "API 26 must keep metadata without unbounded video decoding");
                        }
                    } else {
                        require(
                                "yes".equals(preview.metadata.get("hasAudio")),
                                "No actual audio track");
                        require(
                                preview.bitmap == null,
                                "Audio-only fixture produced an invented frame");
                        long duration = Long.parseLong(preview.metadata.get("durationMs"));
                        require(duration >= 490 && duration <= 510, "Actual PCM duration differs");
                    }
                    input.checkIdentity(() -> false);
                    evidence.put(fixture, report);
                } finally {
                    if (preview.bitmap != null) preview.bitmap.recycle();
                }
            }
        }
        evidence.put(
                "fixtureManifest",
                new JSONObject(new String(asset("fixture-manifest.json"), StandardCharsets.UTF_8)));
        record("exact-range-platform-media-preview", evidence);
    }

    private void cancelBlockedReporter(File input) throws Exception {
        AtomicReference<Messenger> remote = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1), died = new CountDownLatch(1);
        ServiceConnection connection =
                new ServiceConnection() {
                    public void onServiceConnected(ComponentName name, IBinder binder) {
                        remote.set(new Messenger(binder));
                        connected.countDown();
                    }

                    public void onServiceDisconnected(ComponentName name) {
                        died.countDown();
                    }
                };
        boolean bound =
                target.bindService(
                        new Intent(target, BinaryWorkerService.class),
                        connection,
                        Context.BIND_AUTO_CREATE);
        require(bound, "Could not bind actual worker for backpressure cancellation");
        ParcelFileDescriptor[] pipe = null;
        try {
            require(connected.await(15, TimeUnit.SECONDS), "Worker binding timed out");
            pipe = ParcelFileDescriptor.createPipe();
            FileDescriptor sink = pipe[1].getFileDescriptor();
            int flags = Os.fcntlInt(sink, OsConstants.F_GETFL, 0), filled = 0;
            byte[] bytes = new byte[4096];
            try {
                Os.fcntlInt(sink, OsConstants.F_SETFL, flags | OsConstants.O_NONBLOCK);
                while (filled < 2 * 1024 * 1024) {
                    try {
                        int count = Os.write(sink, bytes, 0, bytes.length);
                        require(count > 0, "Pipe made no progress");
                        filled += count;
                    } catch (ErrnoException full) {
                        if (full.errno != OsConstants.EAGAIN) throw full;
                        break;
                    }
                }
            } finally {
                Os.fcntlInt(sink, OsConstants.F_SETFL, flags);
            }
            require(
                    filled > 0 && filled < 2 * 1024 * 1024,
                    "Could not establish a bounded full report pipe");
            String token = UUID.randomUUID().toString();
            try (ParcelFileDescriptor source =
                    ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)) {
                Message run = Message.obtain(null, BinaryWorkerService.RUN);
                Bundle data = run.getData();
                data.putString("token", token);
                data.putString("engine", "hashes");
                data.putParcelable("input", source);
                data.putParcelable("output", pipe[1]);
                remote.get().send(run);
            }
            Message wrong = Message.obtain(null, BinaryWorkerService.CANCEL);
            wrong.getData().putString("token", token + "-wrong");
            remote.get().send(wrong);
            require(
                    !died.await(250, TimeUnit.MILLISECONDS)
                            && remote.get().getBinder().isBinderAlive(),
                    "Wrong job token killed the worker");
            Message cancel = Message.obtain(null, BinaryWorkerService.CANCEL);
            cancel.getData().putString("token", token);
            remote.get().send(cancel);
            require(
                    died.await(10, TimeUnit.SECONDS),
                    "Correct job token did not stop the blocked isolated worker");
            require(
                    android.os.Process.myUid() == target.getApplicationInfo().uid,
                    "Parent app UID/lifetime changed");
            record(
                    "token-scoped-kill-with-blocked-report-pipe",
                    new JSONObject()
                            .put("prefilledPipeBytes", filled)
                            .put("wrongTokenSurvived", true)
                            .put("correctTokenDisconnected", true));
        } finally {
            if (pipe != null) for (ParcelFileDescriptor fd : pipe) if (fd != null) fd.close();
            try {
                target.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
