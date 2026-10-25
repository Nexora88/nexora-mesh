# Nexora Mesh

**An offline-first, nearby-device messaging app for Android.** No account, cloud relay, or internet connection is part of the intended message path.

> Early prototype. This repository does not yet provide real device-to-device message delivery or production-grade encryption. The current Android screen is a local UI prototype only.

## Product direction

Nexora Mesh aims to feel like a modern messenger while working without internet:
- Android-native chat UI built with Kotlin and Jetpack Compose.
- Nearby connectivity beginning with Bluetooth and Wi-Fi Direct.
- Store-and-forward routing through participating devices as a later milestone.
- A Rust protocol core for bounded message framing and transport-independent message types.
- Local-first message history; no cloud account or central messaging server.

## Current status

- [x] Public repository and project structure
- [x] Android app shell and local chat UI prototype
- [x] Rust workspace scaffold
- [ ] Android build verified on CI/device
- [ ] Bluetooth/Wi-Fi Direct discovery and connection
- [ ] Real device-to-device text delivery
- [ ] Reviewed end-to-end encryption and identity verification
- [ ] Multi-hop routing, attachments, voice/video

Do not describe unfinished items as implemented. The local UI prototype is not yet a working offline messenger.

## Architecture

- `android/` — Android application (Kotlin, Jetpack Compose)
- `crates/mesh-core/` — Rust protocol core
- `docs/PROTOCOL.md` — protocol design notes
- `docs/THREAT_MODEL.md` — security boundaries and known risks
- `tools/` — small Python reference utilities and tests

## Offline design rule

The message path must not require an internet server. Bluetooth and Wi-Fi Direct can establish local links without internet, but availability, permissions, range, battery use, and compatibility vary by device. Multi-hop routing requires participating relay devices and is not implemented yet.

## Build the Android app

Open the `android/` directory in Android Studio and let Gradle sync. Use a recent stable Android Studio with a compatible Android SDK. A successful local build and physical-device test are still required before calling this app usable.

## Development principles

1. No homemade cryptography.
2. No claims of anonymity or untraceability.
3. Encrypt messages end-to-end before real private messaging is enabled.
4. Validate all incoming frames and cap message sizes.
5. Keep internet permissions out of the Android manifest unless a documented, necessary feature requires them.
6. Test with at least two physical devices; emulator-only success is not proof of radio connectivity.

See [the protocol notes](docs/PROTOCOL.md), [threat model](docs/THREAT_MODEL.md), and [security policy](SECURITY.md).
