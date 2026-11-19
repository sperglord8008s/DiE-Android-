// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.file.*;
import java.util.function.BooleanSupplier;

/** Private atomic catalog publication with optimistic revision checking. */
final class ScanProfileStore {
    interface Publisher {
        void publish(Path temporary, Path destination) throws IOException;
    }

    private final File directory, catalog;
    private final Publisher publisher;

    ScanProfileStore(File directory) throws IOException {
        this(
                directory,
                (temporary, destination) ->
                        Files.move(
                                temporary,
                                destination,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING));
    }

    ScanProfileStore(File directory, Publisher publisher) throws IOException {
        this.directory = directory.getCanonicalFile();
        catalog = new File(this.directory, "catalog.json");
        this.publisher = publisher;
    }

    ScanProfiles load(BooleanSupplier stop) throws Exception {
        synchronized (ScanProfileStore.class) {
            return read(stop);
        }
    }

    ScanProfiles save(
            String expected,
            String id,
            String name,
            String description,
            ScanOptions options,
            BooleanSupplier stop)
            throws Exception {
        synchronized (ScanProfileStore.class) {
            ScanProfiles current = current(expected, stop);
            return publish(current.save(id, name, description, options, stop), stop);
        }
    }

    ScanProfiles delete(String expected, String id, BooleanSupplier stop) throws Exception {
        synchronized (ScanProfileStore.class) {
            return publish(current(expected, stop).delete(id, stop), stop);
        }
    }

    ScanProfiles replace(String expected, ScanProfiles imported, BooleanSupplier stop)
            throws Exception {
        synchronized (ScanProfileStore.class) {
            current(expected, stop);
            return publish(imported, stop);
        }
    }

    private ScanProfiles current(String expected, BooleanSupplier stop) throws Exception {
        ScanProfiles current = read(stop);
        if (expected == null || !expected.equals(current.sha256))
            throw new IOException(
                    "Profile catalog changed since this operation was captured; reload before"
                        + " editing");
        return current;
    }

    private ScanProfiles read(BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        if (!directory.exists()) return ScanProfiles.empty();
        if (!directory.isDirectory())
            throw new IOException("Private profile directory is unavailable");
        if (!catalog.exists()) return ScanProfiles.empty();
        if (!catalog.isFile() || catalog.length() > ScanProfiles.MAX_BYTES)
            throw new IOException("Private profile catalog is not a bounded regular file");
        try (InputStream input = new FileInputStream(catalog)) {
            return ScanProfiles.read(input, stop);
        }
    }

    private ScanProfiles publish(ScanProfiles next, BooleanSupplier stop) throws Exception {
        EvidenceRows.check(stop);
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create private profile directory");
        File staged = File.createTempFile("profile-catalog-", ".stage", directory);
        boolean published = false;
        try {
            try (FileOutputStream output = new FileOutputStream(staged)) {
                byte[] bytes = next.bytes();
                for (int offset = 0; offset < bytes.length; offset += 65536) {
                    EvidenceRows.check(stop);
                    output.write(bytes, offset, Math.min(65536, bytes.length - offset));
                }
                output.getFD().sync();
            }
            EvidenceRows.check(stop);
            publisher.publish(staged.toPath(), catalog.toPath());
            published = true;
            return next;
        } finally {
            if (!published) staged.delete();
        }
    }
}
