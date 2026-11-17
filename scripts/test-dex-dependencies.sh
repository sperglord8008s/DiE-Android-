#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Source from the repository root. DEX_DEPS_DIR supports verified offline reruns.
load_dex_test_dependencies() {
  local directory="${DEX_DEPS_DIR:-$1/dex-dependencies}"
  mkdir -p "$directory"
  dex_test_classpath=""
  while read -r checksum url; do
    local jar="$directory/${url##*/}"
    if [[ ! -f "$jar" ]]; then curl -fLsS --retry 2 "$url" -o "$jar"; fi
    echo "$checksum  $jar" | sha256sum -c -
    dex_test_classpath="${dex_test_classpath:+$dex_test_classpath:}$jar"
  done < scripts/dex-dependencies.sha256
}
