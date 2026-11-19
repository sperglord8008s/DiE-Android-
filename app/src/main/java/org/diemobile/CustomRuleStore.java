// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Bounded offline rule snapshots. Import never changes the active database. */
final class CustomRuleStore {
    static final long MAX_ARCHIVE = 32L * 1024 * 1024;
    static final long MAX_EXPANDED = 64L * 1024 * 1024;
    static final int MAX_FILE = 1024 * 1024, MAX_ENTRIES = 10000;
    private static final Set<String> FORMATS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("Binary", "COM", "Archive", "ZIP", "JAR", "APK",
        "IPA", "NPM", "MACHOFAT", "DEX", "MSDOS", "LE", "LX", "NE", "PE", "ELF", "MACH", "DOS16M",
        "DOS4G", "Amiga", "AtariST", "JavaClass", "PYC", "PDF", "CFBF", "Image", "JPEG", "PNG", "RAR", "ISO9660")));

    static final class Snapshot {
        final File directory, database, archive;
        final String digest, source;
        final int ruleCount;
        final long expandedBytes;

        Snapshot(File directory, String digest, String source, int ruleCount, long expandedBytes) {
            this.directory = directory; this.database = new File(directory, "db");
            this.archive = new File(directory, "archive.zip");
            this.digest = digest; this.source = source; this.ruleCount = ruleCount; this.expandedBytes = expandedBytes;
        }
    }

    private CustomRuleStore() {}

    static synchronized Snapshot install(File store, File archive, String source) throws Exception {
        if (archive.length() == 0 || archive.length() > MAX_ARCHIVE) throw new IOException("Rule ZIP must be 1 byte–32 MiB");
        if (source == null || source.length() > 1024) throw new IOException("Invalid source display name");
        Files.createDirectories(store.toPath());
        String digest = sha256(archive);
        File destination = new File(store, digest);
        if (destination.isDirectory()) return read(store, digest);
        File staging = Files.createTempDirectory(store.toPath(), ".staging-").toFile();
        long total = 0; int rules = 0, entries = 0;
        Set<String> names = new HashSet<>();
        try {
            File database = new File(staging, "db"); Files.createDirectory(database.toPath());
            try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
                Enumeration<? extends ZipEntry> iterator = zip.entries();
                while (iterator.hasMoreElements()) {
                    ZipEntry entry = iterator.nextElement();
                    if (++entries > MAX_ENTRIES) throw new IOException("Rule ZIP exceeds 10,000 entries");
                    if (entry.isDirectory() && (entry.getSize() != 0 || entry.getCrc() != 0)) throw new IOException("Directory entry contains data");
                    String name = validateName(entry.getName(), entry.isDirectory());
                    if (name.isEmpty()) continue;
                    if (!names.add(name)) throw new IOException("Duplicate rule ZIP entry: " + name);
                    File output = new File(database, name);
                    // Files are always newly written bytes; ZIP links and permissions are never applied.
                    if (entry.isDirectory()) { Files.createDirectories(output.toPath()); continue; }
                    if (entry.getSize() < 0 || entry.getSize() > MAX_FILE || entry.getCrc() < 0) {
                        throw new IOException("Invalid or oversized rule ZIP member: " + name);
                    }
                    Files.createDirectories(output.getParentFile().toPath());
                    CRC32 crc = new CRC32(); long size = 0;
                    try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(output)) {
                        byte[] buffer = new byte[32768]; int count;
                        while ((count = in.read(buffer)) != -1) {
                            size += count; total += count;
                            if (size > MAX_FILE || total > MAX_EXPANDED) throw new IOException("Expanded rule ZIP exceeds 1 MiB/member or 64 MiB total");
                            crc.update(buffer, 0, count); out.write(buffer, 0, count);
                        }
                    }
                    if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("Rule ZIP size/CRC mismatch: " + name);
                    String[] parts = name.split("/");
                    if (parts.length == 2 && FORMATS.contains(parts[0]) && !parts[1].equals("_init")) rules++;
                }
            }
            if (rules == 0) throw new IOException("No format rules found; use ELF/*.sg, PE/*.sg or another supported format folder");
            copyArchive(archive, new File(staging, "archive.zip"));
            if (!sha256(new File(staging, "archive.zip")).equals(digest)) throw new IOException("Rule archive changed during import");
            Properties metadata = new Properties();
            metadata.setProperty("sha256", digest); metadata.setProperty("source", source);
            metadata.setProperty("rules", Integer.toString(rules)); metadata.setProperty("expandedBytes", Long.toString(total));
            metadata.setProperty("importedAt", java.time.Instant.now().toString());
            try (OutputStream out = new FileOutputStream(new File(staging, "snapshot.properties"))) {
                metadata.store(out, "DiE Android offline custom rule snapshot");
            }
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return read(store, digest);
        } finally { remove(staging); }
    }

    static synchronized void activate(File store, String digest) throws IOException {
        read(store, digest);
        Path pending = Files.createTempFile(store.toPath(), ".active-", ".tmp");
        try {
            Files.write(pending, (digest + "\n").getBytes(StandardCharsets.US_ASCII));
            Files.move(pending, new File(store, "active").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending); }
    }

    static synchronized Snapshot active(File store) throws IOException {
        File active = new File(store, "active");
        if (!active.exists()) return null;
        if (active.length() > 128) throw new IOException("Invalid active custom database record");
        return read(store, new String(Files.readAllBytes(active.toPath()), StandardCharsets.US_ASCII).trim());
    }

    static synchronized List<Snapshot> list(File store) throws IOException {
        List<Snapshot> result = new ArrayList<>();
        File[] children = store.listFiles();
        if (children != null) for (File child : children) if (child.getName().matches("[0-9a-f]{64}")) result.add(read(store, child.getName()));
        result.sort(Comparator.comparing(s -> s.digest));
        return Collections.unmodifiableList(result);
    }

    private static Snapshot read(File store, String digest) throws IOException {
        if (!digest.matches("[0-9a-f]{64}")) throw new IOException("Invalid custom database digest");
        File directory = new File(store, digest), file = new File(directory, "snapshot.properties");
        if (!file.isFile() || file.length() > 8192) throw new IOException("Incomplete custom database snapshot");
        Properties metadata = new Properties();
        try (InputStream in = new FileInputStream(file)) { metadata.load(in); }
        if (!digest.equals(metadata.getProperty("sha256")) || !new File(directory, "db").isDirectory()
            || !new File(directory, "archive.zip").isFile()) throw new IOException("Invalid custom database snapshot");
        try {
            int rules = Integer.parseInt(metadata.getProperty("rules"));
            long bytes = Long.parseLong(metadata.getProperty("expandedBytes"));
            if (rules < 1 || rules > MAX_ENTRIES || bytes < 0 || bytes > MAX_EXPANDED) throw new NumberFormatException();
            return new Snapshot(directory, digest, metadata.getProperty("source", ""), rules, bytes);
        } catch (NumberFormatException e) { throw new IOException("Invalid custom database metadata", e); }
    }

    private static String validateName(String original, boolean directory) throws IOException {
        if (original.isEmpty() || original.length() > 512 || original.startsWith("/") || original.indexOf('\\') >= 0 || original.indexOf(':') >= 0) {
            throw new IOException("Unsafe rule ZIP path: " + original);
        }
        for (int i = 0; i < original.length(); i++) if (Character.isISOControl(original.charAt(i))) throw new IOException("Control character in rule ZIP path");
        String name = directory && original.endsWith("/") ? original.substring(0, original.length() - 1) : original;
        for (String part : name.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe rule ZIP path: " + original);
        if (name.equals("db") || name.equals("db_custom")) {
            if (directory) return "";
            throw new IOException("Database wrapper must be a directory");
        }
        if (name.startsWith("db/")) name = name.substring(3);
        else if (name.startsWith("db_custom/")) name = name.substring(10);
        String[] parts = name.split("/", -1);
        if (parts.length > 2) throw new IOException("Nested rule folders are not scanned by CDiE: " + name);
        if (directory) {
            if (parts.length != 1 || !(FORMATS.contains(name) || name.equals("_metadata"))) throw new IOException("Unsupported rule directory: " + name);
        } else if (parts.length == 2) {
            if (parts[0].equals("_metadata")) {
                if (!parts[1].endsWith(".txt")) throw new IOException("Metadata must be a .txt mapping");
            } else if (!FORMATS.contains(parts[0]) || !(parts[1].endsWith(".sg") || !parts[1].contains("."))) {
                throw new IOException("Unsupported rule member: " + name);
            }
        } else if (FORMATS.contains(name) || name.equals("_metadata") ||
            !(name.endsWith(".sg") || !name.contains(".") || name.endsWith(".txt") || name.endsWith(".md") || name.equals("info.ini"))) {
            throw new IOException("Unsupported root helper or notice: " + name);
        }
        return name;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); long total = 0;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = in.read(buffer)) != -1) {
                if ((total += count) > MAX_ARCHIVE) throw new IOException("Rule ZIP exceeds 32 MiB");
                hash.update(buffer, 0, count);
            }
        }
        return Inspector.toHex(hash.digest());
    }

    private static void copyArchive(File source, File destination) throws IOException {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32768]; int count; long total = 0;
            while ((count = in.read(buffer)) != -1) {
                if ((total += count) > MAX_ARCHIVE) throw new IOException("Rule ZIP changed or exceeds 32 MiB");
                out.write(buffer, 0, count);
            }
        }
    }

    private static void remove(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (!file.delete()) throw new IOException("Cannot remove incomplete rule import");
    }
}
