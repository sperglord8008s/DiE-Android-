#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${DIE_RUST_ROOT:-}" ]]; then export PATH="$DIE_RUST_ROOT/bin:$PATH"; fi
metadata=$(mktemp)
trap 'rm -f "$metadata"' EXIT
(
  cd binary-engine
  cargo metadata --locked --format-version 1 --filter-platform aarch64-linux-android > "$metadata"
)
python3 scripts/binary-notices.py "$metadata" "$(rustc --print sysroot)" --check
