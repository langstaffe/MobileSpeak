#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../native"
export PATH="$HOME/.cargo/bin:$PATH"
export IPHONEOS_DEPLOYMENT_TARGET=15.0
for target in aarch64-apple-ios aarch64-apple-ios-sim; do
  rustup target add "$target"
  cargo build --locked --release --lib --target "$target"
done
output=../ios/NativeCore/TeamSpeakCore.xcframework
if [ -d "$output" ]; then rm -r "$output"; fi
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libmobilespeak_core.a \
  -library target/aarch64-apple-ios-sim/release/libmobilespeak_core.a \
  -output "$output"
