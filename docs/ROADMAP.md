# Roadmap

## M0 — Platform foundations
- [x] Android Compose app shell and Rust frame codec
- [x] Native SwiftUI interface starter
- [x] Product vision and trust/discovery principles
- [x] Android BatteryManager / power-policy bridge skeleton (`MeshCoreBridge`)
- [x] iOS CoreBluetooth manager skeleton + background-mode documentation
- [x] Desktop Tauri + BLE notes skeleton
- [ ] Android CI build passes and APK installs on a physical device
- [ ] Add and verify a real Xcode project on a physical iPhone

## M1 — Two-device local transport
- [ ] Select cross-platform compatible discovery/session protocol
- [ ] Android Bluetooth LE discovery with runtime permission and lifecycle handling
- [ ] iOS-compatible discovery and connection path (using MeshRadioManager)
- [ ] Explicit discoverability toggle with a short timeout
- [ ] Two-device diagnostics: discovered, connecting, connected, disconnected
- [ ] Connect two physical devices with internet access disabled

## M2 — Identity, trust, and messages
- [ ] Stable device identity backed by platform-appropriate key storage
- [ ] Pairing approved on both devices, with QR/code fingerprint verification
- [ ] Separate untrusted nearby devices from trusted contacts
- [ ] End-to-end encryption using reviewed libraries and a documented protocol
- [ ] Persistent local message database
- [ ] Queued/sent/delivered/failed states based on actual transport acknowledgements
- [ ] Reconnect, retry, replay protection, and key-change warnings

## M3 — Range extension and emergency resilience
- [x] Message IDs, expiry, hop limit, deduplication, and store-and-forward queue (Rust + snapshot API)
- [x] Explicit relay opt-in foundation and battery/thermal-aware scan policy
- [x] In-memory and Python multi-node simulation tests
- [ ] Ensure relays cannot read message plaintext (opaque envelope; E2E still pending)
- [ ] Field tests across line-of-sight and obstructed terrain; publish measured results only
- [ ] Clear emergency limitations and failed-delivery UX
- [ ] Physical-device verification of encrypted A→B→C transfer, persistent queue, TTL, dedup and delivery confirmation

## M4 — Everyday messenger experience
- [x] Existing Android bounded file-chunk prototype and whole-file SHA-256 verification
- [x] Encrypt completed incoming media at rest with an Android Keystore AES-GCM key
- [ ] Stream file chunks without whole-file memory buffering
- [ ] Authenticated chunk receipts, bounded retry, durable resume, and final completion receipt
- [ ] Explicit encrypted-vault preview/export flow
- [ ] Voice notes with user-triggered microphone permission, preview, discard, and explicit send
- [ ] Video attachments with explicit picker/capture and preview
- [ ] Accessibility and Turkish/English localization
- [ ] Battery, reconnect, and privacy testing

## M5 — One-to-one WebRTC calls
- [ ] IP media path (local Wi-Fi and internet); BLE limited to discovery/control
- [ ] QR-identity-authenticated call invitations and signaling transcript
- [ ] Maintained WebRTC stack with DTLS-SRTP and network-change handling
- [ ] Explicit microphone/camera permissions, accept/decline, and no automatic recording
- [ ] Real-device call quality, permission-denial, TURN, and security tests

## M6 — Group calls
- [ ] Evaluate SFU only after M5 passes; add separate reviewed frame-level E2EE
- [ ] Group membership changes, key rotation, and real-device load tests

A UI mockup does not count as a working transport. A wider mesh can improve reach, but does not guarantee a range or delivery. The current custom static-ECDH session is experimental and does not provide forward secrecy; do not use it for sensitive communication.
