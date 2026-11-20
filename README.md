<p align="center">
  <img src="app/src/main/res/drawable-nodpi/brand_mark.png" width="136" alt="GlassDigestor: a hexagonal lens over file layers" />
</p>

<h1 align="center">GlassDigestor</h1>
<p align="center"><strong>An offline investigation workbench for Android.</strong><br/>Explore the bytes. Follow the evidence. Understand the layers.</p>
<p align="center">
  <a href="https://github.com/sperglord8008s/GlassDigestor/releases">Pre-release downloads</a> ·
  <a href="docs/FEATURE-PARITY.md">Verified scope</a> ·
  <a href="docs/continuation.md">Build & contribute</a> ·
  <a href="https://github.com/sperglord8008s/GlassDigestor/issues">Report a problem</a>
</p>

<p align="center">
  <a href="https://github.com/sperglord8008s/GlassDigestor/actions/workflows/android.yml"><img alt="Android and host checks" src="https://github.com/sperglord8008s/GlassDigestor/actions/workflows/android.yml/badge.svg?branch=main" /></a>
  <img alt="Android API 26+; ARM64" src="https://img.shields.io/badge/Android-API_26%2B_%C2%B7_ARM64-59e1ca?labelColor=101920" />
  <a href="LICENSE"><img alt="GPL-3.0-only frontend" src="https://img.shields.io/badge/license-GPL--3.0--only-ffbc66?labelColor=101920" /></a>
</p>

GlassDigestor brings file identification, binary structures, media discovery,
Android package analysis and system investigation into one local workbench.
Technical evidence is visible by default, with expandable explanations of what
it means, where it came from and what remains unknown.

**Previously DiE Android.** This is an independent FOSS application built around
Detect It Easy / CDiE and other credited open-source engines. The original repository URL redirects here. The Android application ID
remains `org.diemobile`.

## Start exploring

| Investigate | What you can do |
|---|---|
| **Any selected file** | Identify byte-based formats, run desktop DiE signatures, inspect all eight whole-file digests, page through hex and classify strings into overlapping evidence groups. |
| **Mixed blobs & media** | Use **Gallerize** to see discovered images, audio, text and unknown regions; open owned previews, inspect EXIF/XMP and MPV codec metadata, and explore configurable byte/entropy grids. |
| **Embedded content** | Run actual Binwalk and PhotoRec header/data checks, retain nested/overlapping findings, preview exact bounded payloads, export checked recovered bytes, and hand the captured input to Gallerize. |
| **Executables** | Explore typed PE/ELF/Mach-O structures, maps, imports, exports, symbols, resources and disassembly; follow supported coordinates back to original bytes. |
| **Android apps** | Inspect DEX and smali, binary manifests/resources, APK signatures and certificates, captured base/split relationships, SDK name rules and locally imported tracker rules. |
| **Archives** | Browse content-identified archives and compressed streams with virtual folders, complete indexed metadata, nested Back, checked member previews/exports and explicit embedded intervals. Classic ZIP remains available. See the [archive contracts](docs/native-archives.md). |
| **System evidence** | Browse visible packages, declared services and readable files; capture normal-UID Binder registrations with their original output and limitations. |
| **Repeatable work** | Save scan profiles, run document batches, compare exact bytes and native detection trees, and export complete source-identified reports. |

No account, telemetry, cloud upload or `INTERNET` permission. Files stay on the
device. Samples are inspected as data; the application does not run them.
System visibility follows Android's permissions and sandbox.

[Detailed capabilities and engine credits](docs/workbench-features.md) ·
[Input budgets and tested file sizes](docs/input-budgets.md) ·
[Gallerize contracts](docs/gallerize-implementation.md)

## Install a pre-release

