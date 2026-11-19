// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.app.Activity;
import android.content.Intent;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Result-to-preview boundary shared by the gallery and an already derived preview. */
final class ArchivePreviewLauncher {
    static void launch(
            Activity owner,
            Executor worker,
            Intent result,
            String parentSha,
            long parentSize,
            BooleanSupplier stop,
            BooleanSupplier current,
            Runnable settled,
            Consumer<String> status) {
        worker.execute(
                () -> {
                    InputSnapshot staged = null;
                    try {
                        String path = result.getStringExtra("path");
                        if (path == null)
                            throw new java.io.IOException(
                                    "Archive browser returned no member path");
                        String display = result.getStringExtra("display");
                        if (display == null) display = "Archive member";
                        staged =
                                ArchiveMemberHandoff.acquire(
                                        new File(path),
                                        result.getStringExtra("memberSha256"),
                                        new File(owner.getCacheDir(), "archive-snapshots"),
                                        new File(owner.getCacheDir(), "content-previews"),
                                        stop);
                        InputSnapshot input = staged;
                        staged = null;
                        String name = display;
                        owner.runOnUiThread(
                                () -> {
                                    if (!current.getAsBoolean() || stop.getAsBoolean()) {
                                        input.close();
                                        return;
                                    }
                                    settled.run();
                                    try {
                                        owner.startActivity(
                                                new Intent(owner, ContentPreviewActivity.class)
                                                        .putExtra(
                                                                "path",
                                                                input.file.getAbsolutePath())
                                                        .putExtra("sha256", input.sha256)
                                                        .putExtra("sourceSize", input.size)
                                                        .putExtra("name", name)
                                                        .putExtra("originalSha256", parentSha)
                                                        .putExtra("originalSize", parentSize)
                                                        .putExtra(
                                                                "originalKind", "archive-member"));
                                        status.accept(
                                                "Acquired exact archive member · "
                                                        + input.size
                                                        + " bytes · SHA-256 "
                                                        + input.sha256);
                                    } catch (RuntimeException error) {
                                        input.close();
                                        status.accept("Archive preview failed: " + error);
                                    }
                                });
                    } catch (Exception error) {
                        if (staged != null) staged.close();
                        owner.runOnUiThread(
                                () -> {
                                    if (current.getAsBoolean()) {
                                        settled.run();
                                        status.accept("Archive member handoff failed: " + error);
                                    }
                                });
                    }
                });
    }

    private ArchivePreviewLauncher() {}
}
