#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build-archive.sh host
if nm -D app/build/host-archive/libdiearchive.so | awk '{print $NF}' | sed 's/@.*//' | rg -x '(fork|vfork|execve|execvp|posix_spawn|popen|system|open|open64|fopen|opendir|readlink)'; then
  echo 'Archive adapter unexpectedly links program or path-based IO' >&2
  exit 1
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
python3 tests/generate-archive-fixtures.py "$tmp"
cc -O2 -Ivendor/Archive/libarchive/libarchive tests/archive_header_position_oracle.c \
  app/build/archive-libraries-host/libarchive/libarchive/libarchive.a \
  app/build/archive-libraries-host/prefix/lib/{libbz2,liblzma,liblz4,libzstd}.a -lz -lm -o "$tmp/header-position-oracle"
"$tmp/header-position-oracle" "$tmp/upstream-test_read_format_cab_3.cab" > "$tmp/cab-position-oracle.json"
source scripts/test-dependencies.sh
load_json_test_dependency "$tmp"
javac -cp "$json_test_jar" -d "$tmp/classes" app/src/main/java/org/diemobile/ArchiveEngine.java tests/ArchiveEngineTest.java
java --add-opens=java.base/java.io=ALL-UNNAMED -Djava.library.path="$PWD/app/build/host-archive" \
  -cp "$tmp/classes:$json_test_jar" org.diemobile.ArchiveEngineTest "$tmp"
