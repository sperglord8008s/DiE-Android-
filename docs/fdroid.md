# F-Droid packaging

GlassDigestor is **not listed in F-Droid yet**. The current numbered GitHub
checkpoints are debug builds. A listing requires a reviewed build recipe, not
an APK upload. Keep the Android ID `org.diemobile`; this work does not change
existing installations or signing configuration.

## Current audit

| Area | Current state / remaining work |
|---|---|
| Public source and licensing | Public Git repository, GPL-3.0-only frontend and pinned upstream licenses/notices/component-source bundles. The complete dependency and artwork audit still needs an F-Droid packager's review. |
| Runtime privacy | No INTERNET permission, telemetry, ads, automatic updates or runtime engine downloads. Optional tracker data is a local user import. |
| Source-built native engines | CDiE/xxfclib, Binwalk adapter/Rust dependencies, PhotoRec, libarchive/codecs and app-owned probes have source build scripts. Their fetch/setup steps need a complete F-Droid recipe. |
| MPV/FFmpeg runtime | **Main build blocker.** `scripts/fetch-mpv.py` copies nine hash-pinned libraries from the official mpv-android APK. Corresponding sources are bundled, but the complete runtime has not been rebuilt here. Checksums/source bundles alone do not turn this into a source build. |
| MPV source preparation | `scripts/prepare-mpv-source-build.py` verifies pinned components/submodules and prepares upstream build scripts. The upstream runtime requires NDK r30, separate from our NDK 28.2 probe/app builds. Build, integrate, license-audit and test that source-built runtime before proposing inclusion. See [MPV build scope](../media-engine/README.md). |
| Java/Maven dependencies | Audit exact origins against F-Droid's allowed repositories and licenses; retain source-built engine adapters and pinned dependency contracts. Trusted-repository exceptions do not automatically cover arbitrary GitHub release binaries. |
| Release variant | Gradle has a release variant, but only the debug checkpoint pipeline is currently acceptance-tested. Compile/lint/inspect the unsigned release APK and all bundled engines; use a stable tag/version code. |
| Signing | Existing CI debug keys can vary. Plan the distribution channel before a public release: F-Droid signing and developer-signed reproducible builds have different update-key requirements. Do not publish private keys or silently change an existing key/application ID. |
| Determinism | Current manifests record source/toolchain/component identity; bit-identical APK reproduction is **not verified**. Audit native build paths, generated data/order/timestamps and SDK/NDK versions. Developer-signed F-Droid publication requires matching rebuilds. |
| Store presentation | Upstream Fastlane descriptions/icon are supplied. Genuine Android screenshots should follow the gallery UI work. Descriptions must distinguish implemented features from device-verified scope. |

Do not hide a prebuilt native engine with `scanignore`, disable an engine just
to make a recipe appear green, or call debug checkpoint hashes proof of
reproducible release builds. Preserve useful functionality while completing
the source build.

## Submission path

1. Complete the MPV source-build integration and dependency/build audit above.
   Produce a tagged unsigned release APK from the documented toolchain, run
   regression/static checks and verify the relevant Android flows.
2. Prepare an `fdroiddata` recipe named `metadata/org.diemobile.yml`, with exact
   source/tag, required source libraries/toolchains and an audited Gradle release
   build. Test in F-Droid's build environment. No working recipe is claimed yet.
3. Run `fdroid readmeta`, `fdroid rewritemeta org.diemobile`,
   `fdroid lint org.diemobile` and `fdroid build org.diemobile`. Resolve the
   scanner/build findings and retain logs. Metadata syntax alone is insufficient.
4. Submit the tested recipe as a merge request to
   [fdroiddata](https://gitlab.com/fdroid/fdroiddata), or request packaging help
   through the tracker linked by the official guide. This repository has not
   submitted an inclusion request on the maintainer's behalf.
5. Respond to packager review and keep future tagged builds/metadata maintained.

An independently hosted F-Droid-compatible repository is another distribution
channel; it does not imply acceptance in the official F-Droid repository.
No repository or signing service has been created here.

## Authoritative references

Reviewed for this preparation on 22 November 2026:

- [Inclusion policy](https://f-droid.org/docs/Inclusion_Policy/): source, licenses,
  allowed binary origins, build tools and privacy requirements.
- [Submission quick start](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/):
  upstream metadata, recipe validation, build and merge-request process.
- [Reproducible builds](https://f-droid.org/docs/Reproducible_Builds/): matching
  rebuilds and developer-signature publication.
- [Descriptions, graphics and screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/):
  upstream presentation formats.
