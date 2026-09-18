#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../native"
export PATH="$HOME/.cargo/bin:$PATH"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/28.2.13676358}"
if [ ! -d "$ANDROID_NDK_HOME" ]; then echo "Install Android NDK 28.2.13676358 or set ANDROID_NDK_HOME" >&2; exit 1; fi
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export CMAKE_TOOLCHAIN_FILE="$(pwd)/../tool/android.cmake"
for abi in arm64-v8a x86_64; do
  export TS_ANDROID_ABI="$abi"
  case "$abi" in
    arm64-v8a) target=aarch64-linux-android ;;
    x86_64) target=x86_64-linux-android ;;
  esac
  rustup target add "$target"
  case "$(uname -s)" in
    Darwin) host_tag=darwin-x86_64 ;;
    Linux) host_tag=linux-x86_64 ;;
    *) echo "Run this script on macOS or Linux" >&2; exit 1 ;;
  esac
  toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin"
  compiler="$toolchain/${target}24-clang"
  export CC="$compiler" CXX="${compiler}++" AR="$toolchain/llvm-ar"
  linker_key="CARGO_TARGET_$(echo "$target" | tr '[:lower:]-' '[:upper:]_')_LINKER"
  export "$linker_key=$compiler"
  cargo build --locked --release --lib --target "$target"
  mkdir -p "../android/app/src/main/jniLibs/$abi"
  cp "target/$target/release/libmobilespeak_core.so" "../android/app/src/main/jniLibs/$abi/"
done
