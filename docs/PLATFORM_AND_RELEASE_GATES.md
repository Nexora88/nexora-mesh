# Platform matrix and release gates

This document separates source-level implementation from verified end-to-end behavior.

## Intended experience

- Android and Apple devices use their native Bluetooth APIs for nearby radio access.
- A desktop installable web app can provide a polished management surface, QR/contact management, diagnostics, and documentation. It must not claim to relay phone radio traffic unless a supported native bridge is actually connected.
- Web Bluetooth is not a cross-browser substitute for native CoreBluetooth: support differs by browser and platform, and iOS Safari does not provide the same general Web Bluetooth GATT client path as Chromium desktop/Android. Therefore a single PWA cannot honestly guarantee Android↔iPhone, iPhone↔iPhone, and Android↔Android radio messaging on its own.
- A future desktop shell (for example, Tauri) can expose local radio drivers, but it needs its own platform-specific driver and signing/release work.

## Current source-level work in this branch

- Android BLE GATT and iOS CoreBluetooth transport prototypes exist.
- iOS can display its identity QR and now includes a camera-based QR scanner on supported devices, with manual paste as fallback.
- iOS XCTest coverage now targets the shared v1 fingerprint/KDF vectors, AES-GCM tampering, wrong associated data, malformed nonce, malformed packet handling, and message-ID replay guard.
- Rust relay primitives include hop limits, expiry, deduplication, bounded queues, and an explicit opt-in switch that is disabled by default.
- Android unit tests cover persistent replay-window and pending-delivery behavior.

## Release gates that remain open

1. **Native cross-platform cryptography:** the shared vectors must run inside Android and Swift test targets. The current v1 ECDH + HMAC-derived AES-GCM protocol is experimental, has no forward secrecy, and is not a replacement for a reviewed Noise/Signal implementation.
2. **Radio integration:** demonstrate real BLE send/receive on physical Android and Apple hardware. Rust framing/relay helpers are not yet a production live multi-hop transport.
3. **A→B→C mesh:** wire the relay queue to radio ingress/egress, blind target-token derivation, duplicate persistence, TTL decrement, expiry, opt-in controls, battery limits, and end-to-end delivery receipts. Current Rust tests simulate routing in memory only.
4. **Three-device physical matrix:** two Android phones and one iPhone, with QR trust, both-way messaging, ACK loss, process restart, full queue, malformed/replayed packets, disconnect/reconnect, and OS background/foreground behavior. Capture model, OS, build SHA, expected and observed results.
5. **Independent security review:** review the threat model, key agreement, identity binding, nonce handling, replay windows, metadata leakage, queue persistence, and downgrade behavior before claiming production security.
6. **Desktop/PWA:** decide whether this is a management-only PWA or a native desktop messenger with local radio drivers. A management PWA can be installable and polished, but must not pretend to access phone Bluetooth radios on unsupported platforms.
7. **Release artifacts:** sign Android builds, establish Apple signing/TestFlight distribution, package any desktop build, and document installation/update/revocation. CI debug artifacts are for testing, not production distribution.

## Do not claim yet

- “Perfect”, “unhackable”, anonymous, emergency-ready, forward-secret, production-secure, or guaranteed delivery.
- Cross-platform physical interoperability based on CI builds alone.
- Multi-hop radio delivery based on the Rust in-memory routing test.
