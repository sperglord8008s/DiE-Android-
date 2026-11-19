// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.File;

/** Actual Binwalk parsers, including retained rejected and overlapping candidates. */
final class BinwalkBackend extends CarvingBackend {
    BinwalkBackend(File root, File directory, String name, BinaryRunner runner) {
        super(
                root,
                directory,
                name,
                runner,
                "Binwalk",
                "org.diemobile.binwalk-evidence",
                "26713972e3f9f52dc37d4a421c4554b0bd9e82ef");
    }
}
