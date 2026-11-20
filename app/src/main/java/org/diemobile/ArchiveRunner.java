// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.File;
import java.util.function.BooleanSupplier;

/** Descriptor transport boundary; filenames are consumed only by the trusted client. */
interface ArchiveRunner extends AutoCloseable {
    String inspect(File source, long offset, long length, BooleanSupplier stop) throws Exception;

    String extract(
            File source, File output, long offset, long length, int member, BooleanSupplier stop)
            throws Exception;

    @Override
    default void close() {}
}
