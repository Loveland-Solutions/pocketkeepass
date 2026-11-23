#!/bin/sh
# Builds the Rust engine (../core) for iOS and macOS, generates its Swift bindings, and packages
# both as the PocketKeePassCore Swift package that the iOS and macOS apps use:
#
#   PocketKeePassCore/PocketKeePassCoreFFI.xcframework   the static libraries and the C header
#   PocketKeePassCore/Sources/PocketKeePassCore/          the generated Swift side of the bridge
#
# Both are build output: git-ignored, never edited. Xcode runs this before compiling (see the
# apps' "Build engine" phase); it can also be run by hand. Needs Rust with the Apple targets:
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim aarch64-apple-darwin x86_64-apple-darwin
set -eu

here=$(cd "$(dirname "$0")" && pwd)
core=$(cd "$here/../core" && pwd)
package="$here/PocketKeePassCore"
target_dir="$core/target"

# Xcode doesn't pass on the shell's PATH, so look for cargo where rustup puts it.
export PATH="${CARGO_HOME:-$HOME/.cargo}/bin:/opt/homebrew/bin:$PATH"
# Xcode sets these for the app it's building; they'd leak into cargo's own builds.
unset SDKROOT IPHONEOS_DEPLOYMENT_TARGET MACOSX_DEPLOYMENT_TARGET 2>/dev/null || true

export IPHONEOS_DEPLOYMENT_TARGET=26.0
export MACOSX_DEPLOYMENT_TARGET=15.0

# Rust keeps source paths in the library (for panic messages), and they start with the home
# folder, whose name is the developer's account name. Rewritten to neutral ones, as the Android
# build does. rustc applies the last match, so the broadest (home) goes first. One flag per
# entry, separated by 0x1F, so paths with spaces stay whole.
us=$(printf '\037')
CARGO_ENCODED_RUSTFLAGS="--remap-path-prefix=$HOME=~${us}--remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=/cargo${us}--remap-path-prefix=${RUSTUP_HOME:-$HOME/.rustup}=/rustup${us}--remap-path-prefix=$core=/src"
export CARGO_ENCODED_RUSTFLAGS

build() {
    # A static library only for Apple, without touching Cargo.toml's crate types (Android
    # needs the cdylib, and the tests the rlib).
    cargo rustc --manifest-path "$core/Cargo.toml" --lib --release --locked \
        --target "$1" --target-dir "$target_dir" --crate-type staticlib
}

for target in aarch64-apple-ios aarch64-apple-ios-sim aarch64-apple-darwin x86_64-apple-darwin; do
    build "$target"
done

# The bindings come from the compiled library's metadata, built for this Mac. This library is
# linked, and it's the one the Android app's Kotlin tests load (core/target/release), so it's built
# with none of Xcode's environment: linked inside Xcode's build, it came out malformed ("mis-aligned
# LINKEDIT string pool") and refused to load.
unset CARGO_ENCODED_RUSTFLAGS
env -i HOME="$HOME" PATH="$PATH" ${CARGO_HOME:+CARGO_HOME="$CARGO_HOME"} ${RUSTUP_HOME:+RUSTUP_HOME="$RUSTUP_HOME"} \
    cargo build --manifest-path "$core/Cargo.toml" --release --locked --target-dir "$target_dir" \
    --features bindgen
generated="$target_dir/swift-bindings"
rm -rf "$generated"
# It finds uniffi.toml through cargo metadata, so it runs from the crate's folder.
(cd "$core" && "$target_dir/release/uniffi-bindgen" generate --language swift --no-format \
    --out-dir "$generated" "$target_dir/release/libpocketkeepass_core.dylib")

headers="$target_dir/swift-headers"
rm -rf "$headers"
mkdir -p "$headers"
cp "$generated/PocketKeePassCoreFFI.h" "$headers/"
# An XCFramework's headers need the module map under this exact name.
cp "$generated/PocketKeePassCoreFFI.modulemap" "$headers/module.modulemap"

# One library per platform: the Mac's two architectures go into one file.
mac="$target_dir/apple-macos/libpocketkeepass_core.a"
mkdir -p "$(dirname "$mac")"
lipo -create -output "$mac" \
    "$target_dir/aarch64-apple-darwin/release/libpocketkeepass_core.a" \
    "$target_dir/x86_64-apple-darwin/release/libpocketkeepass_core.a"

xcframework="$package/PocketKeePassCoreFFI.xcframework"
rm -rf "$xcframework"
xcodebuild -create-xcframework \
    -library "$target_dir/aarch64-apple-ios/release/libpocketkeepass_core.a" -headers "$headers" \
    -library "$target_dir/aarch64-apple-ios-sim/release/libpocketkeepass_core.a" -headers "$headers" \
    -library "$mac" -headers "$headers" \
    -output "$xcframework" >/dev/null

sources="$package/Sources/PocketKeePassCore"
mkdir -p "$sources"
cp "$generated/PocketKeePassCore.swift" "$sources/PocketKeePassCore.swift"
echo "PocketKeePassCore: engine and Swift bindings are up to date."
