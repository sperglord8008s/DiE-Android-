// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/** Installs the bundled, pinned database atomically; no network access. */
final class EngineDatabase {
    final File main, custom;
    final String engineRevision, signatureRevision, customDigest;

    private EngineDatabase(File main, File custom, String engineRevision, String signatureRevision, String customDigest) {
        this.main = main; this.custom = custom;
        this.engineRevision = engineRevision; this.signatureRevision = signatureRevision; this.customDigest = customDigest;
    }

    static synchronized EngineDatabase prepare(Context context) throws IOException {
        Properties revision = new Properties();
        try (InputStream in = context.getAssets().open("engine-revisions.properties")) { revision.load(in); }
        String engine = revision.getProperty("cdie", ""), signatures = revision.getProperty("signatures", "");
        if (!engine.matches("[0-9a-f]{40}") || !signatures.matches("[0-9a-f]{40}")) {
            throw new IOException("Bundled engine revision metadata is missing or invalid");
        }
        File main = new File(context.getFilesDir(), "cdie-db-" + signatures);
        if (!new File(main, ".ready").isFile()) {
            File staging = new File(context.getFilesDir(), "cdie-db-staging");
            remove(staging); remove(main);
            try {
                copyAssets(context.getAssets(), "db", staging);
                Files.write(new File(staging, ".ready").toPath(), signatures.getBytes(StandardCharsets.US_ASCII));
                if (!staging.renameTo(main)) throw new IOException("Cannot publish bundled database snapshot");
            } catch (IOException e) { remove(staging); throw e; }
        }
        File custom = new File(context.getFilesDir(), "cdie-custom");
        if (!custom.isDirectory() && !custom.mkdirs()) throw new IOException("Cannot create custom database directory");
        CustomRuleStore.Snapshot active = CustomRuleStore.active(new File(context.getFilesDir(), "custom-rule-snapshots"));
        return new EngineDatabase(main, active == null ? custom : active.database, engine, signatures,
            active == null ? "" : active.digest);
    }

    private static void copyAssets(AssetManager assets, String path, File destination) throws IOException {
        String[] children = assets.list(path);
        if (children != null && children.length > 0) {
            Files.createDirectories(destination.toPath());
            for (String child : children) copyAssets(assets, path + "/" + child, new File(destination, child));
        } else {
            // Asset implementations can return nested relative leaf names.
            // Create their complete parent path before opening the output.
            File parent=destination.getParentFile();
            if(parent!=null)Files.createDirectories(parent.toPath());
            try (InputStream in = assets.open(path); OutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
        }
    }

    private static void remove(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (!file.delete()) throw new IOException("Cannot remove incomplete database: " + file.getName());
    }
}
