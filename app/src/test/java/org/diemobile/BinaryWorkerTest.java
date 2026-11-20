// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.*;
import android.os.*;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Manifest and refusal boundary only; this does not simulate an isolated OS UID. */
@RunWith(WorkbenchTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BinaryWorkerTest {
    @Test
    public void workerIsPrivateAndRequestsASeparateIsolatedProcess() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ServiceInfo info =
                context.getPackageManager()
                        .getServiceInfo(new ComponentName(context, BinaryWorkerService.class), 0);
        assertFalse(info.exported);
        assertTrue((info.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0);
        assertTrue(info.processName.endsWith(":binary_worker"));
    }

    @Test
    public void ordinaryUidReceivesAnErrorWithoutNativeFallback() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ApplicationInfo info = context.getApplicationInfo();
        int previous = info.uid;
        info.uid = android.os.Process.myUid();
        Path directory = Files.createTempDirectory("die-worker-guard"),
                input = directory.resolve("input.bin"),
                output = directory.resolve("report.json");
        Files.write(input, new byte[] {1, 2, 3});
        ServiceController<BinaryWorkerService> controller =
                Robolectric.buildService(BinaryWorkerService.class).create();
        try {
            for (String engine :
                    new String[] {"binwalk", "photorec", "mpv", "resources", "hashes", "archive"}) {
                Message request = Message.obtain(null, BinaryWorkerService.RUN);
                request.getData().putString("token", "guard-fixture");
                request.getData()
                        .putParcelable(
                                "input",
                                ParcelFileDescriptor.open(
                                        input.toFile(), ParcelFileDescriptor.MODE_READ_ONLY));
                request.getData().putString("engine", engine);
                request.getData()
                        .putParcelable(
                                "output",
                                ParcelFileDescriptor.open(
                                        output.toFile(),
                                        ParcelFileDescriptor.MODE_CREATE
                                                | ParcelFileDescriptor.MODE_WRITE_ONLY
                                                | ParcelFileDescriptor.MODE_TRUNCATE));
                new Messenger(controller.get().onBind(new Intent())).send(request);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                JSONObject report =
                        new JSONObject(
                                new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
                assertFalse(report.getBoolean("isolatedUid"));
                assertFalse(report.getJSONObject("report").getBoolean("ok"));
                assertTrue(
                        report.getJSONObject("report").getString("error").contains("isolated UID"));
                assertEquals(engine, report.getString("engine"));
            }
        } finally {
            controller.destroy();
            info.uid = previous;
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void malformedArchiveRequestClosesBothOutputDescriptors() throws Exception {
        Path directory = Files.createTempDirectory("archive-malformed-worker");
        ServiceController<BinaryWorkerService> controller =
                Robolectric.buildService(BinaryWorkerService.class).create();
        ParcelFileDescriptor report =
                ParcelFileDescriptor.open(
                        directory.resolve("report").toFile(),
                        ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_WRITE_ONLY);
        ParcelFileDescriptor payload =
                ParcelFileDescriptor.open(
                        directory.resolve("payload").toFile(),
                        ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_WRITE_ONLY);
        try {
            Message request = Message.obtain(null, BinaryWorkerService.RUN);
            request.getData().putString("engine", "archive");
            request.getData().putString("token", "malformed-archive");
            request.getData().putParcelable("output", report);
            request.getData().putParcelable("payload", payload);
            new Messenger(controller.get().onBind(new Intent())).send(request);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertThrows(IllegalStateException.class, report::getFd);
            assertThrows(IllegalStateException.class, payload::getFd);
        } finally {
            report.close();
            payload.close();
            controller.destroy();
            Files.deleteIfExists(directory.resolve("report"));
            Files.deleteIfExists(directory.resolve("payload"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void ordinaryUidCannotWriteArchivePayloadAndReleasesTheDescriptor() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ApplicationInfo info = context.getApplicationInfo();
        int previous = info.uid;
        info.uid = android.os.Process.myUid();
        Path directory = Files.createTempDirectory("archive-worker-payload-guard");
        Path source = directory.resolve("input"),
                target = directory.resolve("payload"),
                report = directory.resolve("report");
        Files.write(source, new byte[] {1, 2, 3});
        ServiceController<BinaryWorkerService> controller =
                Robolectric.buildService(BinaryWorkerService.class).create();
        ParcelFileDescriptor payload =
                ParcelFileDescriptor.open(
                        target.toFile(),
                        ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_WRITE_ONLY);
        try {
            Message request = Message.obtain(null, BinaryWorkerService.RUN);
            Bundle data = request.getData();
            data.putString("engine", "archive");
            data.putString("token", "ordinary-archive");
            data.putInt("member", 0);
            data.putLong("offset", 0);
            data.putLong("length", 3);
            data.putParcelable(
                    "input",
                    ParcelFileDescriptor.open(
                            source.toFile(), ParcelFileDescriptor.MODE_READ_ONLY));
            data.putParcelable("payload", payload);
            data.putParcelable(
                    "output",
                    ParcelFileDescriptor.open(
                            report.toFile(),
                            ParcelFileDescriptor.MODE_CREATE
                                    | ParcelFileDescriptor.MODE_WRITE_ONLY));
            new Messenger(controller.get().onBind(new Intent())).send(request);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            JSONObject response =
                    new JSONObject(new String(Files.readAllBytes(report), StandardCharsets.UTF_8));
            assertFalse(response.getBoolean("isolatedUid"));
            assertEquals("archive", response.getString("engine"));
            assertTrue(
                    response.getJSONObject("report").getString("error").contains("isolated UID"));
            assertEquals(0, Files.size(target));
            assertThrows(IllegalStateException.class, payload::getFd);
        } finally {
            payload.close();
            controller.destroy();
            info.uid = previous;
            Files.deleteIfExists(source);
            Files.deleteIfExists(target);
            Files.deleteIfExists(report);
            Files.deleteIfExists(directory);
        }
    }
}
