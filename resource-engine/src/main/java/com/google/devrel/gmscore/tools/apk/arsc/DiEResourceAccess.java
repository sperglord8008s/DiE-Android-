// SPDX-License-Identifier: GPL-3.0-only
package com.google.devrel.gmscore.tools.apk.arsc;

import java.nio.ByteBuffer;

/** App-owned read-only access to the pinned parser's protected wire identity.
 * No inspected class is loaded, and none of the parser's editing APIs are used. */
public final class DiEResourceAccess {
    public static int offset(Chunk chunk) { return chunk.offset; }
    public static int type(Chunk chunk) { return chunk.getType().code()&0xffff; }
    public static int styleSpanCount(StringPoolChunk pool,int style) { return pool.getStyle(style).spans().size(); }
    public static ResourceConfiguration configuration(ByteBuffer source) { return ResourceConfiguration.create(source); }
    private DiEResourceAccess() {}
}
