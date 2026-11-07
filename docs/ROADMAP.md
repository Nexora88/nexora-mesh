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
- [x] Stable Android identity backed by platform-appropriate key storage
- [x] Pairing approved on both devices, with QR/code fingerprint verification
- [ ] Replace custom static-ECDH channel with a reviewed protocol/library providing forward secrecy and replay resistance
- [ ] Persistent local message database
- [ ] Queued/sent/delivered/failed states based on actual authenticated receipts
- [ ] Reconnect, retry, replay protection, and key-change warnings
- [ ] Matching iOS identity/QR/session/chat implementation

## M3 — Range extension and resilience
- [x] Message IDs, expiry, hop limit, deduplication, and store-and-forward queue (Rust + snapshot API)
- [x] Explicit relay opt-in foundation and battery/thermal-aware scan policy
- [x] In-memory and Python multi-node simulation tests
- [ ] Ensure relays cannot read message plaintext (opaque envelope; E2EE still requires protocol replacement)
- [ ] Field tests across line-of-sight and obstructed terrain; publish measured results only
- [ ] Clear emergency limitations and failed-delivery UX
- [ ] Physical-device verification of encrypted A→B→C transfer, persistent queue, TTL, dedup and delivery confirmation

## M4 — Secure media messaging
- [x] Android document picker and bounded encrypted-media prototype (64 MiB, 16 KiB chunks, SHA-256 reassembly check)
- [ ] Streaming disk-backed media I/O (avoid whole-file memory allocation)
- [ ] Transfer offer/accept, authenticated chunk ACK, retry/resume, and final delivery receipt
- [ ] Encrypt received media at rest with per-file keys protected by Android Keystore
- [ ] Explicit photo/video/file picker and camera preview; no automatic upload or export
- [ ] Voice recording with preview/cancel and explicit playback
- [ ] Physical Android-to-Android media transfer and interruption/resume tests
- [ ] iOS media protocol parity and Android↔iOS interoperability

## M5 — Voice and video calls
- [ ] 1:1 voice/video call invitation bound to QR-verified identity
- [ ] WebRTC media over local Wi-Fi/IP or internet; BLE is discovery/control only, not the video bearer
- [ ] DTLS-SRTP, authenticated signaling, explicit permissions, visible capture indicators and reliable hang-up cleanup
- [ ] TURN fallback privacy review and metadata disclosure
- [ ] iOS parity and physical-device call tests
- [ ] Independent security review before sensitive-use claims

## M6 — Group calling (separate security milestone)
- [ ] Group membership, identity changes, and key lifecycle threat model
- [ ] Reviewed group E2EE media design; transport encryption alone is insufficient for SFU privacy
- [ ] Load, abuse, privacy, and cross-platform interoperability tests

## M7 — Platform polish
- [ ] Accessibility and Turkish/English localization
- [ ] Battery, reconnect, and privacy testing
- [ ] Installable PWA management console + native desktop radio bridge (Tauri)

A UI mockup does not count as a working transport. BLE does not provide a viable video-call media path. A wider mesh can improve reach, but does not guarantee range or delivery. An APK build does not certify security or feature completeness.
