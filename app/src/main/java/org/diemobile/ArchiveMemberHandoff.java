// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.util.function.BooleanSupplier;

/**
 * Acquires ownership of an exact result from our archive browser, then releases its temporary leaf.
 */
final class ArchiveMemberHandoff {
    static InputSnapshot acquire(
            File member,
            String expectedSha,
            File archiveDirectory,
            File previewDirectory,
            BooleanSupplier stop)
            throws Exception {
        File file = member.getCanonicalFile();
        if (!file.getParentFile().equals(archiveDirectory.getCanonicalFile())
                || !file.equals(member.getAbsoluteFile())
                || !file.getName().matches("archive-member-[A-Za-z0-9._-]+"))
            throw new IOException("Archive result is outside the browser's owned member directory");
        InputSnapshot acquired = null;
        try {
            if (expectedSha == null || !expectedSha.matches("[0-9a-f]{64}"))
                throw new IOException("Archive result has no exact member SHA-256");
            acquired = InputSnapshot.acquire(file, previewDirectory, ArchiveStore.MAX_MEMBER, stop);
            if (!acquired.sha256.equals(expectedSha))
                throw new IOException("Archive result differs from its captured member SHA-256");
            InputSnapshot result = acquired;
            acquired = null;
            return result;
        } finally {
            if (acquired != null) acquired.close();
            file.delete(); // Only a validated owned leaf can reach this point.
        }
    }

    private ArchiveMemberHandoff() {}
}
