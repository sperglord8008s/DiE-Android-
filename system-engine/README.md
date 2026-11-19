# Android system observation contracts

`binder-service-contract.json` pins the unmodified AOSP Android 15 `service.cpp`
reference bundled under `app/src/main/assets/references/`, with its full source
notice and Apache-2.0 license under assets/notices. It is an offline technical
reference; the app does not compile or ship the OS executable. Runtime capture
uses only the fixed existing `/system/bin/service list` command as the app UID.
The actual installed executable's source identity is not verified.

`BoundedProcessOutput` owns process/pipe capture; `BinderServiceCapture` supplies
the fixed command and Android context; `BinderRegistry` separates observed
registration syntax from diagnostics; `BinderRegistryBackend` supplies exact
owned bytes, typed evidence and exports. The common viewer handles navigation.
No caller-supplied executable/arguments, sample execution, arbitrary service
transactions, network or privileged bridge is exposed. The fixed platform query
performs its documented list/lookup/interface-descriptor calls.

Run `bash scripts/test-system-services.sh`. Gradle and APK verification also gate
the reference bytes/notices. Read [the full evidence and lifecycle contract](../docs/binder-registry.md)
for limits and the distinction between host fixtures and actual Android runtime.
