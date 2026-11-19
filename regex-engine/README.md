# Source-built RE2/J

All 19 Java sources published as `com.google.re2j:re2j:1.8:sources` are retained
unmodified in `third_party/re2j` and compiled with `--release 8`. There is no
prebuilt RE2/J runtime JAR or transitive runtime dependency. Gradle verifies the
complete source inventory before compilation.

- Published source JAR SHA-256:
  `4378db846db2d91c8028fe32efcb183a1f1b4157339d0a3b4c46b2c6b0655d7c`.
- Upstream tag/source: `google/re2j` `re2j-1.8`, commit
  `57278921a609461c14d9cdb057d7aa9511c8f7ac`.
- License: Go Authors' BSD-3-Clause. Full original `LICENSE`, every source file's
  copyright/header variant and exact per-file identities are retained in this
  module, `source-lock.json` and the APK's `notices/re2j-1.8.txt`.

```sh
python3 scripts/check-regex-sources.py
bash scripts/fetch-knowledge.sh
bash scripts/test-knowledge.sh
```

RE2/J uses a linear-time regular-expression implementation instead of recursive
backtracking. The app's `NameMatcher` adapter implements the pinned LibChecker
portable v5 matching contract: exact equality across every row first, then the
first whole-string regex by `(priority,id)`. It lexically normalizes `\d` to
ASCII digit ranges without changing escaped backslashes, rejects regex inputs
containing any of five line terminators and reports unsupported patterns. Case
folding and Unicode normalization are absent. Every one of the pinned catalog's
123 regex rules compiles successfully; all 19 shared upstream cases pass.

The engine is general-purpose, while this adapter deliberately restricts its
syntax to the upstream portable subset. This does not claim compatibility with
all ICU/Java/PCRE syntax or complete Exodus regex matching. Existing Exodus
imports still use the separately documented limited literal matcher.

The app adapters are GPL-3.0-only. See
[SDK contracts](../docs/sdk-investigation.md) for canonical data compilation,
runtime evidence, source ownership, budgets, tests and remaining limits.
