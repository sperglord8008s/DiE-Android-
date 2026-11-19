// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Valid media, overlapping payloads, relative magic and unresolvable boundaries. */
public final class CarvingFixture {
    public final byte[] bytes, jpeg, png, wav, gif;
    public final int jpegAt, nestedPngAt, pngAt, wavAt, gifAt, dcmAt, brokenPngAt, truncatedJpegAt;

    public CarvingFixture() throws Exception {
        jpeg = MetadataFixtureFactory.jpeg();
        png = MetadataFixtureFactory.png();
        wav = MetadataFixtureFactory.wav();
        gif =
                Base64.getDecoder()
                        .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
        byte[] icc = Arrays.copyOf(MetadataFixtureFactory.iccValue(), 64);
        int nested = find(jpeg, icc) + 1024;
        System.arraycopy(png, 0, jpeg, nested, png.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Observed ext superblock magic implies a negative source start here.
        out.write(new byte[] {0x53, (byte) 0xef});
        out.write(new byte[35]);
        jpegAt = out.size();
        out.write(jpeg);
        nestedPngAt = jpegAt + nested;
        out.write(new byte[19]);
        pngAt = out.size();
        out.write(png);
        out.write(new byte[11]);
        wavAt = out.size();
        out.write(wav);
        out.write(new byte[13]);
        gifAt = out.size();
        out.write(gif);
        out.write(new byte[17]);
        dcmAt = out.size();
        out.write(new byte[128]);
        out.write("DICM".getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[] {2, 0, 0, 0, 'U', 'L', 4, 0, 0, 0, 0, 0});
        out.write(new byte[20]);
        out.write(new byte[7]);
        brokenPngAt = out.size();
        byte[] broken = png.clone();
        Arrays.fill(broken, 8, 12, (byte) 0x7f);
        out.write(broken);
        out.write(new byte[23]);
        truncatedJpegAt = out.size();
        out.write(jpeg, 0, jpeg.length - 2);
        bytes = out.toByteArray();
    }

    private static int find(byte[] source, byte[] pattern) {
        outer:
        for (int i = 0; i <= source.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++)
                if (source[i + j] != pattern[j]) continue outer;
            return i;
        }
        throw new AssertionError("Fixture ICC bytes unavailable");
    }
}
