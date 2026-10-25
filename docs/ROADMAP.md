# Roadmap

## M0 — App foundation
- [x] Repository layout, Android Compose shell, Rust frame codec
- [x] Keep internet permission absent
- [ ] CI build passes and APK installs on a physical Android device

## M1 — Local transport
- [ ] Bluetooth discovery and pairing
- [ ] Wi-Fi Direct discovery and connection
- [ ] Connection lifecycle and runtime permission handling
- [ ] Two-device diagnostic screen

## M2 — Reliable text messaging
- [ ] Stable device identity and peer verification
- [ ] End-to-end encryption using reviewed libraries
- [ ] Persistent local message database
- [ ] Queued/sent/delivered/failed states
- [ ] Reconnect and retry behavior

## M3 — Mesh relaying
- [ ] Message IDs, expiry, hop limit, deduplication
- [ ] Store-and-forward queue
- [ ] Relay consent and battery safeguards
- [ ] Multi-device integration tests

## M4 — Media and polish
- [ ] File transfer with size limits and integrity checks
- [ ] Accessibility, localization, battery and reliability testing
- [ ] Threat-model review before privacy claims

A UI mockup does not count as working transport functionality.
