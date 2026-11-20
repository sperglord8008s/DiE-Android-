#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/fetch-archive.sh
mode="${1:-android}"
abi="${DIE_ANDROID_ABI:-arm64-v8a}"
profile="$mode"
if [[ "$mode" = android ]]; then
  case "$abi" in arm64-v8a|x86_64) ;; *) echo 'Supported Android archive ABIs: arm64-v8a, x86_64' >&2; exit 1;; esac
  if [[ "$abi" != arm64-v8a ]]; then profile="$mode-$abi"; fi
fi
build="$PWD/app/build/archive-libraries-$profile"
prefix="$build/prefix"
source_root="$PWD/vendor/Archive"
flags=(-DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  -DCMAKE_C_VISIBILITY_PRESET=hidden -DCMAKE_INSTALL_LIBDIR=lib
  "-DCMAKE_C_FLAGS_RELEASE=-O2 -DNDEBUG -ffunction-sections -fdata-sections"
  -DCMAKE_INSTALL_PREFIX="$prefix" -DBUILD_SHARED_LIBS=OFF)
if [[ "$mode" = android ]]; then
  ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/28.2.13676358}"
  flags+=(-DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static)
elif [[ "$mode" != host ]]; then
  echo 'Usage: bash scripts/build-archive-libraries.sh [host|android]' >&2
  exit 1
fi
build_component() {
  local component="$1" source="$2"
  shift 2
  cmake -S "$source" -B "$build/$component" "${flags[@]}" "$@"
  cmake --build "$build/$component" --parallel 2
  cmake --install "$build/$component"
}
build_component bzip2 "$PWD/archive-engine/bzip2" -DBZIP2_SOURCE_ROOT="$source_root/bzip2"
build_component xz "$source_root/xz" -DBUILD_TESTING=OFF -DXZ_THREADS=no -DXZ_NLS=OFF \
  -DXZ_TOOL_XZ=OFF -DXZ_TOOL_XZDEC=OFF -DXZ_TOOL_LZMADEC=OFF -DXZ_TOOL_LZMAINFO=OFF -DXZ_DOC=OFF
build_component lz4 "$source_root/lz4/build/cmake" -DBUILD_STATIC_LIBS=ON -DLZ4_BUILD_CLI=OFF
build_component zstd "$source_root/zstd/build/cmake" -DZSTD_BUILD_STATIC=ON -DZSTD_BUILD_SHARED=OFF \
  -DZSTD_BUILD_PROGRAMS=OFF -DZSTD_BUILD_TESTS=OFF -DZSTD_BUILD_CONTRIB=OFF -DZSTD_MULTITHREAD_SUPPORT=OFF
cmake -S "$source_root/libarchive" -B "$build/libarchive" "${flags[@]}" \
  -DENABLE_TEST=OFF -DENABLE_TAR=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF -DENABLE_UNZIP=OFF -DENABLE_INSTALL=OFF \
  -DENABLE_OPENSSL=OFF -DENABLE_MBEDTLS=OFF -DENABLE_NETTLE=OFF -DENABLE_MD=OFF -DENABLE_CNG=OFF \
  -DENABLE_LIBB2=OFF -DENABLE_LIBXML2=OFF -DENABLE_EXPAT=OFF -DENABLE_BSDXML=OFF -DENABLE_WIN32_XMLLITE=OFF \
  -DENABLE_PCREPOSIX=OFF -DENABLE_PCRE2POSIX=OFF -DENABLE_ACL=OFF -DENABLE_XATTR=OFF -DENABLE_ICONV=OFF \
  -DENABLE_LZO=OFF -DENABLE_ZLIB=ON -DENABLE_BZip2=ON -DENABLE_LZMA=ON -DENABLE_LZ4=ON -DENABLE_ZSTD=ON \
  -DBZIP2_INCLUDE_DIR="$prefix/include" -DBZIP2_LIBRARY_RELEASE="$prefix/lib/libbz2.a" \
  -DLIBLZMA_INCLUDE_DIR="$prefix/include" -DLIBLZMA_LIBRARY="$prefix/lib/liblzma.a" \
  -DLZ4_INCLUDE_DIR="$prefix/include" -DLZ4_LIBRARY="$prefix/lib/liblz4.a" \
  -DZSTD_INCLUDE_DIR="$prefix/include" -DZSTD_LIBRARY="$prefix/lib/libzstd.a"
cmake --build "$build/libarchive" --target archive_static --parallel 2
python3 - "$build" <<'PY'
import json
from pathlib import Path
import sys
root=Path(sys.argv[1])
config=(root/'libarchive/config.h').read_text()
for macro in ['HAVE_LIBZ','HAVE_LIBBZ2','HAVE_LIBLZMA','HAVE_LIBLZ4','HAVE_LIBZSTD']:
    if '#define '+macro+' 1' not in config:
        raise SystemExit('Required compiled codec missing: '+macro)
for archive in ['libbz2.a','liblzma.a','liblz4.a','libzstd.a']:
    if not (root/'prefix/lib'/archive).is_file(): raise SystemExit('Missing built codec '+archive)
print('Pinned libarchive library built with zlib, bzip2, liblzma, LZ4 and Zstandard. Adapter registration/IO must still enforce descriptor-only operation.')
PY
