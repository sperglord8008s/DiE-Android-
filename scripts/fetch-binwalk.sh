#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
revision=26713972e3f9f52dc37d4a421c4554b0bd9e82ef
checkout=vendor/Binwalk
if [[ ! -d "$checkout/.git" ]]; then
  mkdir -p "$checkout"
  git -C "$checkout" init -q
  git -C "$checkout" remote add origin https://github.com/ReFirmLabs/binwalk.git
fi
if [[ -n "$(git -C "$checkout" status --porcelain)" ]]; then
  echo 'Binwalk source contains local changes; preserve or move them before fetching the pinned engine.' >&2
  exit 1
fi
if [[ "$(git -C "$checkout" rev-parse HEAD 2>/dev/null || true)" != "$revision" ]]; then
  git -C "$checkout" fetch --depth 1 origin "$revision"
  git -C "$checkout" checkout --detach "$revision"
fi
test "$(git -C "$checkout" rev-parse HEAD)" = "$revision"
