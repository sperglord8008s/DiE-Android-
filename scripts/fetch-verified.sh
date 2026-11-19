#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Source from host checks. Never publish partial downloads or replace edited cache bytes.
fetch_verified_dependency() {
  local destination="$1" checksum="$2" url="$3" stage
  if [[ ! "$checksum" =~ ^[0-9a-f]{64}$ ]]; then echo 'Invalid pinned dependency checksum.' >&2; return 1; fi
  if [[ -e "$destination" || -L "$destination" ]]; then
    printf '%s  %s\n' "$checksum" "$destination" | sha256sum -c -
    return
  fi
  if [[ "${DIE_OFFLINE:-false}" = true ]]; then echo "Offline dependency missing: $destination" >&2; return 1; fi
  mkdir -p "$(dirname "$destination")" || return 1
  stage=$(mktemp "${destination}.download.XXXXXXXX") || return 1
  if ! curl --fail --location --silent --show-error --retry 6 --retry-max-time 120 \
      --connect-timeout 15 --max-time 45 --max-filesize 16777216 "$url" -o "$stage"; then
    rm -f "$stage"; return 1
  fi
  if ! printf '%s  %s\n' "$checksum" "$stage" | sha256sum -c -; then
    rm -f "$stage"; return 1
  fi
  if ! mv -f "$stage" "$destination"; then rm -f "$stage"; return 1; fi
}
