// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.IOException;

/** A checked half-open physical file interval; never a virtual address interval. */
final class FileRange {
    static final long MAX_OPERATION_BYTES = 256L * 1024 * 1024;
    final long offset, length, fileSize;

    FileRange(long fileSize, long offset, long length) {
        if (fileSize < 0 || offset < 0 || length < 0 || offset > fileSize || length > fileSize - offset) {
            throw new IllegalArgumentException("Physical range is outside the input");
        }
        this.fileSize = fileSize; this.offset = offset; this.length = length;
    }

    void requireCurrentLength(long currentLength) throws IOException {
        if (currentLength != fileSize) throw new IOException("Input length changed; reload analysis before using this range");
    }

    void requireOperationBudget() throws IOException {
        if (length > MAX_OPERATION_BYTES) throw new IOException("Selected range exceeds the 256 MiB operation limit");
    }
}
