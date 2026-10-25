# Roadmap

## M0 — Platform foundations
- [x] Android Compose app shell and Rust frame codec
- [x] Native SwiftUI interface starter
- [x] Product vision and trust/discovery principles
- [ ] Android CI build passes and APK installs on a physical device
- [ ] Add and verify a real Xcode project on a physical iPhone

## M1 — Two-device local transport
- [ ] Select cross-platform compatible discovery/session protocol
- [ ] Android Bluetooth LE discovery with runtime permission and lifecycle handling
- [ ] iOS-compatible discovery and connection path; document Apple platform constraints
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
- [ ] Message IDs, expiry, hop limit, deduplication, and store-and-forward queue
- [ ] Explicit relay opt-in and battery/thermal safeguards
- [ ] Ensure relays cannot read message plaintext
- [ ] Multi-device integration tests with controlled packet loss
- [ ] Field tests across line-of-sight and obstructed terrain; publish measured results only
- [ ] Clear emergency limitations and failed-delivery UX

## M4 — Everyday messenger experience
- [ ] File transfer with size limits and integrity checks
- [ ] Accessibility and Turkish/English localization
- [ ] Audio/video feasibility study for local links
- [ ] Battery, reconnect, and privacy testing

A UI mockup does not count as a working transport. A wider mesh can improve reach, but does not guarantee a range or delivery.