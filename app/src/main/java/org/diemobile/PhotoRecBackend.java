// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.File;

/** Whole-blob PhotoRec header/data evidence and bounded stored-byte carving. */
final class PhotoRecBackend extends CarvingBackend {
    static final long MAX_BYTES = PhotoRecReport.MAX_INPUT_BYTES;

    @Override
    long maximumInputBytes() {
        return MAX_BYTES;
    }

    /** Caller retains ownership; native results are checked against these exact private bytes. */
    PhotoRecBackend(InputSnapshot input, String name, BinaryRunner runner) {
        super(
                input.file,
                input.file.getParentFile(),
                name,
                runner,
                "PhotoRec",
                "org.diemobile.photorec-evidence",
                "281be432dd79121e08e0898887a9ee1f30fb3e96",
                input);
    }

    PhotoRecBackend(File root, File directory, String name, BinaryRunner runner) {
        super(
                root,
                directory,
                name,
                runner,
                "PhotoRec",
                "org.diemobile.photorec-evidence",
                "281be432dd79121e08e0898887a9ee1f30fb3e96");
    }
}
