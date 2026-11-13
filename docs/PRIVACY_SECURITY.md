# Nexora Mesh — privacy and security model

**Status: experimental engineering document, not a security certification.** Read this before using real or sensitive conversations. The Android BLE integration is newly connected to the existing chat protocol; iOS remains transport-only. No physical-device interoperability or independent cryptographic audit has been completed.

## 1. Security goals

- A nearby radio peer is not automatically a trusted person.
- A message is not sent unless the app has an authenticated session with a QR-trusted identity.
- BLE fragmentation and reassembly must be bounded; malformed frames must fail closed.
- The UI must distinguish connection, local queue acceptance, cryptographic verification, and recipient delivery.
- The relay/transport layer should carry ciphertext only once the application protocol is integrated.
- No plaintext fallback is allowed when trust or decryption fails.

## 2. Data inventory (current implementation)

| Data | Current handling | Important caveat |
| --- | --- | --- |
| Stable Android identity key | P-256 key pair; private key encrypted with an Android Keystore AES-GCM wrapping key; encrypted blob stored in app preferences | Identity is stable. There is no key rotation/recovery flow or forward secrecy yet. |
| Trusted contact | Public key, label, fingerprint, added timestamp in app-local preferences | Public identity data is not a secret, but the contact list reveals social relationships and is not currently encrypted at rest. |
| QR payload | Versioned payload includes public identity key, fingerprint, and display label | Scan only QR codes received through a channel you trust; compare the fingerprint through an independent channel when possible. |
| Chat text | Held in the current UI state; encrypted before the wire frame is emitted | No durable encrypted message database or cross-session history is implemented. Avoid screenshots and device backups for sensitive content. |
| BLE device metadata | Nearby peer labels and Bluetooth address may be shown in the device list | Radio discovery can reveal proximity and device metadata. Advertisements must not include stable identity keys or contact names. |
| Received media | Chunks are encrypted in transit and checked by the transfer reassembler; completed files are encrypted with AES-GCM under a non-exportable Android Keystore key and stored in app-private storage | Current sender/receiver still buffer whole files in memory; there is no authenticated per-chunk receipt or durable resume yet. The vault needs on-device instrumentation testing. |
| Diagnostic status | Short status strings are displayed locally | Do not add message bodies, keys, QR payloads, or raw ciphertext to logs/analytics. |

## 2.1 Media vault implementation status

The Android receive path now passes completed, digest-verified media to MediaVault. The vault encrypts the file with AES-GCM, authenticates the transfer ID, safe filename, and plaintext size as associated data, and writes the ciphertext under the app-private files/secure-media directory. The AES key is generated and retained by Android Keystore. The app does not export or automatically record received media.

This protects completed files at rest. It does **not** yet make the transfer streaming or resumable: the current implementation still reassembles the complete file in memory before vault encryption. Authenticated chunk receipts, durable partial-transfer resume, an explicit decrypt/preview/export flow, and physical-device tests remain open.

## 3. Trust and session flow

1. The app creates or loads its local identity key.
2. The user exchanges a versioned QR contact payload and saves the contact only after reviewing the fingerprint.
3. BLE discovery/connection is treated as an untrusted transport event, not an identity assertion.
4. The remote identity received over the link must exactly match the saved QR public key.
5. The current Android protocol derives a shared secret from static P-256 ECDH and uses an encrypted proof/ack exchange before enabling the chat channel.
6. Text is sealed with AES-GCM; message IDs are authenticated as associated data and duplicate IDs are suppressed within the current in-memory session.
7. Invalid framing, unknown peers, failed proof, invalid authentication tags, malformed IDs, and oversized packets must be rejected without a plaintext fallback.

**Current cryptographic limitation:** the Android implementation is a custom protocol using static identity keys. It does not provide forward secrecy, directional send/receive keys, durable anti-replay state, or a reviewed standard handshake such as a vetted Signal-style protocol. A compromised long-term private key may expose previously recorded sessions. Do not call this implementation audited or production-grade E2EE.

## 4. BLE transport privacy

- Advertise the service UUID, not a permanent identity key, contact name, or user identifier.
- Bluetooth addresses and advertised device names may still be observable by nearby parties; OS address randomization varies by platform/device.
- Require explicit user action to scan/connect. Do not auto-trust based on device name, address, OS pairing, or BLE proximity.
- Enforce envelope size limits, bounded concurrent assemblies, a 30-second assembly timeout, fragment index/count/length validation, duplicate/conflicting-fragment handling, and MTU-aware fragmentation.
- BLE write/notification success means only that a transport operation was accepted. It does not mean the app decrypted, authenticated, displayed, or durably stored a message.
- Do not queue fragments forever. Disconnect must clear the active session and incomplete assemblies.
- Background scanning, reconnection, MTU negotiation, and power behavior differ by phone and OS version and require physical-device tests.

