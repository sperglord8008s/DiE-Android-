# Package-set declaration contract

This adapter composes the existing pinned **Google binary-resources 32.5.1**
parser/wire model and **AOSP apksig 8.9.2** results with owned whole-APK snapshots.
It adds no alternate APK parser and never installs or executes inspected code.

`source-lock.json` identifies reviewed AOSP Android 15 declaration/dependency
knowledge and public SDK 35 attribute IDs. The referenced Apache-2.0 platform
classes are not bundled, modified or executed. `scripts/check-package-contract.py`
checks retained provenance and selector IDs without downloading anything.
An optional directory argument also SHA-256 verifies independently retrieved
original reference files named by the lock.

Actual engine licenses/source locks remain in `resource-engine` and
`signing-engine`; their existing bundled notices are preserved. See
[package-set contracts](../docs/package-set-investigation.md) for acquisition,
observed relationships, exact metadata/byte exports, budgets, fixtures and the
distinction between declaration evidence and Android platform decisions.
