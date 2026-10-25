# Nexora Mesh

**An offline-first messenger project for Android and Apple platforms, designed with resilient communication in mind.** The long-term goal is more than an anonymous chat app: build a recognizable, trustworthy messaging brand that can help people communicate when connectivity is disrupted by wildfires, earthquakes, floods, or mountain search-and-rescue operations.

> **Honest status:** Early prototype. The Android screen is a local UI prototype, and ios/NexoraMeshApp.swift is a SwiftUI interface starter. Real device discovery, pairing, two-device message delivery, end-to-end encryption, and mesh relaying are not implemented yet. This is not ready for emergency use.

## Product vision

- **Messenger experience:** chat list, trusted contacts, message delivery states, and later file/audio/video calling.
- **Offline communication:** local radio links between devices without requiring an internet server.
- **Disaster resilience:** support teams when infrastructure is unavailable, with field testing and clear limitations.
- **Android + Apple:** Android radio transport first, plus a native iOS interface and a protocol designed for cross-platform interoperability.
- **Range extension in stages:** direct connection first, then opt-in store-and-forward and multi-hop mesh relaying.

## Discovery and trust

Privacy should be the default. Devices should be undiscoverable when possible until the user deliberately starts a time-limited discovery session. Discovering a nearby device must not automatically add it as a contact. Only explicitly paired/trusted devices should appear in the normal contacts list. Pairing should require approval from both sides and a verification code or QR fingerprint. A Bluetooth name or address is not a cryptographic identity.

## Current status

- [x] Public repository and Rust workspace
- [x] Android Jetpack Compose UI prototype
- [x] SwiftUI interface starter for Apple platforms
- [x] Rust frame codec and tests
- [x] Product vision, threat model, and roadmap
- [ ] Android CI build and physical-device installation verified
- [ ] Xcode project and physical iOS build
- [ ] Bluetooth discovery and trusted pairing
- [ ] Encrypted text delivery between two real devices with internet disabled
- [ ] Persistent chat history and delivery states
- [ ] Opt-in mesh relaying and field range tests
- [ ] File, audio, and video calls

Do not describe unfinished items as implemented.

## Repository layout

- android/ — Android app shell
- ios/ — SwiftUI interface starter and Apple platform notes
- crates/mesh-core/ — Rust protocol core
- docs/PRODUCT_VISION.md — product vision and privacy rules
- docs/PROTOCOL.md — protocol notes
- docs/THREAT_MODEL.md — security boundaries and risks
- docs/ROADMAP.md — development milestones
- tools/ — Python reference utilities and tests

## Range limitations

Bluetooth and Wi-Fi Direct do not provide unlimited range. Hardware, terrain, obstacles, interference, and battery state all matter. Multi-hop relaying may extend the paths a message can travel, but cannot guarantee delivery or a particular distance. Relaying is not implemented yet.

## Security principles

1. No homemade cryptography.
2. No guarantees of anonymity or untraceability.
3. Use reviewed cryptographic libraries for end-to-end encryption before enabling real private messaging.
4. Discovery must not automatically grant trust.
5. Never show an undelivered message as delivered.
6. Do not claim emergency readiness before real field testing.
7. Keep internet permission out of the Android manifest unless a documented requirement justifies it.

See docs/PRODUCT_VISION.md, docs/PROTOCOL.md, docs/THREAT_MODEL.md, and SECURITY.md.