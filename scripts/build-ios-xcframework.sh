#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${ROOT}/dist/ios"
HEADER="${ROOT}/crates/mesh-core/include"
mkdir -p "${OUT}"

rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios

cargo build --manifest-path "${ROOT}/Cargo.toml" -p mesh-core --release --target aarch64-apple-ios
cargo build --manifest-path "${ROOT}/Cargo.toml" -p mesh-core --release --target aarch64-apple-ios-sim
cargo build --manifest-path "${ROOT}/Cargo.toml" -p mesh-core --release --target x86_64-apple-ios

mkdir -p "${ROOT}/target/ios-simulator-universal"
lipo -create \
  "${ROOT}/target/aarch64-apple-ios-sim/release/libmesh_core.a" \
  "${ROOT}/target/x86_64-apple-ios/release/libmesh_core.a" \
  -output "${ROOT}/target/ios-simulator-universal/libmesh_core.a"

xcodebuild -create-xcframework \
  -library "${ROOT}/target/aarch64-apple-ios/release/libmesh_core.a" -headers "${HEADER}" \
  -library "${ROOT}/target/ios-simulator-universal/libmesh_core.a" -headers "${HEADER}" \
  -output "${OUT}/MeshCore.xcframework"

echo "Created ${OUT}/MeshCore.xcframework"
echo "Add the framework to an Xcode target and import nexora_mesh.h. This script does not create or sign an iOS app."
