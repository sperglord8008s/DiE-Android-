// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.File;
import java.util.function.BooleanSupplier;

/** Process/IPC boundary kept outside the Android-independent evidence provider. */
interface BinaryRunner extends AutoCloseable {
    String scan(File privateInput,BooleanSupplier cancelled)throws Exception;
    @Override default void close()throws Exception {}
}
