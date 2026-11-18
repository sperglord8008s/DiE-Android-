// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.JSONObject;

/** A separate JVM with an empty native-library path verifies the fallback contract. */
public final class NativeUnavailableTest {
    public static void main(String[] args) throws Exception {
        if (Engine.AVAILABLE) throw new AssertionError("Unavailable-engine fixture unexpectedly loaded native code");
        NativeTable records = NativeTable.parse(Engine.records("unused", 1, 0, -1, false));
        if (records.ok || records.error.isEmpty()) throw new AssertionError("Unavailable engine returned successful records");
        if (NativeTable.parse(Engine.structures("unused", "", 0, -1, () -> false)).ok) throw new AssertionError("Unavailable structures succeeded");
        if (NativeTable.parse(Engine.entropy("unused", () -> false)).ok) throw new AssertionError("Unavailable entropy succeeded");
        if (!Engine.tool("unused", 0, -1, 0, 4).startsWith("ERROR:")) throw new AssertionError("Unavailable tool looked successful");
        if (new JSONObject(Engine.desktop("unused", "", "", 1, 16, 0, "", "", "", "")).getBoolean("ok")) throw new AssertionError("Unavailable scanner looked successful");
        System.out.println("Unavailable JNI fallback returns explicit errors for scan, records and tools.");
    }
}
