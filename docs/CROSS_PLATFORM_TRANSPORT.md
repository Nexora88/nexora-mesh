# Nexora Mesh: Android ↔ iOS offline transport integration

## Scope and honest status

The C ABI shim is implemented in `crates/mesh-core/src/ffi.rs`, and the public header is
`crates/mesh-core/include/nexora_mesh.h`. The shim validates and forwards already-encrypted v1
envelopes. It is **not yet built into Android JNI or an iOS XCFramework**, and it does not implement
E2EE, Bluetooth discovery, or radio IO.

## Shared peer trust flow

1. Each installation creates a long-lived identity key and a short-lived discovery key. The
   discovery ID is a rotating keyed digest; never broadcast the permanent identity/public key.
2. QR code is a versioned text payload containing the public identity key, protocol version,
   and fingerprint. The receiving user scans or manually enters it and compares the fingerprint
   on both devices before saving a trusted contact.
3. Pairing alone is not trust. The first session handshake binds ephemeral ECDH public keys to
   the trusted identity and includes nonces and protocol version. Derive independent directional
   keys with a reviewed KDF; authenticate every record with AEAD. Reject downgrade/replay.
4. Never show “verified” until the QR identity is stored and the live handshake proves possession
   of that identity's private key. Do not allow plaintext fallback.

## Shared radio contract

- BLE GATT service UUID: `7d2a0001-5b6c-4f10-9a7d-4e45584f5241`
- Data characteristic UUID: `7d2a0002-5b6c-4f10-9a7d-4e45584f5241`
- Capability characteristic UUID: `7d2a0003-5b6c-4f10-9a7d-4e45584f5241`
- Advertise only a protocol/service identifier, not a permanent user identifier.
- Each GATT write/notification carries a fragment record: transfer ID (16 bytes), sequence (u16),
  total fragments (u16), payload length (u16), bytes. Validate limits before allocating. Use MTU
  negotiation where available; do not assume an ATT payload size.
- Platform BLE APIs deliver arbitrary connection/fragment events. Reassemble with strict memory
  caps, timeout, duplicate/conflict detection, then pass a complete v1 envelope to
  `nm_wire_validate`/the Rust core. All relay traffic must remain opaque ciphertext.
- Bluetooth LE does not promise 1–2 Mbps end-to-end application throughput. Measure each device,
  handle background suspension, and keep text usable under low throughput.

## Multi-hop

A node may relay only after explicit user opt-in. Check expiry, duplicate ID, max 5 hops, queue
capacity and battery/data policy before storing. Decrement the hop count exactly once per forward.
The target token is routing metadata, not a public-key hash by default. Derive a rotating token
from a secret shared with the intended recipient; account for correlation and false matches.
Do not relay packets for unverified or expired sessions. An in-memory simulation is not proof of
real three-device delivery.

## Android and Apple bridge

- Android: compile Rust for each supported Android ABI, package shared libraries, expose a narrow
  JNI wrapper around the C ABI, and pass complete reassembled envelope bytes only. Do not call
  native code from Bluetooth callbacks before bounds validation.
- iOS: build Rust as an XCFramework/static library, import the C header in a module map/bridging
  header, and call it from Swift after fragment reassembly. CoreBluetooth roles are constrained by
  iOS background policies; validate foreground and background behavior on real devices.
- Wi-Fi Direct is not a common Android/iOS transport. Treat it as Android-only optional transport;
  BLE GATT is the cross-platform baseline.

## Media

Do not invent a “mini-AI codec” or promise arbitrary video compresses to 20–30 KB. Start with
platform codecs (Opus where supported, H.264/HEVC where licensed/supported), encrypted chunked
file transfer, integrity hash, bounded queues, cancellation and resume. Live calling requires
congestion control, jitter buffer, packet-loss handling, adaptive bitrate and echo cancellation.
Bluetooth LE may not provide enough bandwidth for useful video. A/B test Wi-Fi Direct (Android)
or local Wi-Fi when both devices support it; iOS interoperability still needs measurement.

## Required release gates

- Rust unit/property/fuzz tests and C ABI memory-safety review.
- Android JNI library built and loaded on arm64-v8a and x86_64 emulator.
- iOS XCFramework builds in Xcode; no claim of support without signing/build verification.
- Two-phone QR identity verification, tampered key rejection, replay rejection and ciphertext test.
- Three physical devices with A→B→C delivery, B offline queue, TTL expiry, duplicate suppression,
  relay opt-in, reconnect and battery/background tests.
- Android↔iPhone BLE GATT message + encrypted file roundtrip on real devices.
- Separate call-quality testing before voice/video is described as available.
