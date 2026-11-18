// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Validates complete histogram accounting and physical measurement domains. */
final class NativeEntropy {
    private NativeEntropy() {}

    static void validate(JSONArray rows, long fileSize, JSONObject metadata) throws JSONException {
        long regions = integer(metadata.get("regionCount")),
                blocks = integer(metadata.get("sampleCount"));
        if (fileSize > 256L * 1024 * 1024
                || regions < 1
                || regions > 1024
                || blocks > 100
                || integer(metadata.get("histogramBinCount")) != 256
                || rows.length() != 257 + regions + blocks) fail("Entropy counts/limits mismatch");
        if (fileSize == 0 ? blocks != 0 : blocks == 0) fail("Entropy block coverage mismatch");
        JSONObject whole = rows.getJSONObject(0);
        measurement(whole, "whole-file", fileSize);
        if (integer(whole.get("physicalOffset")) != 0 || size(whole) != fileSize)
            fail("Whole-input entropy range mismatch");
        int at = 1;
        for (int i = 0; i < regions; i++, at++)
            measurement(rows.getJSONObject(at), "region", fileSize);
        long end = 0;
        for (int i = 0; i < blocks; i++, at++) {
            JSONObject block = rows.getJSONObject(at);
            measurement(block, "block", fileSize);
            if (integer(block.get("physicalOffset")) != end || size(block) == 0)
                fail("Entropy blocks overlap or omit bytes");
            end += size(block);
        }
        if (end != fileSize) fail("Entropy blocks do not cover the complete input");
        long countSum = 0;
        double expectedEntropy = 0;
        for (int byteValue = 0; byteValue < 256; byteValue++, at++) {
            JSONObject bin = rows.getJSONObject(at);
            if (!bin.getString("rowType").equals("byte-frequency")
                    || integer(bin.get("byteValue")) != byteValue
                    || !bin.has("physicalOffset")
                    || !bin.isNull("physicalOffset")
                    || !bin.has("virtualAddress")
                    || !bin.isNull("virtualAddress")
                    || !bin.has("sizeBytes")
                    || !bin.isNull("sizeBytes")) fail("Histogram bin/domain mismatch");
            long count = decimal(bin.get("count"));
            if (count > fileSize - countSum) fail("Histogram counts exceed the input");
            countSum += count;
            double probability = real(bin, "probability"),
                    expected = fileSize == 0 ? 0 : (double) count / fileSize;
            if (Double.doubleToLongBits(probability) != Double.doubleToLongBits(expected))
                fail("Histogram probability mismatch");
            if (count != 0) expectedEntropy -= expected * (Math.log(expected) / Math.log(2));
        }
        if (countSum != fileSize
                || Math.abs(real(whole, "entropyBitsPerByte") - expectedEntropy) > 1e-12)
            fail("Whole-input entropy/histogram accounting mismatch");
    }

    private static void measurement(JSONObject row, String type, long fileSize)
            throws JSONException {
        if (!type.equals(row.getString("rowType"))
                || !row.has("virtualAddress")
                || !row.isNull("virtualAddress")) fail("Entropy measurement domain mismatch");
        long offset = integer(row.get("physicalOffset")), size = size(row);
        if (offset > fileSize || size > fileSize - offset) fail("Entropy range exceeds the input");
        double value = real(row, "entropyBitsPerByte");
        if (value < 0 || value > 8 + 1e-12) fail("Entropy value outside byte-distribution bounds");
        String bits = row.getString("entropyIeeeBitsHex");
        if (!bits.matches("[0-9A-F]{16}")
                || Long.parseUnsignedLong(bits, 16) != Double.doubleToRawLongBits(value))
            fail("Entropy IEEE representation mismatch");
        if (!row.getString("upstreamDisplayLabel").equals(value >= 6.5 ? "packed" : "not packed"))
            fail("Native entropy display label mismatch");
    }

    private static double real(JSONObject object, String name) throws JSONException {
        Object value = object.get(name);
        if (!(value instanceof Number)) {
            fail("Entropy value must be a JSON number");
            return 0;
        }
        double result = ((Number) value).doubleValue();
        if (!Double.isFinite(result)) fail("Nonfinite entropy value");
        return result;
    }

    private static long size(JSONObject row) throws JSONException {
        return decimal(row.get("sizeBytes"));
    }

    private static long decimal(Object value) throws JSONException {
        if (!(value instanceof String) || !value.toString().matches("(?:0|[1-9][0-9]{0,18})")) {
            fail("Invalid exact entropy count/size");
            return 0;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            fail("Entropy count/size overflow");
            return 0;
        }
    }

    private static long integer(Object value) throws JSONException {
        if (!(value instanceof Number) || !value.toString().matches("(?:0|[1-9][0-9]{0,18})")) {
            fail("Invalid exact entropy integer");
            return 0;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            fail("Entropy integer overflow");
            return 0;
        }
    }

    private static void fail(String message) throws JSONException {
        throw new JSONException(message);
    }
}
