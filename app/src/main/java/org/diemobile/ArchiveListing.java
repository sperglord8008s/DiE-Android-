// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.util.*;

/** One virtual listing shared by the archive tree, gallery and complete scoped file export. */
final class ArchiveListing {
    static final class Entry {
        final String label, folder;
        final NativeArchiveStore.Member member;

        Entry(String label, String folder, NativeArchiveStore.Member member) {
            this.label = label;
            this.folder = folder;
            this.member = member;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static List<Entry> entries(NativeArchiveSession.Location location) {
        String query = location.filter.toLowerCase(Locale.ROOT);
        TreeMap<String, Entry> folders = new TreeMap<>();
        ArrayList<Entry> rows = new ArrayList<>();
        for (NativeArchiveStore.Member member : location.inventory.members) {
            String label = label(member);
            if (!location.allEntries && member.treeName()) {
                if (!member.name.startsWith(location.prefix) || member.name.equals(location.prefix))
                    continue;
                String tail = member.name.substring(location.prefix.length());
                int slash = tail.indexOf('/');
                if (slash >= 0) {
                    String folder = tail.substring(0, slash + 1);
                    if (folder.toLowerCase(Locale.ROOT).contains(query))
                        folders.putIfAbsent(
                                folder,
                                new Entry(
                                        "Directory: "
                                                + NativeArchiveSession.abbreviated(folder, 160),
                                        location.prefix + folder,
                                        null));
                    continue;
                }
            } else if (!location.allEntries && !location.prefix.isEmpty()) continue;
            if (member.name.toLowerCase(Locale.ROOT).contains(query)
                    || label.toLowerCase(Locale.ROOT).contains(query))
                rows.add(new Entry(label, null, member));
        }
        rows.sort(Comparator.comparingInt(row -> row.member.index));
        rows.addAll(0, folders.values());
        return Collections.unmodifiableList(rows);
    }

    /** Includes all matching pages and descendants of displayed virtual folders, once per index. */
    static List<NativeArchiveStore.Member> selection(NativeArchiveSession.Location location) {
        Set<Integer> selected = new HashSet<>();
        List<Entry> entries = entries(location);
        for (Entry entry : entries) {
            if (entry.member != null) selected.add(entry.member.index);
        }
        // Prefix lookup avoids an entries × folders walk for large ordinary archives.
        Set<String> folders = new HashSet<>();
        for (Entry entry : entries) if (entry.folder != null) folders.add(entry.folder);
        List<NativeArchiveStore.Member> result = new ArrayList<>();
        for (NativeArchiveStore.Member member : location.inventory.members) {
            if (!selected.contains(member.index) && member.treeName()) {
                for (int slash = member.name.indexOf('/');
                        slash >= 0;
                        slash = member.name.indexOf('/', slash + 1)) {
                    if (folders.contains(member.name.substring(0, slash + 1))) {
                        selected.add(member.index);
                        break;
                    }
                }
            }
            if (selected.contains(member.index)) result.add(member);
        }
        return Collections.unmodifiableList(result);
    }

    private static String label(NativeArchiveStore.Member member) {
        return "#"
                + member.index
                + " "
                + NativeArchiveSession.abbreviated(member.name, 160)
                + "\n"
                + (member.directory
                        ? "Directory"
                        : member.linked
                                ? "Link (never materialized)"
                                : member.regular ? "Regular member" : "Special / unknown type")
                + " · declared size="
                + (member.size == null ? "unknown" : member.size)
                + (member.encrypted ? " · encrypted" : "")
                + (!member.treeName() ? " · name outside virtual tree" : "");
    }

    private ArchiveListing() {}
}
