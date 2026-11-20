# CI host platform

The five-job production/host regression gate uses **Ubuntu 24.04 LTS** explicitly.
It previously followed `ubuntu-latest`; a rollout to Ubuntu 26.04 changed host
media dependencies and a mirror download exceeded the bounded 12-minute package
step in runs `44729667640` and `44729733609`. Pinning a stable host keeps that
platform change separate from application development. The step retains its
network/retry/time limits; no regression test is skipped or weakened.

This controls the Linux host test environment. Production remains Android
ARM64/API 26+, using the existing pinned JDK/Gradle/SDK/NDK/CMake and native
source inputs. Emulator Android versions and ABI are a separate contract.

The original five worker contracts passed on actual x86 Android API 26 and 35
in run `44729667760` at source
`26ad7d6a2b5f0e7f676d4f8373b6dcf9845eebba`. Both downloaded reports, source/APK
identities and artifact digests were independently checked. This does not
verify production ARM64, other engines, SAF/UI, media/PDF or 16 KiB execution.
The host-platform follow-up changes no production worker/native implementation.
