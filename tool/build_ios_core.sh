#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../native"
python3 ../tool/prepare_audio_runtime.py ios
export PATH="$HOME/.cargo/bin:$PATH"
python3 ../tool/build_sherpa_fft.py ios
export IPHONEOS_DEPLOYMENT_TARGET=15.0
core_build_args=(--locked --release --lib)
if [ "${MOBILESPEAK_NETWORK_DIAGNOSTICS:-0}" = 1 ]; then
  core_build_args+=(--features network-diagnostics)
fi
for target in aarch64-apple-ios aarch64-apple-ios-sim; do
  rustup target add "$target"
  cargo build "${core_build_args[@]}" --target "$target"
done
output=../ios/NativeCore/TeamSpeakCore.xcframework
if [ -d "$output" ]; then rm -r "$output"; fi
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libmobilespeak_core.a \
  -library target/aarch64-apple-ios-sim/release/libmobilespeak_core.a \
  -output "$output"
