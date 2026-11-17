#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
cache="${METADATA_DEPS_DIR:-$PWD/vendor/metadata-downloads}"
mkdir -p "$cache"
fetch() {
  local name="$1" digest="$2" url="$3"
  if [[ ! -f "$cache/$name" ]]; then curl --fail --location --silent --show-error --max-filesize 16777216 "$url" -o "$cache/$name.download"; mv "$cache/$name.download" "$cache/$name"; fi
  if [[ "$digest" != payload-manifest ]]; then printf '%s  %s\n' "$digest" "$cache/$name" | sha256sum -c -; fi
}
fetch metadata-extractor-2.22.0-sources.jar db4027ec3925959ecd26933ec1fbf4e48e01ad2b338dded702e1a6e62226cccc https://repo.maven.apache.org/maven2/com/drewnoakes/metadata-extractor/2.22.0/metadata-extractor-2.22.0-sources.jar
# Gitiles may regenerate gzip/tar headers differently on different servers.
# prepare-metadata.py verifies the complete compiled-source + license payload set.
fetch aosp-xmp.tar.gz payload-manifest https://android.googlesource.com/platform/external/xmp_toolkit/+archive/c60127d527f8438b89f9e292200639ec14d1c3cf.tar.gz
python3 scripts/prepare-metadata.py "$cache"
patch --batch --forward -p1 -d vendor/metadata-engine < patches/metadata-engine.patch
printf '%s\n' 'metadata-extractor=2.22.0' 'xmp-aosp=c60127d527f8438b89f9e292200639ec14d1c3cf' 'adapter=1' > vendor/metadata-engine/revision.txt
