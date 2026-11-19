# Build and checkpoint versions

`version.properties` is the reviewed version source. Published checkpoints use
`major.minor.patch-pre-alpha.sequence` (currently `0.6.0-pre-alpha.1`). Increase
the sequence and Android `version.code` for each newly published checkpoint.
The numeric code must always exceed the previous published code; the version
name itself does not determine Android update ordering.

Debug builds append `-dev.g<12-character Git revision>` and, when applicable,
`.dirty`. A source tree without Git metadata uses `-dev.unknown` and cannot
produce a clean verified checkpoint. The identifier is stable for the same
source state; it does not depend on a clock, a shallow-clone commit count or a
GitHub run number. This is an identity convention, not a promise of identical
APK bytes or signing keys.

For example, a clean debug build produces
`GlassDigestor-0.6.0-pre-alpha.1-dev.g0123456789ab-arm64-v8a-debug.apk`.
The installed version name includes the same suffix. Tap the main title or
open **Options → Build details** to inspect the version code, build variant,
full source revision and source status.

`scripts/build-version.py` supplies the same contract to Gradle, APK verification
and checkpoint packaging. `scripts/verify-apk.sh` and `scripts/package-alpha.py`
resolve the single actual APK through Android Gradle Plugin output metadata;
they no longer depend on `app-debug.apk`. Verification rejects an APK built
from a different version/source state. Checkpoints require a clean committed
tree and retain the filename, SHA-256, source/engine manifest and metadata.

The application ID and signing configuration are unchanged. CI uses Android's
debug signer; independently created runners can have different certificates.
Check the public signer digest in each build manifest before replacing an
existing installation. A GitHub pre-release distributes this explicitly labeled
debug APK for testing; it is not a production-key release or evidence of device
acceptance. Keep exported reports and the prior APK when testing updates.
