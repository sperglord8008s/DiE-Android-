# Source-built Android binary resources engine

The app builds all 27 Java source files published by Google as
`com.android.tools.apkparser:binary-resources:32.5.1:sources`. The publisher is
the Android Open Source Project; the source archive includes the complete
Apache-2.0 license in `NOTICE`. It is fetched at build time from Google's Maven
repository, never by the running application.

Source JAR SHA-256:
`5f8079eb2d2535b4d8bdc4782022287b52a40dce852fc942fca2adc12f78ccdc`.
The exact URL and publisher notice are bundled in
`app/src/main/assets/notices/android-resources-32.5.1.txt`.

```sh
bash scripts/fetch-resources.sh
bash scripts/test-resources.sh
# Require independent AAPT2-generated references as well:
DIE_REQUIRE_AAPT2=true bash scripts/test-resources.sh
```

Set `RESOURCE_DEPS_DIR` to retain the source/host-test dependency cache.
`DIE_OFFLINE=true` prevents source acquisition when the pinned source JAR is
missing; Gradle's `--offline` also selects this behavior and requires cached
Maven artifacts. Source preparation verifies the complete managed source tree,
rejects contributor edits/new files/symlinks and replaces a new source snapshot
atomically. Generated vendor sources are ignored by Git. Keep changes in the
checked-in patch or app-owned adapter, rather than editing the vendor tree.

`patches/android-resource-engine.patch` makes these changes:

- Replace three Java switch expressions with equivalent Java 8 syntax. Compile
  the engine with `--release 8` to avoid newer covariant NIO API linkage on the
  app's API 26 minimum.
- Decode UTF-16LE string-pool storage as exact code units, preserving isolated
  surrogates which a `Charset` decoder would replace.
- Treat a style offset of `0xffffffff` as an absent style, retaining its original
  wire offset separately.
- Read a legacy 284-byte package header without consuming a nonexistent
  `typeIdOffset`; expose the present adjustment with a read-only getter.

The app's GPL-3.0-only adapter first validates physical chunks, offsets, strings,
entries, styles and shared budgets. It then invokes the actual Google parser
and compares its chunk/string/style models against that inventory. A small
same-package read-only access class exposes protected source coordinates.
Resource bags and sparse entries use the original wire inventory for every
ordinal: the upstream maps normalize duplicate keys, so they cannot serve as a
complete stored-record inventory. The adapter never serializes a modified model
and presents that as original bytes.

Guava 31.1-android is already shared with DEX inspection. AutoValue 1.11.0 and its
annotations, Checker compatibility annotations, jsr305 and Error Prone
annotations are build-only. AutoValue generates the parser's immutable classes;
its annotation processor is not part of the APK. Host-test dependency SHA-256
values are checked in `scripts/test-resources.sh` and the existing DEX dependency
lock. The app uses Android's built-in `org.json`.

See [investigation contracts](../docs/android-resources-investigation.md) for
tables, source ownership, budgets, tests and unsupported cases. Full Android
platform acceptance, resource resolution and device IPC remain separate checks.
