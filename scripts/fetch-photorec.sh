#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/fetch-verified.sh
cache="${PHOTOREC_DEPS_DIR:-$PWD/.ci-cache/photorec}"
archive="$cache/testdisk-7.2.tar.gz"
fetch_verified_dependency "$archive" 0cfd82ca6890188a3a4fe01c9b970f654eb3af396580f408ed024789e2873616 \
  https://codeload.github.com/cgsecurity/testdisk/tar.gz/281be432dd79121e08e0898887a9ee1f30fb3e96
python3 scripts/prepare-photorec.py "$archive"
