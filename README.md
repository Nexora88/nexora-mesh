# Nexora Mesh

**An offline-first messenger project for Android and Apple platforms, designed with resilient communication in mind.** The long-term goal is a trustworthy messenger that can help people communicate when connectivity is disrupted by wildfires, earthquakes, floods, or mountain search-and-rescue operations.

> **Honest status:** Android Bluetooth Classic chat remains the current live chat path. This branch adds a Rust C ABI, Android JNI/CMake bridge, Android BLE GATT discovery/connection prototype, iOS CoreBluetooth GATT source, a Swift C ABI wrapper, and an XCFramework build script. BLE is not yet wired end-to-end to the QR-gated encrypted chat protocol. Android CI for the new native bridge is still running; the iOS sources have not been built in Xcode here. No physical Android–iPhone or three-device relay tests have been performed. Audio/video calls and a custom codec are not implemented. This is not ready for emergency use.

## Product vision

- **Messenger experience:** chat list, trusted contacts, message delivery states, and later file/audio/video calling.
- **Offline communication:** direct Bluetooth links without an internet server.
- **Disaster resilience:** support teams when infrastructure is unavailable, with field testing and clear limitations.
- **Android + Apple:** Android radio transport first, plus a native iOS interface and a protocol designed for cross-platform interoperability.
- **Range extension in stages:** direct connection first, then opt-in store-and-forward and multi-hop mesh relaying.

## Android direct-link prototype

The first transport uses Bluetooth Classic RFCOMM. The user starts device discovery; an unknown device is not automatically trusted. The user must select a device and accept Android's system pairing flow on both phones. After pairing, one phone listens for an incoming connection and the other initiates the connection. The connection can carry text while internet access is off.

See [Android two-device test instructions](docs/ANDROID_BLUETOOTH_TEST.md).

**Important boundaries:** this is one direct connection at a time. Android system bonding is the initial trust gate, not a substitute for an app-level identity-verification protocol. App-level QR/code verification, reviewed end-to-end encryption, durable message storage, delivery receipts, Apple interoperability, and mesh relaying are still future work. CI compilation does not prove that physical-device Bluetooth behavior works.

## Discovery and trust

Privacy should be the default. Discovery is user-triggered rather than continuous. Discovering a nearby device must not automatically add it as a trusted contact. Pairing should require approval from both sides and, in a later milestone, a verification code or QR fingerprint. A Bluetooth name or address is not a cryptographic identity.

## Current status

- [x] Public repository and Rust workspace
- [x] Android Jetpack Compose UI
- [x] SwiftUI interface starter for Apple platforms
- [x] Rust frame codec and tests
- [x] Android Bluetooth discovery and system-bonding flow implemented in source (physical testing pending)
- [x] Direct RFCOMM text transport implemented in source (physical testing pending)
- [x] Product vision, threat model, roadmap, and two-device test instructions
- [ ] Android CI build and physical-device installation verified
- [ ] Successful two-way message exchange on two physical Android devices with internet disabled
- [ ] App-level identity verification and reviewed end-to-end encryption
- [ ] Persistent chat history and reliable delivery states
- [ ] Xcode project and physical iOS build
- [ ] Opt-in mesh relaying and field range tests
- [ ] File, audio, and video calls

Do not describe unverified source code as a working tested feature.

## Repository layout

- android/ — Android app and Bluetooth transport
- ios/ — SwiftUI interface starter and Apple platform notes
- crates/mesh-core/ — Rust protocol core
- docs/PRODUCT_VISION.md — product vision and privacy rules
- docs/PROTOCOL.md — protocol notes
- docs/THREAT_MODEL.md — security boundaries and risks
- docs/ROADMAP.md — development milestones
- docs/ANDROID_BLUETOOTH_TEST.md — physical two-device test procedure
- tools/ — Python reference utilities and tests

## Range limitations

Bluetooth does not provide unlimited range. Hardware, terrain, obstacles, interference, and battery state all matter. Multi-hop relaying may extend the paths a message can travel, but cannot guarantee delivery or a particular distance. Relaying is not implemented yet.

## Security principles

1. No homemade cryptography.
2. No guarantees of anonymity or untraceability.
3. Add reviewed end-to-end encryption before presenting the app as private messaging.
4. Discovery must not automatically grant trust.
5. Never show an undelivered message as delivered.
6. Do not claim emergency readiness before real field testing.
7. Keep internet permission out of the Android manifest unless a documented requirement justifies it.

See docs/PRODUCT_VISION.md, docs/PROTOCOL.md, docs/THREAT_MODEL.md, and SECURITY.md.