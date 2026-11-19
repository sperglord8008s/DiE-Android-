#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Source from the repository root. DEX_DEPS_DIR supports verified offline reruns.
source scripts/fetch-verified.sh
load_dex_test_dependencies() {
  local directory="${DEX_DEPS_DIR:-$1/dex-dependencies}"
  mkdir -p "$directory"
  dex_test_classpath=""
  while read -r checksum url; do
    local jar="$directory/${url##*/}"
    fetch_verified_dependency "$jar" "$checksum" "$url" || return 1
    dex_test_classpath="${dex_test_classpath:+$dex_test_classpath:}$jar"
  done < scripts/dex-dependencies.sha256
}
