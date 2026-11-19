// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;

/** Structure/field coordinate contracts and explicit semantic-value references. */
final class NativeStructures {
    private static final BigInteger U64 = BigInteger.ONE.shiftLeft(64);
    private static final long KNOWN_PROPERTIES = 0xfff;

    static final class Reference {
        final BigInteger value;
        final boolean fileOffset, virtualAddress;
        final String basis;

        Reference(BigInteger value, boolean fileOffset, boolean virtualAddress, String basis) {
            this.value = value;
            this.fileOffset = fileOffset;
            this.virtualAddress = virtualAddress;
            this.basis = basis;
        }
    }

    private NativeStructures() {}

    static void validate(JSONArray rows, long fileSize, JSONObject metadata) throws JSONException {
        int currentParent = -1, structures = 0, fields = 0;
        JSONObject parent = null;
        if (!(metadata.get("inventoryOnly") instanceof Boolean)) fail("Invalid inventory flag");
        boolean inventory = metadata.getBoolean("inventoryOnly");
        long slice = signedNumber(metadata.get("requestedMachOSliceIndex"));
        if (slice < -1 || slice >= 4096) fail("Invalid slice index");
        metadata.getString("selector");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String kind = row.getString("rowType");
            long nativeSize;
            Long expectedOffset = null;
            if (kind.equals("structure")) {
                structures++;
                if (!row.has("parentStructureIndex") || !row.isNull("parentStructureIndex"))
                    fail("Structure has a parent");
                parent = row;
                currentParent = i;
                long offset = signedString(row, "nativeOffset");
                nativeSize = signedString(row, "nativeTotalSize");
                signedString(row, "nativeEntrySize");
                long address = signedString(row, "nativeAddress");
                checkAddress(
                        row, address == -1 ? null : new BigInteger(Long.toUnsignedString(address)));
                unsignedString(row.get("count"), 64);
                unsignedNumber(row.get("nativeStructureId"), 32);
                unsignedNumber(row.get("nativeStructureType"), 31);
                if (fits(fileSize, offset, nativeSize)) expectedOffset = offset;
            } else if (kind.equals("field")) {
                fields++;
                if (inventory
                        || parent == null
                        || unsignedNumber(row.get("parentStructureIndex"), 31) != currentParent)
                    fail("Field parent/order mismatch");
                long relative = signedString(row, "relativeOffset");
                nativeSize = signedString(row, "nativeSize");
                long parentOffset = signedString(parent, "nativeOffset");
                long parentSize = signedString(parent, "nativeTotalSize");
                boolean within =
                        fits(fileSize, parentOffset, parentSize)
                                && fits(parentSize, relative, nativeSize);
                if (within) expectedOffset = parentOffset + relative;
                long address = signedString(parent, "nativeAddress");
                BigInteger va =
                        address == -1 || !within
                                ? null
                                : new BigInteger(Long.toUnsignedString(address))
                                        .add(BigInteger.valueOf(relative));
                checkAddress(
                        row,
                        va == null || va.compareTo(U64.subtract(BigInteger.ONE)) >= 0 ? null : va);
                long properties = unsignedNumber(row.get("properties"), 32);
                if (unsignedNumber(row.get("unknownPropertyBits"), 32)
                        != (properties & ~KNOWN_PROPERTIES)) fail("Property mask mismatch");
                JSONArray names = row.getJSONArray("propertyNames");
                String[] expectedNames = {
                    "identifier",
                    "virtual-address",
                    "file-offset",
                    "size",
                    "count",
                    "pointer",
                    "flags",
                    "timestamp",
                    "string",
                    "reserved",
                    "signature",
                    "checksum"
                };
                int position = 0;
                for (int bit = 0; bit < expectedNames.length; bit++)
                    if ((properties & (1L << bit)) != 0) {
                        if (position >= names.length()
                                || !expectedNames[bit].equals(names.getString(position++)))
                            fail("Property name mismatch");
                    }
                if (position != names.length()) fail("Unexpected property name");
                validateValue(row.getJSONObject("nativeValue"));
                JSONObject texts = row.getJSONObject("nativeTextUnits");
                for (String key :
                        new String[] {
                            "nativeName", "nativeType", "rawValue", "displayValue", "nativeComment"
                        }) {
                    if (!row.has(key) || !texts.has(key) || row.isNull(key) != texts.isNull(key))
                        fail("Missing native text representation");
                    if (!row.isNull(key)) {
                        String units = texts.getString(key);
                        if (units.length() > 8 * 512 * 1024 || !hex(units, 8))
                            fail("Invalid native wchar units");
                        StringBuilder decoded = new StringBuilder();
                        for (int at = 0; at < units.length(); at += 8) {
                            long code = Long.parseLong(units.substring(at, at + 8), 16);
                            if (code <= 0xffff) decoded.append((char) code);
                            else if (code <= 0x10ffff) decoded.appendCodePoint((int) code);
                            else decoded.append('\ufffd');
                        }
                        if (!row.getString(key).contentEquals(decoded))
                            fail("Native text/unit mismatch");
                    }
                }
            } else {
                fail("Unknown structure row type");
                return;
            }
            Object size = row.get("sizeBytes");
            if (nativeSize < 0 ? size != JSONObject.NULL : !Long.toString(nativeSize).equals(size))
                fail("Native/usable size mismatch");
            if (!(row.get("physicalRangeValid") instanceof Boolean)
                    || row.getBoolean("physicalRangeValid") != (expectedOffset != null))
                fail("Physical validity mismatch");
            Object actualOffset = row.get("physicalOffset");
            if (expectedOffset == null
                    ? actualOffset != JSONObject.NULL
                    : signedNumber(actualOffset) != expectedOffset)
                fail("Derived physical offset mismatch");
        }
        if (unsignedNumber(metadata.get("structureCount"), 31) != structures
                || unsignedNumber(metadata.get("fieldCount"), 31) != fields)
            fail("Structure/field count mismatch");
    }

    /**
     * Reviewed field-specific relationships; semantic flags alone cannot distinguish PE RVAs,
     * absolute VAs, resource-relative offsets, or fat slice coordinates.
     */
    static Reference reference(NativeTable table, NativeTable.Row record) throws JSONException {
        JSONObject row = record.toJson();
        if (!"field".equals(row.optString("rowType"))) return null;
        int parentIndex = row.getInt("parentStructureIndex");
        String structure = table.rows.get(parentIndex).name;
        String name = row.getString("name");
        long properties = unsignedNumber(row.get("properties"), 32);
        if ((properties & 6) == 0) return null;
        BigInteger declared = integerValue(row);
        if (declared == null || declared.signum() < 0 || declared.compareTo(U64) >= 0) return null;
        if (structure.equals("IMAGE_DOS_HEADER") && name.equals("e_lfanew")
                || structure.equals("IMAGE_FILE_HEADER") && name.equals("PointerToSymbolTable")
                || structure.equals("IMAGE_SECTION_HEADER")
                        && (name.equals("PointerToRawData")
                                || name.equals("PointerToRelocations")
                                || name.equals("PointerToLinenumbers"))
                || structure.equals("IMAGE_DEBUG_DIRECTORY") && name.equals("PointerToRawData")
                || structure.equals("fat_arch") && name.equals("offset")
                || structure.equals("fat_arch_64") && name.equals("offset")
                || table.fileType.startsWith("ELF")
                        && (name.equals("e_phoff") || name.equals("e_shoff")))
            return declared.signum() == 0
                    ? null
                    : new Reference(
                            declared, true, false, "format-defined whole-input file offset");
        if ((structure.equals("IMAGE_OPTIONAL_HEADER32")
                                || structure.equals("IMAGE_OPTIONAL_HEADER64"))
                        && name.equals("ImageBase")
                || (structure.equals("segment_command") || structure.equals("segment_command_64"))
                        && name.equals("vmaddr")
                || (structure.equals("section") || structure.equals("section_64"))
                        && name.equals("addr")
                || table.fileType.startsWith("ELF") && name.equals("e_entry"))
            return new Reference(declared, false, true, "format-defined preferred virtual address");
        boolean peRva =
                (structure.equals("IMAGE_OPTIONAL_HEADER32")
                                        || structure.equals("IMAGE_OPTIONAL_HEADER64"))
                                && name.equals("AddressOfEntryPoint")
                        || structure.equals("IMAGE_SECTION_HEADER")
                                && name.equals("VirtualAddress");
        if (peRva) {
            for (NativeTable.Row candidate : table.rows)
                if (candidate.name.equals("ImageBase")
                        && candidate.text("rowType").equals("field")) {
                    String owner =
                            table.rows.get(candidate.toJson().getInt("parentStructureIndex")).name;
                    if (!owner.equals("IMAGE_OPTIONAL_HEADER32")
                            && !owner.equals("IMAGE_OPTIONAL_HEADER64")) continue;
                    BigInteger base = integerValue(candidate.toJson());
                    if (base != null && base.signum() >= 0 && base.add(declared).compareTo(U64) < 0)
                        return new Reference(
                                base.add(declared),
                                false,
                                true,
                                "PE RVA + captured ImageBase 0x" + base.toString(16));
                }
            return null;
        }
        boolean machoSliceOffset =
                (structure.equals("segment_command") || structure.equals("segment_command_64"))
                                && name.equals("fileoff")
                        || (structure.equals("section") || structure.equals("section_64"))
                                && (name.equals("offset") || name.equals("reloff"));
        if (machoSliceOffset) {
            if (!name.equals("fileoff") && declared.signum() == 0) return null;
            if (name.equals("offset")) {
                BigInteger flags = null;
                for (NativeTable.Row candidate : table.rows)
                    if (candidate.name.equals("flags")
                            && candidate.text("rowType").equals("field")
                            && candidate.toJson().getInt("parentStructureIndex") == parentIndex)
                        flags = integerValue(candidate.toJson());
                if (flags == null) return null;
                int sectionType = flags.intValue() & 0xff;
                if (sectionType == 1 || sectionType == 0xc || sectionType == 0x12) return null;
            }

            Long base = table.fileType.equals("MACHOFAT") ? null : 0L;
            for (NativeTable.Row candidate : table.rows)
                if (candidate.text("rowType").equals("structure")
                        && (candidate.name.equals("mach_header")
                                || candidate.name.equals("mach_header_64"))) {
                    base = candidate.physicalOffset;
                    break;
                }
            if (base != null && declared.add(BigInteger.valueOf(base)).compareTo(U64) < 0)
                return new Reference(
                        declared.add(BigInteger.valueOf(base)),
                        true,
                        false,
                        "Mach-O slice-relative value + captured header offset 0x"
                                + Long.toHexString(base));
        }
        return null;
    }

    private static BigInteger integerValue(JSONObject row) throws JSONException {
        JSONObject variant = row.getJSONObject("nativeValue");
        long type = unsignedNumber(variant.get("typeId"), 31);
        if (type >= 1 && type <= 8) return new BigInteger(variant.getString("value"));
        // CDiE's fat-header visitor supplies raw/display strings but no xx_var.
        if (type == 0
                && row.optString("nativeType").matches("uint(?:8|16|32|64)")
                && row.optString("rawValue").matches("0x[0-9A-Fa-f]{1,16}"))
            return new BigInteger(row.getString("rawValue").substring(2), 16);
        return null;
    }

    private static void validateValue(JSONObject value) throws JSONException {
        String[] types = {
            "none",
            "int8",
            "int16",
            "int32",
            "int64",
            "uint8",
            "uint16",
            "uint32",
            "uint64",
            "float",
            "double",
            "bool",
            "string",
            "wstring",
            "bytes",
            "string-view",
            "wstring-view",
            "bytes-view",
            "pointer"
        };
        int type = (int) unsignedNumber(value.get("typeId"), 31);
        if (type >= types.length
                || !types[type].equals(value.getString("type"))
                || !value.has("value")) fail("Unknown native variant");
        if (type >= 1 && type <= 8) {
            String number = value.getString("value");
            if (!number.matches("-?(?:0|[1-9][0-9]{0,19})")) fail("Invalid native integer");
            BigInteger parsed = new BigInteger(number);
            int bits = new int[] {8, 16, 32, 64}[(type - 1) % 4];
            BigInteger limit = BigInteger.ONE.shiftLeft(type <= 4 ? bits - 1 : bits);
            if (parsed.compareTo(limit) >= 0
                    || parsed.compareTo(type <= 4 ? limit.negate() : BigInteger.ZERO) < 0)
                fail("Native integer exceeds type");
        } else if (type == 9 || type == 10) {
            value.getString("value");
            if (!value.getString("ieeeBitsHex").matches(type == 9 ? "[0-9A-F]{8}" : "[0-9A-F]{16}"))
                fail("Missing exact IEEE representation");
        } else if (type == 11) {
            if (!(value.get("value") instanceof Boolean)) fail("Invalid native bool");
        } else if (type == 12 || type == 14 || type == 15 || type == 17) {
            long size = unsignedNumber(value.get("lengthBytes"), 31);
            String bytes = value.getString("bytesHex");
            if (size > 512 * 1024
                    || bytes.length() != size * 2
                    || !hex(bytes, 2)
                    || !value.isNull("value")) fail("Invalid native byte/string value");
        } else if (type == 13 || type == 16) {
            long size = unsignedNumber(value.get("lengthNativeUnits"), 31);
            String units = value.getString("wcharUnitsHex");
            if (size > 512 * 1024 || units.length() != size * 8 || !hex(units, 8))
                fail("Invalid native wide value");
            value.getString("value");
        } else if (type == 18) {
            if (!(value.get("opaquePointerPresent") instanceof Boolean) || !value.isNull("value"))
                fail("Invalid opaque pointer");
            value.getString("limitation");
        } else if (!value.isNull("value")) fail("Unexpected absent native value");
    }

    private static boolean fits(long whole, long offset, long size) {
        return whole >= 0 && offset >= 0 && size >= 0 && offset <= whole && size <= whole - offset;
    }

    private static void checkAddress(JSONObject row, BigInteger expected) throws JSONException {
        Object actual = row.get("virtualAddress");
        if (expected == null) {
            if (actual != JSONObject.NULL) fail("Unexpected virtual address");
        } else if (!(actual instanceof String)
                || !((String) actual).matches("0x[0-9A-F]{1,16}")
                || !expected.equals(new BigInteger(((String) actual).substring(2), 16)))
            fail("Derived virtual address mismatch");
    }

    private static boolean hex(String value, int unitWidth) {
        if (value.length() % unitWidth != 0) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'A' && c <= 'F')) return false;
        }
        return true;
    }

    private static long signedString(JSONObject row, String key) throws JSONException {
        Object value = row.get(key);
        if (!(value instanceof String) || !((String) value).matches("-?(?:0|[1-9][0-9]{0,18})")) {
            fail("Invalid signed native integer");
            return 0;
        }
        try {
            return Long.parseLong((String) value);
        } catch (NumberFormatException e) {
            fail("Native integer overflow");
            return 0;
        }
    }

    private static long signedNumber(Object value) throws JSONException {
        if (!(value instanceof Number) || !value.toString().matches("-?(?:0|[1-9][0-9]{0,18})")) {
            fail("Invalid exact integer");
            return 0;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            fail("Integer overflow");
            return 0;
        }
    }

    private static long unsignedNumber(Object value, int bits) throws JSONException {
        long parsed = signedNumber(value);
        if (parsed < 0 || (bits < 63 && parsed >= (1L << bits))) fail("Invalid unsigned integer");
        return parsed;
    }

    private static BigInteger unsignedString(Object value, int bits) throws JSONException {
        if (!(value instanceof String) || !((String) value).matches("(?:0|[1-9][0-9]{0,19})")) {
            fail("Invalid unsigned native integer");
            return null;
        }
        BigInteger result = new BigInteger((String) value);
        if (result.bitLength() > bits) fail("Native unsigned overflow");
        return result;
    }

    private static void fail(String message) throws JSONException {
        throw new JSONException(message);
    }
}
