// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;

/** Keep the production JNI entry-point names intact inside the Android sandbox. */
public final class WorkbenchTestRunner extends RobolectricTestRunner {
    public WorkbenchTestRunner(Class<?> test) throws InitializationError { super(test); }
    @Override protected InstrumentationConfiguration createClassLoaderConfig(FrameworkMethod method) {
        return new InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotInstrumentClass("org.diemobile.Engine")
            .doNotInstrumentClass("org.diemobile.BinwalkEngine")
            .doNotInstrumentClass("org.diemobile.MpvEngine").build();
    }
}