Download the ARM64 APK and its checksum/build manifest from
[GitHub Releases](https://github.com/sperglord8008s/GlassDigestor/releases).
The published numbered checkpoint is **0.6.0-pre-alpha.12** (Android version code **18**), including [sourced package knowledge](docs/package-knowledge.md), [independent preview origin evidence](docs/content-origins.md), [large text/Hex and full report navigation](docs/text-paging.md), the [native archive workbench](docs/native-archives.md), [embedded finding links](docs/archive-launches.md) and [checked recovery previews/exports](docs/recovered-content.md).
Android 8.0 / API 26 or later is required.

Debug APKs have numbered, source-identified names, for example
`GlassDigestor-0.6.0-pre-alpha.5-dev.g0123456789ab-arm64-v8a-debug.apk`.
Current development builds use **0.6.0-pre-alpha.13** / code **19**, including
[direct gallery archive inspection and explicit byte-range previews](docs/gallery-inspection-ranges.md)
and a separate [Android worker runtime laboratory](runtime-worker/README.md).
Seven worker/media/compiled-manifest contracts passed on actual x86 Android API 26/35;
[bounded gallery video frames/media observations](docs/gallery-media-previews.md)
are covered by an actual platform-codec runtime check. Development now combines PhotoRec and Binwalk in an [actual recovered-file
gallery with typed saves and payload ZIPs](docs/recovered-file-gallery.md).
[Recovered PDF file exports](docs/pdf-file-recovery.md) now extend that gallery.
[Observed APK → sourced community package knowledge](docs/apk-community-context.md)
remains available.
Package knowledge retains all 5,402 original UAD-NG records, including duplicate IDs. Earlier published checkpoints remain unchanged.

Tap the app title or **Options → Build details** to see the same version and
full source revision. See [build versioning](docs/build-versioning.md).

These testing builds use the existing debug signing configuration. Compare the
signer digest in each manifest before updating an existing installation; keys
from separate CI runners can differ. The historical
[0.5.0-alpha checkpoint](docs/releases/0.5.0-alpha.md) remains unchanged.

## What has been verified

The published checkpoint passed **all five GitHub Actions jobs** and
**175 Android host checks**, using real JNI/parsers
where documented. ARM64 compilation, APK signature/offline packaging, static
16 KiB alignment, source identities and independent host fixtures are checked.

The current gallery-range integration passes **170 Android host tests**,
ARM64 assembly/lint and the APK verifier for fifteen native libraries. Actual
PhotoRec → gallery → libarchive tests verify exact decoded bytes and origin
context; the fully written 80 MiB-plus gallery fixture remains green. Independent
oracles also reconstruct every knowledge source record and UTF-16 unit. See the
[knowledge](docs/package-knowledge.md), [text](docs/text-paging.md) and
[origin contracts](docs/content-origins.md).

**This remains a pre-alpha workbench.** Six worker/media-helper contracts pass
on x86 Android API 26/35 ([exact evidence](docs/verification/android-worker-runtime-8f2ab38.json)).
Production ARM64/handset/SAF/UI/media runtime acceptance and full desktop Detect
It Easy parity remain incomplete. Header
matches, declared permissions and high entropy are evidence to investigate;
they do not establish malicious behavior, complete validity or safety.

The [feature-parity checklist](docs/FEATURE-PARITY.md) distinguishes implemented,
host-verified and device-unverified functionality. The verified workbench is on
the default branch through [PR #9](https://github.com/sperglord8008s/GlassDigestor/pull/9).
Gallery-range development continues on `codex/gallery-inspection-ranges`.

## Build and contribute

Use JDK 17, Gradle 8.11.1, Android SDK/build-tools 35, NDK 28.2.13676358, CMake
3.22.1 and Rust 1.90.0. Dependency revisions, checksums, source bundles and
licenses are pinned. Build-time downloads are separate from offline app use.

Start with [AGENTS.md](AGENTS.md), the [design goals](docs/DESIGN-GOALS.md) and
[continuation instructions](docs/continuation.md). They include the complete
engine setup, build, host/native/UI checks and current next steps.

Useful contributions include reproducible parser fixtures, handset test reports,
accessibility fixes, source-audited engines, documentation and practical package
knowledge backed by evidence. For a bug, include the exact build identifier,
Android version, selected tool and observed error; share sample bytes only when
you have permission to do so.

## Open-source foundations

Detect It Easy / CDiE · Binwalk · PhotoRec · libarchive · libmpv / FFmpeg ·
metadata-extractor / Adobe XMP · AOSP apksig · Google smali / baksmali ·
Google binary-resources · RE2/J · LibChecker · UAD-NG.

The frontend is **GPL-3.0-only**. Upstream code, data and runtime components retain
their own licenses and notices; the build packages those notices and corresponding
source bundles where required. Imported Exodus data has its own ODbL/DbCL terms.
See [LICENSE](LICENSE), [engine credits](docs/workbench-features.md#license),
[brand artwork](docs/branding/README.md) and the component modules for exact pins.
