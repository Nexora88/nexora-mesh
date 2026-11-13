# Nexora Mesh

**An offline-first messenger project for Android and Apple platforms, designed with resilient communication in mind.** The long-term goal is a trustworthy messenger that can help people communicate when connectivity is disrupted by wildfires, earthquakes, floods, or mountain search-and-rescue operations.

> **Honest status:** Android Bluetooth Classic remains the current direct chat path. Android BLE and iOS CoreBluetooth transports exist as prototypes. The Rust relay core now supports bounded opaque-envelope storage, hop decrement, expiry, replay filtering, opt-in and ACK-retained queue APIs; Android SharedPreferences and iOS Keychain relay stores are added. Battery policy is exposed through Android JNI and iOS C-FFI. iOS CoreBluetooth state restoration and supported background modes are declared, but the OS does not guarantee continuous background radio work. A Tauri desktop shell now exposes BLE discovery/listing only. The PWA remains a management console. Live multi-hop forwarding, hop STORE_ACK/retry, desktop encrypted chat, physical interoperability and independent security review are not complete. This is not ready for emergency use.

## Product vision

- **Messenger experience:** chat list, trusted contacts, message delivery states, and later file/audio/video calling.
- **Offline communication:** direct Bluetooth links without an internet server.
- **Disaster resilience:** support teams when infrastructure is unavailable, with field testing and clear limitations.
- **Android + Apple:** Android radio transport first, plus a native iOS interface and a protocol designed for cross-platform interoperability.
- **Range extension in stages:** direct connection first, then opt-in store-and-forward and multi-hop mesh relaying.

## Android direct-link prototype

The first transport uses Bluetooth Classic RFCOMM. The user starts device discovery; an unknown device is not automatically trusted. The user must select a device and accept Android's system pairing flow on both phones. After pairing, one phone listens for an incoming connection and the other initiates the connection. The connection can carry text while internet access is off.

See [Android two-device test instructions](docs/ANDROID_BLUETOOTH_TEST.md).

**Important boundaries:** this is one direct connection at a time. The Android direct-session code now includes QR-fingerprint-based peer trust, ECDH/AES-GCM session encryption, replay checks, and authenticated delivery acknowledgements. These code paths have not received an independent cryptographic audit or been verified in a two-physical-device offline exchange. Forward secrecy, durable chat history, Apple interoperability, and live mesh relaying remain incomplete. CI compilation and a locally built APK do not prove physical-device Bluetooth delivery.

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
- [x] Local direct-chat debug APK built and its Android debug signature verified (physical installation still pending)
- [ ] Android CI build and physical-device installation verified
- [ ] Successful two-way message exchange on two physical Android devices with internet disabled
- [x] QR-fingerprint peer verification and ECDH/AES-GCM direct-session code implemented (independent review and physical verification pending)
- [x] Authenticated delivery-acknowledgement code path implemented (physical verification pending)
- [ ] Independent security review and forward secrecy
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

Bluetooth does not provide unlimited range. Hardware, terrain, obstacles, interference, OS scheduling, and battery state all matter. Multi-hop relaying may extend the paths a message can travel, but cannot guarantee delivery or a particular distance. The current store-forward queue and Rust routing APIs are groundwork; live A→B→C radio forwarding and authenticated hop acknowledgements remain incomplete.

## Security principles

1. No homemade cryptography.
2. No guarantees of anonymity or untraceability.
3. Add reviewed end-to-end encryption before presenting the app as private messaging.
4. Discovery must not automatically grant trust.
5. Never show an undelivered message as delivered.
6. Do not claim emergency readiness before real field testing.
7. Keep internet permission out of the Android manifest unless a documented requirement justifies it.

See docs/PRODUCT_VISION.md, docs/PROTOCOL.md, docs/THREAT_MODEL.md, and SECURITY.md.

## Privacy and security status

The Android BLE GATT transport remains a separate prototype and is not yet wired end-to-end to the QR-trust and encrypted chat protocol. The current direct chat path uses Bluetooth Classic RFCOMM. The relay queue/store primitives are not yet an authenticated multi-hop delivery system. iOS remains transport-only; Android↔iOS encrypted chat, forward secrecy, physical interoperability tests and independent security review are incomplete. See [the privacy/security threat model](docs/PRIVACY_SECURITY.md) and [cross-platform transport status](docs/CROSS_PLATFORM_TRANSPORT.md). Do not use this prototype for emergency guarantees or highly sensitive communication.


## Installable web / desktop management console

A progressive web app (PWA) management console is available under [web/](web/). Serve it over HTTPS (or localhost for development), open it in a supported browser, and choose **Install app** / **Add to Home Screen**. Its shell is cached for offline reopening after the first successful load.

**Important:** this PWA is currently a management UI for local contact labels and drafts. It does not have access to the native radio transport, does not cryptographically verify pasted QR payloads, and deliberately does not send messages. The chat UI becomes a real messenger only after the native bridge, trusted identity verification, session encryption, and delivery/ACK path are connected. A desktop native shell with signed BLE drivers is still a separate release task.

See [store-and-forward, power policy and desktop integration](docs/STORE_FORWARD_POWER_DESKTOP.md) for implemented primitives and the remaining native integration work.
