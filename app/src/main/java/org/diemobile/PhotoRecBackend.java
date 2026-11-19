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
