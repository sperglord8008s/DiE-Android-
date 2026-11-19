// SPDX-License-Identifier: GPL-3.0-only
#include "archive_reader.h"
#include "input.h"
#include "json.h"
#include <archive_entry.h>
#include <memory>
#include <vector>

namespace die_archive {
namespace {
constexpr int MAX_ENTRIES = 20000;
constexpr int MAX_AUXILIARY = 20000;
constexpr uint64_t MAX_TRANSFORMED = 512ULL * 1024 * 1024;
constexpr size_t MAX_ROW = 4 * 1024 * 1024;

struct Reader {
    struct archive *value = archive_read_new();
    Reader() { if (!value) throw std::runtime_error("Cannot allocate archive reader"); }
    ~Reader() { archive_read_free(value); }
    Reader(const Reader &) = delete;
    Reader &operator=(const Reader &) = delete;
};

std::string diagnostic(struct archive *reader) {
    const char *error = archive_error_string(reader);
    return error ? error : "libarchive returned no diagnostic";
}
void required(int status, struct archive *reader) {
    if (status != ARCHIVE_OK) throw std::runtime_error(diagnostic(reader));
}

std::string configure(struct archive *reader, Input &input) {
    // Explicit registration avoids program-filter fallbacks and filesystem-backed mtree data.
    struct Format { const char *name; int (*support)(struct archive *); };
    const Format formats[] = {
        {"7zip", archive_read_support_format_7zip}, {"ar", archive_read_support_format_ar},
        {"cab", archive_read_support_format_cab}, {"cpio", archive_read_support_format_cpio},
        {"empty", archive_read_support_format_empty}, {"iso9660", archive_read_support_format_iso9660},
        {"lha", archive_read_support_format_lha}, {"rar", archive_read_support_format_rar},
        {"rar5", archive_read_support_format_rar5}, {"tar", archive_read_support_format_tar},
        {"warc", archive_read_support_format_warc}, {"xar", archive_read_support_format_xar},
        {"zip", archive_read_support_format_zip}, {"raw-transformed-only", archive_read_support_format_raw}
    };
    std::vector<std::string> registrations;
    for (const auto &format : formats) {
        int status = format.support(reader); Object value;
        value.text("format", format.name); value.number("registrationStatus", status);
        value.raw("diagnostic", status == ARCHIVE_OK ? "null" : quote(diagnostic(reader).c_str()));
        registrations.push_back(value.finish());
        if (status < ARCHIVE_WARN) required(status, reader);
        archive_clear_error(reader);
    }
    for (auto support : {archive_read_support_filter_none, archive_read_support_filter_gzip,
            archive_read_support_filter_bzip2, archive_read_support_filter_xz,
            archive_read_support_filter_lzma, archive_read_support_filter_lzip,
            archive_read_support_filter_lz4, archive_read_support_filter_zstd,
            archive_read_support_filter_compress, archive_read_support_filter_rpm,
            archive_read_support_filter_uu}) required(support(reader), reader);
    required(archive_read_set_callback_data(reader, &input), reader);
    required(archive_read_set_read_callback(reader, Input::read), reader);
    required(archive_read_set_skip_callback(reader, Input::skip), reader);
    required(archive_read_set_seek_callback(reader, Input::seek), reader);
    required(archive_read_open1(reader), reader);
    return array(registrations);
}

void work(struct archive *reader, Input &input) {
    input.check();
    if (!input.failure.empty()) throw std::runtime_error(input.failure);
    if (archive_filter_code(reader, 0) != ARCHIVE_FILTER_NONE
            && archive_filter_bytes(reader, 0) > int64_t(MAX_TRANSFORMED))
        throw std::runtime_error("Transformed archive stream exceeds 512 MiB work budget");
}

std::string time_value(bool set, int64_t seconds, long nanos) {
    if (!set) return "null";
    Object out; out.number("secondsSinceUnixEpoch", seconds); out.number("nanoseconds", nanos);
    return out.finish();
}

std::string metadata(struct archive *reader, struct archive_entry *entry, int index, int header_status, const std::string &header_diagnostic) {
    Object out; out.number("index", index); out.number("headerStatus", header_status);
    out.raw("headerDiagnostic", header_status == ARCHIVE_OK ? "null" : returned_text(header_diagnostic.c_str()));
    out.raw("pathname", returned_text(archive_entry_pathname(entry)));
    out.raw("pathnameUtf8", returned_text(archive_entry_pathname_utf8(entry)));
    out.raw("hardlink", returned_text(archive_entry_hardlink(entry)));
    out.raw("symlink", returned_text(archive_entry_symlink(entry)));
    out.raw("userNameUtf8", returned_text(archive_entry_uname_utf8(entry)));
    out.raw("groupNameUtf8", returned_text(archive_entry_gname_utf8(entry)));
    out.raw("hardlinkUtf8", returned_text(archive_entry_hardlink_utf8(entry)));
    out.raw("symlinkUtf8", returned_text(archive_entry_symlink_utf8(entry)));
    out.raw("sourcePathAccessor", returned_text(archive_entry_sourcepath(entry)));
    out.text("modeText", archive_entry_strmode(entry));
    out.raw("userName", returned_text(archive_entry_uname(entry)));
    out.raw("groupName", returned_text(archive_entry_gname(entry)));
    out.raw("fileFlagsText", returned_text(archive_entry_fflags_text(entry)));
    out.raw("sizeBytes", archive_entry_size_is_set(entry) ? std::to_string(archive_entry_size(entry)) : "null");
    out.raw("uid", archive_entry_uid_is_set(entry) ? std::to_string(archive_entry_uid(entry)) : "null");
    out.raw("gid", archive_entry_gid_is_set(entry) ? std::to_string(archive_entry_gid(entry)) : "null");
    out.raw("inode", archive_entry_ino_is_set(entry) ? std::to_string(archive_entry_ino64(entry)) : "null");
    out.number("mode", archive_entry_mode(entry));
    out.raw("fileType", archive_entry_filetype_is_set(entry) ? std::to_string(archive_entry_filetype(entry)) : "null");
    out.raw("permissions", archive_entry_perm_is_set(entry) ? std::to_string(archive_entry_perm(entry)) : "null");
    out.number("linkCount", archive_entry_nlink(entry));
    out.number("deviceMajor", archive_entry_devmajor(entry)); out.number("deviceMinor", archive_entry_devminor(entry));
    out.flag("deviceIsSet", archive_entry_dev_is_set(entry));
    out.number("specialDeviceMajor", archive_entry_rdevmajor(entry)); out.number("specialDeviceMinor", archive_entry_rdevminor(entry));
    out.flag("specialDeviceIsSet", archive_entry_rdev_is_set(entry));
    out.number("symlinkType", archive_entry_symlink_type(entry));
    out.number("dataEncrypted", archive_entry_is_data_encrypted(entry));
    out.number("metadataEncrypted", archive_entry_is_metadata_encrypted(entry));
    out.number("encrypted", archive_entry_is_encrypted(entry));
    unsigned long flags = 0, cleared = 0; archive_entry_fflags(entry, &flags, &cleared);
    out.text("fileFlagsSetUnsigned", std::to_string(flags).c_str());
    out.text("fileFlagsClearUnsigned", std::to_string(cleared).c_str());
    out.raw("atime", time_value(archive_entry_atime_is_set(entry), archive_entry_atime(entry), archive_entry_atime_nsec(entry)));
    out.raw("mtime", time_value(archive_entry_mtime_is_set(entry), archive_entry_mtime(entry), archive_entry_mtime_nsec(entry)));
    out.raw("ctime", time_value(archive_entry_ctime_is_set(entry), archive_entry_ctime(entry), archive_entry_ctime_nsec(entry)));
    out.raw("birthtime", time_value(archive_entry_birthtime_is_set(entry), archive_entry_birthtime(entry), archive_entry_birthtime_nsec(entry)));
    out.number("headerPositionUncompressedStream", archive_read_header_position(reader));
    out.text("headerPositionMeaning", "Raw archive_read_header_position observation from this iteration; CAB values can change after data reads. Not a physical offset or stable entry identity");
    out.text("formatNameAtHeader", archive_format_name(reader));
    out.flag("dataReadRequested", false);

    int xattrs = archive_entry_xattr_reset(entry);
    if (xattrs < 0 || xattrs > MAX_AUXILIARY) throw std::runtime_error("Archive xattr count exceeds budget");
    std::vector<std::string> attributes;
    for (int i = 0; i < xattrs; ++i) {
        const char *name = nullptr; const void *bytes = nullptr; size_t size = 0;
        if (archive_entry_xattr_next(entry, &name, &bytes, &size) != ARCHIVE_OK) throw std::runtime_error("Incomplete archive xattr iterator");
        Object value; value.number("index", i); value.raw("name", returned_text(name));
        value.number("sizeBytes", int64_t(size)); value.text("valueHex", hex(bytes, size).c_str()); attributes.push_back(value.finish());
    }
    out.raw("xattrs", array(attributes));
    int sparse = archive_entry_sparse_reset(entry);
    if (sparse < 0 || sparse > MAX_AUXILIARY) throw std::runtime_error("Archive sparse count exceeds budget");
    std::vector<std::string> sparse_ranges;
    for (int i = 0; i < sparse; ++i) {
        la_int64_t offset = 0, length = 0;
        if (archive_entry_sparse_next(entry, &offset, &length) != ARCHIVE_OK) throw std::runtime_error("Incomplete archive sparse iterator");
        Object value; value.number("index", i); value.number("logicalOffset", offset); value.number("sizeBytes", length); sparse_ranges.push_back(value.finish());
    }
    out.raw("sparseRanges", array(sparse_ranges));
    int types = ARCHIVE_ENTRY_ACL_TYPE_ACCESS | ARCHIVE_ENTRY_ACL_TYPE_DEFAULT | ARCHIVE_ENTRY_ACL_TYPE_NFS4;
    int acls = archive_entry_acl_reset(entry, types);
    if (acls < 0 || acls > MAX_AUXILIARY) throw std::runtime_error("Archive ACL count exceeds budget");
    std::vector<std::string> access;
    for (int i = 0; i < acls; ++i) {
        int type = 0, permissions = 0, tag = 0, qualifier = 0; const char *name = nullptr;
        if (archive_entry_acl_next(entry, types, &type, &permissions, &tag, &qualifier, &name) != ARCHIVE_OK)
            throw std::runtime_error("Incomplete archive ACL iterator");
        Object value; value.number("index", i); value.number("type", type); value.number("permissions", permissions);
        value.number("tag", tag); value.number("qualifier", qualifier); value.raw("name", returned_text(name)); access.push_back(value.finish());
    }
    out.number("aclTypes", archive_entry_acl_types(entry)); out.raw("aclEntries", array(access));
    size_t mac_size = 0; const void *mac = archive_entry_mac_metadata(entry, &mac_size);
    out.number("macMetadataSizeBytes", int64_t(mac_size)); out.raw("macMetadataHex", mac ? quote(hex(mac, mac_size).c_str()) : "null");
    Object digests;
    const int algorithms[] = {ARCHIVE_ENTRY_DIGEST_MD5, ARCHIVE_ENTRY_DIGEST_RMD160, ARCHIVE_ENTRY_DIGEST_SHA1,
            ARCHIVE_ENTRY_DIGEST_SHA256, ARCHIVE_ENTRY_DIGEST_SHA384, ARCHIVE_ENTRY_DIGEST_SHA512};
    const int lengths[] = {16, 20, 20, 32, 48, 64}; const char *names[] = {"MD5", "RMD160", "SHA1", "SHA256", "SHA384", "SHA512"};
    for (unsigned i = 0; i < 6; ++i) {
        const unsigned char *digest = archive_entry_digest(entry, algorithms[i]);
        digests.raw(names[i], digest ? quote(hex(digest, size_t(lengths[i])).c_str()) : "null");
    }
    out.raw("digestAccessorBuffers", digests.finish());
    out.raw("digestPresence", "null");
    out.text("digestMeaning", "Pinned public getters return buffers even when unset; no public presence accessor exists. These are returned state bytes, not asserted declared/verified digests.");
    std::string result = out.finish();
    if (result.size() > MAX_ROW) throw std::runtime_error("Archive entry metadata exceeds 4 MiB");
    return result;
}

std::string filters(struct archive *reader) {
    std::vector<std::string> rows;
    for (int i = 0; i < archive_filter_count(reader); ++i) {
        Object value; value.number("index", i); value.number("code", archive_filter_code(reader, i));
        value.text("name", archive_filter_name(reader, i)); value.number("bytesConsumed", archive_filter_bytes(reader, i)); rows.push_back(value.finish());
    }
    return array(rows);
}

uint64_t payload(struct archive *reader, struct archive_entry *entry, Input &input, Descriptor &output) {
    struct stat destination{}; int flags = fcntl(output.value, F_GETFL);
    if (flags < 0 || (flags & O_ACCMODE) == O_RDONLY || fstat(output.value, &destination)
            || (flags & O_APPEND) || !S_ISREG(destination.st_mode) || destination.st_size != 0
            || (destination.st_dev == input.before.st_dev && destination.st_ino == input.before.st_ino))
        throw std::runtime_error("Archive payload requires a separate empty writable regular descriptor");
    if (archive_entry_filetype(entry) != AE_IFREG || archive_entry_hardlink(entry) || archive_entry_symlink(entry))
        throw std::runtime_error("Only regular stored member data can be acquired; no links/devices/directories are materialized");
    bool sized = archive_entry_size_is_set(entry);
    la_int64_t declared = archive_entry_size(entry);
    if (sized && (declared < 0 || uint64_t(declared) > kMaximumMemberBytes)) throw std::runtime_error("Archive member exceeds 256 MiB");
    uint64_t end = 0;
    for (;;) {
        work(reader, input); const void *bytes = nullptr; size_t count = 0; la_int64_t offset = 0;
        int status = archive_read_data_block(reader, &bytes, &count, &offset);
        if (status == ARCHIVE_EOF) break;
        required(status, reader);
        if (offset < 0 || uint64_t(offset) < end || uint64_t(offset) > kMaximumMemberBytes || count > kMaximumMemberBytes - uint64_t(offset))
            throw std::runtime_error("Archive member data exceeds its bounded ordered logical extent");
        if (sized && uint64_t(offset) + count > uint64_t(declared)) throw std::runtime_error("Member data exceeds its declared logical size");
        size_t done = 0;
        while (done < count) {
            input.check(); ssize_t written = pwrite(output.value, static_cast<const unsigned char *>(bytes) + done, count - done, off_t(uint64_t(offset) + done));
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) throw std::runtime_error("Cannot write complete acquired member bytes");
            done += size_t(written);
        }
        end = uint64_t(offset) + count;
    }
    // Sparse holes have zero-valued logical bytes; trailing holes are represented by the declared extent.
    if (sized && end < uint64_t(declared) && archive_entry_sparse_count(entry) == 0)
        throw std::runtime_error("Member data ended before declared size without a sparse-hole witness");
    uint64_t length = sized ? uint64_t(declared) : end;
    if (ftruncate(output.value, off_t(length))) throw std::runtime_error("Cannot finalize acquired member extent");
    return length;
}

std::string run(int descriptor, int destination, int64_t offset, int64_t length, int member) {
    Input input(descriptor, offset, length); Reader reader;
    std::vector<std::string> rows; size_t row_bytes = 0;
    std::string failure, selected, registrations = "[]"; bool complete = false, extracted = false;
    int warning_count = 0;
    uint64_t acquired = 0;
    try {
        registrations = configure(reader.value, input);
        for (;;) {
            work(reader.value, input); struct archive_entry *entry = nullptr;
            int status = archive_read_next_header(reader.value, &entry);
            if (status == ARCHIVE_EOF) { complete = true; break; }
            std::string header_diagnostic = diagnostic(reader.value);
            if (status == ARCHIVE_WARN && entry != nullptr) warning_count++;
            else required(status, reader.value);
            if (archive_format(reader.value) == ARCHIVE_FORMAT_RAW && archive_filter_code(reader.value, 0) == ARCHIVE_FILTER_NONE)
                throw std::runtime_error("Unrecognized archive: uncompressed raw fallback is not format identification");
            if (rows.size() == MAX_ENTRIES) throw std::runtime_error("Archive inventory exceeds 20,000 entries");
            std::string row = metadata(reader.value, entry, int(rows.size()), status, header_diagnostic);
            if (row.size() > 16 * 1024 * 1024 - row_bytes) throw std::runtime_error("Archive entry metadata exceeds 16 MiB aggregate");
            row_bytes += row.size(); rows.push_back(row);
            if (int(rows.size()) - 1 == member) {
                Descriptor output(destination); selected = row;
                acquired = payload(reader.value, entry, input, output); extracted = true;
            }
        }
        if (member >= 0 && !extracted) throw std::runtime_error("Selected archive member index does not exist");
        input.verify();
    } catch (const std::exception &error) { complete = false; failure = error.what(); }
    try { input.verify(); } catch (const std::exception &error) { complete = false; failure = error.what(); }
    Object out; out.text("schema", "org.diemobile.archive-evidence"); out.number("schemaVersion", 1);
    out.text("adapter", "diemobile-libarchive-1"); out.flag("ok", complete); out.flag("complete", complete);
    out.raw("error", failure.empty() ? "null" : quote(failure.c_str()));
    out.raw("errorBytesHex", failure.empty() ? "null" : quote(hex(failure.data(), failure.size()).c_str()));
    out.number("fileSizeBytes", input.before.st_size); out.number("scopeOffset", int64_t(input.base)); out.number("scopeSizeBytes", int64_t(input.length));
    out.text("scopeMeaning", "Bounded parsing interval; not an inferred recovered archive extent");
    out.number("headerWarningCount", warning_count); out.raw("formatRegistrations", registrations);
    out.number("entryCount", int64_t(rows.size())); out.raw("entries", array(rows));
    out.number("formatCode", archive_format(reader.value)); out.text("formatName", archive_format_name(reader.value));
    out.number("formatCapabilities", archive_read_format_capabilities(reader.value));
    out.number("hasEncryptedEntries", archive_read_has_encrypted_entries(reader.value)); out.raw("filters", filters(reader.value));
    out.text("engineVersion", archive_version_string()); out.text("engineDetails", archive_version_details());
    out.text("sourceRevision", "03036ffa409a2aa1c5bbad35be2202641a040fda");
    out.number("sourceReadBytes", int64_t(input.bytes_read)); out.number("sourceReadOperations", int64_t(input.operations));
    out.number("maximumInputBytes", int64_t(kMaximumInputBytes)); out.number("maximumSourceReadWorkBytes", int64_t(kMaximumIoWorkBytes));
    out.number("maximumTransformedStreamBytes", int64_t(MAX_TRANSFORMED)); out.number("maximumMemberBytes", int64_t(kMaximumMemberBytes));
    out.number("lzmaDecoderMemoryLimitBytes", 64LL * 1024 * 1024);
    out.number("zstdMaximumWindowBytes", 64LL * 1024 * 1024);
    out.text("memoryLimitMeaning", "Limits selected codec dictionaries/windows, not total upstream/worker heap or RSS");
    out.number("maximumEntries", MAX_ENTRIES); out.number("cooperativeDeadlineSeconds", 30);
    out.flag("callerDescriptorPositionPreserved", true); out.flag("ownedDescriptorDuplicate", true);
    out.flag("externalProgramsEnabled", false); out.flag("filesystemReaderEnabled", false);
    out.flag("pathExtractionEnabled", false); out.flag("passphraseSupport", false);
    out.flag("entryDataReadRequested", member >= 0);
    out.text("iterationMode", member >= 0 ? "selected-member data reads with metadata iteration" : "metadata iteration and skips");
    out.text("iterationMeaning", "Metadata iteration and skips; upstream may decode/check as needed. No independent full-member validation is implied.");
    out.number("selectedIndex", member); out.raw("selectedEntry", selected.empty() ? "null" : selected);
    out.raw("acquiredSizeBytes", complete && extracted ? std::to_string(acquired) : "null");
    out.text("acquisitionMeaning", "Only complete successful extraction publishes derived logical bytes; sparse holes are zeros, member names are never output paths, and stored-byte locations are not inferred.");
    return out.finish();
}
} // namespace

std::string inspect(int descriptor, int64_t offset, int64_t length) { return run(descriptor, -1, offset, length, -1); }
std::string extract(int descriptor, int output, int64_t offset, int64_t length, int member) {
    if (member < 0) throw std::runtime_error("Member index must be nonnegative");
    return run(descriptor, output, offset, length, member);
}
} // namespace die_archive
