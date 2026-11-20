// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import org.json.*;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Actual Binwalk/PhotoRec finding → source-bound libarchive parsing → exact derived bytes. */
public final class ArchiveLaunchTest {
    static int fd(FileDescriptor value) throws Exception {
        Field field = FileDescriptor.class.getDeclaredField("fd");
        field.setAccessible(true);
        return field.getInt(value);
    }

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    interface Task {
        void run() throws Exception;
    }

    static void refuse(Task task, String reason) throws Exception {
        try {
            task.run();
            throw new AssertionError("Accepted " + reason);
        } catch (IOException expected) {
            require(expected.getMessage() != null, "Missing failure diagnostic");
        }
    }

    public static void main(String[] args) throws Exception {
        require(ArchiveEngine.AVAILABLE, ArchiveEngine.ERROR);
        require(BinwalkEngine.AVAILABLE, BinwalkEngine.ERROR);
        require(PhotoRecEngine.AVAILABLE, PhotoRecEngine.ERROR);
        Path fixtures = Path.of(args[0]),
                temporary = Files.createTempDirectory("glassdigestor-archive-launch");
        JSONObject expected = new JSONObject(Files.readString(fixtures.resolve("expected.json")));
        try {
            for (boolean photo : new boolean[] {false, true}) {
                File original =
                        temporary
                                .resolve(photo ? "gzip-disguised.jpeg" : "tar-disguised.jpeg")
                                .toFile();
                long offset;
                if (photo) {
                    byte[] prefix = "An observed embedded start\0".getBytes(StandardCharsets.UTF_8);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    bytes.write(prefix);
                    bytes.write(Files.readAllBytes(fixtures.resolve("raw.gz")));
                    Files.write(original.toPath(), bytes.toByteArray());
                    offset = prefix.length;
                } else {
                    Files.copy(fixtures.resolve("embedded.blob"), original.toPath());
                    offset = expected.getJSONObject("embedded.blob").getLong("offset");
                }
                BinaryRunner runner =
                        new BinaryRunner() {
                            public String scan(File file, BooleanSupplier stop) throws Exception {
                                EvidenceRows.check(stop);
                                try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                                    return photo
                                            ? PhotoRecEngine.scanFd(fd(input.getFD()))
                                            : BinwalkEngine.scanFd(fd(input.getFD()));
                                }
                            }
                        };
                CarvingBackend backend =
                        photo
                                ? new PhotoRecBackend(
                                        original,
                                        temporary.resolve("photorec").toFile(),
                                        original.getName(),
                                        runner)
                                : new BinwalkBackend(
                                        original,
                                        temporary.resolve("binwalk").toFile(),
                                        original.getName(),
                                        runner);
                NativeArchiveSession session = null;
                try {
                    backend.initialRequest(() -> false);
                    EvidenceRows rows = backend.open("all", () -> false);
                    EvidenceRows.Row selected = null;
                    for (int i = 0; i < rows.size(); i++) {
                        EvidenceRows.Row row = rows.row(i, () -> false);
                        if (row.physicalOffset != null
                                && row.physicalOffset == offset
                                && (photo ? row.name.equals("gz") : row.name.contains("tar"))) {
                            selected = row;
                            break;
                        }
                    }
                    require(
                            selected != null,
                            "No actual "
                                    + (photo ? "PhotoRec gzip" : "Binwalk TAR")
                                    + " finding at the independent embedded start");
                    if (photo)
                        require(
                                selected.sizeBytes == null,
                                "Expected actual PhotoRec gzip extent to remain unknown");
                    JSONObject origin =
                            backend.provenance()
                                    .put(
                                            "retainedUnknownField",
                                            "NUL\0 supplementary 😀 isolated \uDFFF");
                    ArchiveLaunch launch =
                            ArchiveLaunch.fromFinding(
                                    backend.ownedByteSource(), rows.kind, selected, origin);
                    require(
                            launch.offset == offset
                                    && launch.length == backend.ownedByteSource().size - offset,
                            "Parsing tail differs");
                    require(
                            launch.json().isNull("recoveredExtentBytes"),
                            "Parsing scope became a recovered extent");
                    require(
                            new JSONObject(launch.json().getString("findingRecordRaw"))
                                            .isNull("sizeBytes")
                                    == (selected.sizeBytes == null),
                            "Original finding extent changed");
                    require(
                            new JSONObject(launch.json().getString("findingProvenanceRaw"))
                                    .getString("retainedUnknownField")
                                    .equals(origin.getString("retainedUnknownField")),
                            "Exact provenance UTF-16 changed");
                    session =
                            NativeArchiveSession.open(
                                    backend.byteSource(),
                                    original.getName(),
                                    temporary.resolve("archives").toFile(),
                                    launch.offset,
                                    launch.length,
                                    NativeArchiveStoreTest.NATIVE,
                                    launch,
                                    () -> false);
                    require(
                            session.current().inventory.complete,
                            session.current().inventory.failure);
                    backend.close();
                    original.delete();
                    String state = session.retentionState();
                    File sessionDirectory = session.directory;
                    int before = NativeArchiveStoreTest.calls;
                    session.close(true);
                    session =
                            NativeArchiveSession.restore(
                                    sessionDirectory.getParentFile(),
                                    state,
                                    NativeArchiveStoreTest.NATIVE,
                                    () -> false);
                    require(NativeArchiveStoreTest.calls == before, "Restore rescanned");
                    require(
                            session.launch.raw.equals(launch.raw),
                            "Original launch packet changed on restore");
                    require(
                            session.evidence().getString("originalLaunchRaw").equals(launch.raw),
                            "Original launch absent from export");
                    NativeArchiveStore.Member member = session.current().inventory.members.get(0);
                    try (NativeArchiveStore.Acquired acquired =
                            session.acquire(
                                    member, temporary.resolve("members").toFile(), () -> false)) {
                        String wantedSha =
                                photo
                                        ? expected.getJSONObject("raw.gz")
                                                .getJSONArray("entries")
                                                .getJSONObject(0)
                                                .getString("sha256")
                                        : expected.getJSONObject("embedded.blob")
                                                .getJSONArray("entries")
                                                .getJSONObject(0)
                                                .getString("sha256");
                        require(
                                acquired.input.sha256.equals(wantedSha),
                                "Independent decoded member bytes differ");
                    }
                    JSONObject forged = launch.json().put("scopeOffset", "0");
                    refuse(() -> ArchiveLaunch.parse(forged.toString()), "coerced interval");
                    InputSnapshot root = session.locations().get(0).input;
                    ArchiveLaunch wrong =
                            ArchiveLaunch.forInput(
                                    "0".repeat(64), root.size, launch.offset, launch.length);
                    int nativeBefore = NativeArchiveStoreTest.calls;
                    refuse(
                            () ->
                                    NativeArchiveSession.open(
                                            root.file,
                                            "wrong identity",
                                            temporary.resolve("refused").toFile(),
                                            wrong.offset,
                                            wrong.length,
                                            NativeArchiveStoreTest.NATIVE,
                                            wrong,
                                            () -> false),
                            "wrong parent identity");
                    require(
                            NativeArchiveStoreTest.calls == nativeBefore,
                            "Mismatched source entered the native parser");
                    EvidenceRows.Row large =
                            new EvidenceRows.Row(
                                    selected.index,
                                    selected.name,
                                    selected.description,
                                    selected.physicalOffset,
                                    selected.sizeBytes,
                                    selected.json()
                                            .put(
                                                    "largeRetainedField",
                                                    "a".repeat(ArchiveLaunch.MAX_CHARACTERS)));
                    refuse(
                            () -> ArchiveLaunch.fromFinding(root, rows.kind, large, origin),
                            "oversized launch without an explicit error");
                    require(root.file.isFile(), "Launch refusal deleted caller input");
                } finally {
                    backend.close();
                    if (session != null) session.close();
                }
            }
            System.out.println(
                    "Actual Binwalk embedded TAR and PhotoRec unknown-extent gzip launch chains"
                        + " passed: independent decoded bytes, exact source/provenance/Unicode, no"
                        + " inferred extents, offline restore and pre-native identity/budget"
                        + " refusals.");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }
}
