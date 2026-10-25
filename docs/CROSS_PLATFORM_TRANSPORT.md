# Nexora Mesh: Android ↔ iOS offline transport integration

## Scope and honest status

The C ABI shim is implemented in `crates/mesh-core/src/ffi.rs`, with the public header at
`crates/mesh-core/include/nexora_mesh.h`. Android JNI/CMake wiring and an iOS XCFramework build
script are present; Android BLE GATT and iOS CoreBluetooth transport source are present. CI builds
are the first validation layer, not proof of a signed, installable iOS app or physical-device
interoperability. Android now routes the existing QR-trust and AES-GCM wire protocol through the BLE packet boundary, including GATT notifications for the return direction. This is an experimental integration, not an audited secure messenger. The iOS app still does not implement the matching QR trust store, compatible identity/key agreement, encrypted session handshake, or live chat UI; iOS BLE remains transport-only. Android Bluetooth Classic remains available as the existing chat path.

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
- Each GATT write carries a fragment record: transfer ID (16 bytes), sequence (u16), total fragments
  (u16), payload length (u16), then bytes. The current Android and iOS sources use this 22-byte
  header and cap a complete envelope at 256 KiB with at most four incomplete assemblies. These
  implementations still need expiry/cleanup for stalled assemblies, stress tests, and device-level
  interoperability checks. Use MTU negotiation where available; do not assume an ATT payload size.
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

- Android: CI compiles Rust shared libraries for `arm64-v8a` and `x86_64`, packages them, and builds
  the JNI bridge through CMake. BLE GATT reassembles bounded packets before the caller validates
  them. The APK build is not a substitute for running on phones.
- iOS: `ios/project.yml` defines the app target and CI generates an Xcode project with XcodeGen,
  then attempts a simulator build without code signing. The Rust XCFramework is built for device
  and simulator architectures. A green simulator build validates compilation only: it does not
  establish a signed/release-ready app, QR trust integration, compatible encrypted sessions, or
  physical-device interoperability. CoreBluetooth background behavior remains unverified.
- Wi-Fi Direct is not a common Android/iOS transport. Treat it as Android-only optional transport;
  BLE GATT is the cross-platform baseline.

## Media

Do not invent a “mini-AI codec” or promise arbitrary video compresses to 20–30 KB. Start with
platform codecs (Opus where supported, H.264/HEVC where licensed/supported), encrypted chunked
file transfer, integrity hash, bounded queues, cancellation and resume. Live calling requires
congestion control, jitter buffer, packet-loss handling, adaptive bitrate and echo cancellation.
Bluetooth LE may not provide enough bandwidth for useful video. A/B test Wi-Fi Direct (Android)
or local Wi-Fi when both devices support it; iOS interoperability still needs measurement.

## Current implementation gaps

- Android BLE now enters the existing QR-trusted-contact gate and encrypted chat wire protocol; text messages appear in the existing Android chat UI. Both GATT central writes and peripheral notifications are implemented in source, but the integration is not yet proven on physical devices.
- The Android session still uses static P-256 ECDH identity keys and a custom HMAC-derived AES-GCM key. It does not provide forward secrecy, directional keys, a reviewed standard session protocol, or an independent cryptographic audit. Do not describe it as production-grade E2EE.
- Android suppresses duplicate message IDs only in the current in-memory session (bounded cache); replay IDs are not durable across process restarts. Delivery ACKs, retry/resume, durable offline queue, and exactly-once semantics are not implemented. A successful GATT write is not proof that the recipient accepted the message.
- iOS BLE now has notification-based return transport, but its UI, QR identity, compatible cryptographic handshake, and chat-message path are not connected. Android↔iOS secure chat is therefore not implemented yet.
- No physical Android↔iPhone roundtrip or three-device A→B→C test has been performed by CI.
- Voice/video calls and a custom media codec are not implemented.

## Required release gates

- Rust unit/property/fuzz tests and C ABI memory-safety review.
- Android JNI library built and loaded on arm64-v8a and x86_64 emulator.
- iOS XCFramework builds in Xcode; no claim of support without signing/build verification.
- Two-phone QR identity verification, tampered key rejection, replay rejection and ciphertext test.
- Three physical devices with A→B→C delivery, B offline queue, TTL expiry, duplicate suppression,
  relay opt-in, reconnect and battery/background tests.
- Android↔iPhone BLE GATT message + encrypted file roundtrip on real devices.
- Separate call-quality testing before voice/video is described as available.
