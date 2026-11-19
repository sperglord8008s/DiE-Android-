// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.util.function.BooleanSupplier;

/** Exodus-specific inventory contract over the shared lossless catalog reader. */
final class TrackerJson extends CatalogJson {
    TrackerJson(String text, BooleanSupplier stop) throws Exception {
        super(text, stop, "trackers", 10000, 256, "Tracker");
    }
}
