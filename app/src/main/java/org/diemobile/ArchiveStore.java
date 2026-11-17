// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Read-only ZIP acquisition. Member names are evidence, never output paths. */
final class ArchiveStore {
    static final int MAX_ENTRIES = 20000;
    static final long MAX_ARCHIVE = 1024L * 1024 * 1024;
    static final long MAX_MEMBER = 256L * 1024 * 1024;
    static final long MAX_NAME_CHARACTERS = 8L * 1024 * 1024;

    static final class Member {
        final int index, method, occurrences;
        final String name;
        final long size, compressedSize, crc32;
        final boolean directory;

        private Member(int index, ZipEntry entry, int occurrences) {
            this.index = index; this.name = entry.getName(); this.size = entry.getSize();
            this.compressedSize = entry.getCompressedSize(); this.crc32 = entry.getCrc();
            this.method = entry.getMethod(); this.directory = entry.isDirectory(); this.occurrences = occurrences;
        }
        private boolean matches(ZipEntry entry) {
            return name.equals(entry.getName()) && size == entry.getSize() && compressedSize == entry.getCompressedSize()
                && crc32 == entry.getCrc() && method == entry.getMethod() && directory == entry.isDirectory();
        }
    }

    static final class Inventory {
        final File source;
        final long sourceSize;
        final List<Member> members;
        private Inventory(File source, long sourceSize, List<Member> members) {
            this.source = source; this.sourceSize = sourceSize;
            this.members = Collections.unmodifiableList(members);
        }
    }

    static final class Extracted {
        final File file;
        final long size;
        final String sha256;
        private Extracted(File file, long size, String sha256) {
            this.file = file; this.size = size; this.sha256 = sha256;
        }
    }

    private ArchiveStore() {}

    static Inventory read(File source, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        long size = source.length();
        if (!source.isFile() || size > MAX_ARCHIVE) throw new IOException("Archive is unavailable or exceeds the 1 GiB input budget");
        List<ZipEntry> entries = new ArrayList<>(); Map<String, Integer> names = new HashMap<>(); long characters = 0;
        try (ZipFile zip = new ZipFile(source)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                checkCancelled(cancelled);
                if (entries.size() == MAX_ENTRIES) throw new IOException("Archive inventory exceeds 20,000 entries; no partial inventory returned");
                ZipEntry entry = all.nextElement(); characters += entry.getName().length();
                if (characters > MAX_NAME_CHARACTERS) throw new IOException("Archive names exceed the 8 Mi-character inventory budget");
                entries.add(entry); names.merge(entry.getName(), 1, Integer::sum);
            }
        }
        requireLength(source, size); checkCancelled(cancelled);
        List<Member> members = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) members.add(new Member(i, entries.get(i), names.get(entries.get(i).getName())));
        return new Inventory(source, size, members);
    }

    static Extracted extract(Inventory inventory, Member member, File directory, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled); requireLength(inventory.source, inventory.sourceSize);
        if (member.index < 0 || member.index >= inventory.members.size() || inventory.members.get(member.index) != member) {
            throw new IOException("Member does not belong to this inventory");
        }
        if (member.occurrences != 1) throw new IOException("Duplicate member name; extraction would be ambiguous");
        if (member.directory) throw new IOException("A directory has no selectable payload");
        if (member.size < 0 || member.size > MAX_MEMBER || member.crc32 < 0 || member.compressedSize < 0) {
            throw new IOException("Member has unavailable metadata or exceeds the 256 MiB extraction budget");
        }
        if (member.method != ZipEntry.STORED && member.method != ZipEntry.DEFLATED) {
            throw new IOException("Unsupported ZIP compression method: " + member.method);
        }
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private archive snapshot directory");
        File output = null; boolean success = false;
        try (ZipFile zip = new ZipFile(inventory.source)) {
            // Revalidate the entire bounded directory, including uniqueness, on
            // the same ZipFile handle used for extraction. Never use a stale name lookup.
            Enumeration<? extends ZipEntry> all = zip.entries(); ZipEntry selected = null; int index = 0;
            while (all.hasMoreElements()) {
                checkCancelled(cancelled); ZipEntry entry = all.nextElement();
                if (index >= inventory.members.size() || !inventory.members.get(index).matches(entry)) throw new IOException("Archive directory changed; reload it before selecting a member");
                if (index == member.index) selected = entry;
                index++;
            }
            if (index != inventory.members.size() || selected == null) throw new IOException("Archive directory changed; reload it before selecting a member");
            String suffix = ".bin"; int dot = member.name.lastIndexOf('.');
            if (dot >= 0 && member.name.substring(dot).matches("\\.[A-Za-z0-9]{1,12}")) suffix = member.name.substring(dot);
            output = File.createTempFile("archive-member-", suffix, directory);
            CRC32 crc = new CRC32(); MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
            long total = 0; byte[] buffer = new byte[65536];
            try (InputStream in = zip.getInputStream(selected); OutputStream out = new FileOutputStream(output)) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    if (count > member.size - total || count > MAX_MEMBER - total) throw new IOException("Expanded bytes exceed the declared size or extraction budget");
                    out.write(buffer, 0, count); crc.update(buffer, 0, count); digest.update(buffer, 0, count); total += count;
                }
            }
            requireLength(inventory.source, inventory.sourceSize); checkCancelled(cancelled);
            if (total != member.size || crc.getValue() != member.crc32) throw new IOException("Selected member size or CRC32 mismatch");
            success = true; return new Extracted(output, total, Inspector.toHex(digest.digest()));
        } catch (IOException | RuntimeException e) {
            success = false; throw e; // Includes a failed ZipFile.close() after payload validation.
        } finally { if (!success && output != null) output.delete(); }
    }

    private static void requireLength(File source, long expected) throws IOException {
        if (!source.isFile() || source.length() != expected) throw new IOException("Archive length changed or input is unavailable; reload it");
    }
    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Archive operation cancelled");
    }
}
