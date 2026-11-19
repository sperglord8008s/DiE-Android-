#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
cache="${RESOURCE_DEPS_DIR:-$PWD/vendor/resource-downloads}"
mkdir -p "$cache"
name=binary-resources-32.5.1-sources.jar
if [[ ! -f "$cache/$name" ]]; then
  if [[ "${DIE_OFFLINE:-false}" = true ]]; then echo 'Offline resource build needs the pinned source JAR in RESOURCE_DEPS_DIR.' >&2; exit 1; fi
  curl --fail --location --silent --show-error --max-filesize 1048576 \
    https://dl.google.com/dl/android/maven2/com/android/tools/apkparser/binary-resources/32.5.1/binary-resources-32.5.1-sources.jar -o "$cache/$name.download"
  mv "$cache/$name.download" "$cache/$name"
fi
printf '%s  %s\n' 5f8079eb2d2535b4d8bdc4782022287b52a40dce852fc942fca2adc12f78ccdc "$cache/$name" | sha256sum -c -
python3 scripts/prepare-resources.py "$cache/$name"