## 5. Threat model

### In scope
- Nearby unknown devices and malicious/malformed BLE packets.
- A peer that claims a false display name or sends a public key that does not match the saved QR identity.
- Tampering with ciphertext or associated metadata.
- Duplicate/replayed text frames during a live session.
- Resource-exhaustion attempts via oversized envelopes, too many incomplete transfers, or conflicting fragments.
- Accidental privacy leaks from UI status, device labels, logs, or files saved to storage.

### Not yet adequately covered
- Compromise of an endpoint, rooted/jailbroken devices, malicious keyboards/accessibility services, screen capture, and OS-level malware.
- Forward secrecy and post-compromise security.
- Persistent replay protection across app restarts, durable message delivery, offline queue expiry, and delivery receipts.
- Contact revocation/key-change warnings and identity migration/recovery.
- Traffic analysis: who is nearby, when packets are exchanged, packet sizes, and approximate communication patterns.
- iOS-compatible identity storage, QR trust, and encrypted chat session.
- Formal protocol review, fuzzing, independent penetration testing, and real-device interoperability.

## 6. Required privacy UX

- Show distinct states: **BLE connected**, **QR identity not verified**, **verifying key possession**, **encrypted session active**, **queued locally**, **delivered (only after an authenticated receipt)**, and **failed**.
- Never label a message “delivered” from a successful GATT write alone.
- Show the trusted contact fingerprint and provide explicit remove/revoke controls.
- Warn when a contact key changes; never silently overwrite the previously trusted key.
- Avoid printing fingerprints, raw public keys, QR contents, plaintext, session keys, or media bytes to logs.
- Provide a clear option to stop discovery and disconnect, and clear session state on disconnect.
- Explain that deleting a conversation does not securely erase OS backups, screenshots, exported files, or forensic traces.

## 7. Release gates before sensitive use

- Replace the custom static-ECDH session with a reviewed protocol/library that supports ephemeral key agreement, transcript binding, directional keys, replay resistance, and key rotation.
- Add independent tests for QR fingerprint mismatch, untrusted identity, proof failure, invalid AES-GCM tag, modified message ID, duplicate/replayed ID, malformed UTF-8/JSON, oversized envelope, fragment conflicts, and reconnect state reset.
- Add property/fuzz tests for the Rust envelope and both BLE reassemblers.
- Add durable, bounded delivery ACK/retry/resume semantics with explicit message expiry and duplicate suppression.
- Implement matching iOS identity/QR/session/chat code using compatible, reviewed primitives before claiming Android↔iOS secure chat.
- Run physical-device tests on at least two Android devices and one iPhone, including bidirectional text, tampering, disconnect/reconnect, backgrounding, permission denial, and memory/power behavior.
- Obtain an independent security review before real sensitive or emergency use.

Nexora Mesh is not currently an emergency communications guarantee. Bluetooth range, delivery, background execution, and device interoperability are not guaranteed.


## 8. Media, voice, and calls — verified feature boundaries

- Android document picker and `sendMedia` exist. The current path encrypts media chunks in transit and checks a whole-file SHA-256 digest after reassembly, but the entire received file is held in memory before it is written. There is no media-specific authenticated completion receipt, durable chunk resume, or per-chunk retry.
- Received media is written as plaintext in app-specific storage. App-specific storage is not the same as encrypted-at-rest storage.
- Voice recording/playback and video calling are not implemented in the current Android app.
- The current BLE transport must not be represented as a video-call transport. Real-time audio/video needs a suitable IP path (for example, local Wi-Fi/Wi-Fi Direct or internet) and a maintained media stack such as WebRTC.
- WebRTC DTLS-SRTP protects a peer-to-peer media transport. If an SFU/media server is added, transport encryption alone does not prevent that server from accessing media; a separately reviewed end-to-end media layer is required.
- Camera and microphone access must be user-initiated, permission-scoped, visibly indicated, and stopped on hang-up or interruption. No background or automatic recording is allowed.
- See [MEDIA_AND_CALLS_PLAN.md](MEDIA_AND_CALLS_PLAN.md) for the staged feature and test plan.
