#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/fetch-binwalk.sh
mode="${1:-android}"
if [[ -n "${DIE_RUST_ROOT:-}" ]]; then export PATH="$DIE_RUST_ROOT/bin:$PATH"; fi
if ! command -v cargo >/dev/null || ! command -v rustc >/dev/null; then
  echo 'Install Rust 1.90.0 with the aarch64-linux-android standard library (rustup toolchain install 1.90.0 --profile minimal; rustup target add --toolchain 1.90.0 aarch64-linux-android).' >&2
  exit 1
fi
export CARGO_TARGET_DIR="${BINARY_BUILD_DIR:-$PWD/app/build/binwalk-rust}"
cd binary-engine
if [[ "$(rustc --version)" != 'rustc 1.90.0 '* ]]; then echo 'The binary engine is pinned to Rust 1.90.0.' >&2; exit 1; fi
if [[ "$mode" = host ]]; then
  cargo build --locked --release --lib
  mkdir -p ../app/build/host-binwalk
  cp "$CARGO_TARGET_DIR/release/libdiebinwalk.so" ../app/build/host-binwalk/
elif [[ "$mode" = android ]]; then
  sdk="${ANDROID_HOME:?Set ANDROID_HOME}"
  ndk="${ANDROID_NDK_HOME:-$sdk/ndk/28.2.13676358}"
  llvm="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$llvm/aarch64-linux-android26-clang"
  export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
  export AR_aarch64_linux_android="$llvm/llvm-ar"
  export RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384'
  cargo build --locked --release --lib --target aarch64-linux-android
  mkdir -p ../app/build/generated-binwalk-jni/arm64-v8a
  cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/libdiebinwalk.so" ../app/build/generated-binwalk-jni/arm64-v8a/
else echo 'Usage: bash scripts/build-binwalk.sh [android|host]' >&2; exit 1; fi
