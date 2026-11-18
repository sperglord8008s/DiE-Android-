# APK signing engine contract

The app links the **unmodified published AOSP apksig 8.9.2** Maven artifact
(Apache-2.0), with a strict Gradle version and build-time SHA-256 check. This
directory retains its binary/source lock and reviewed API/input source identities;
it is not an additional Gradle parser module or a fork of the signing engine.

`scripts/test-apk.sh` verifies both archives, checks the reviewed source headers
and report provenance, and runs actual signed fixtures. The public source JAR is
a development contract reference. `APKSIG_JAR`, `APKSIG_SOURCES_JAR`, `JSON_JAR`
and `DIE_OFFLINE=true` support verified cached test runs. No inspected APK, private
key, user database or provider URI is downloaded by these dependency helpers.
Fixture keys are disposable development data and never packaged into the app.

The adapter guards an upstream result-aggregation omission for failed v4 input,
preserves every returned diagnostic scope and checks `.idsig` top-level allocation
lengths before the engine reads it. Upstream bytecode is unchanged. See
[signing evidence contracts](../docs/apk-signing-investigation.md) for the full
result semantics, lifecycle, budgets, fixtures and remaining device scope.
