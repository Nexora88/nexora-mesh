# Secure media and calling implementation plan

**Status: implementation in progress. This is not a security certification or a claim that calls are available.**

## M4.1 — Secure file/media storage (current code milestone)

- Keep the current 64 MiB transfer ceiling and strict transfer-ID, chunk-count, chunk-index, declared-size, and SHA-256 checks.
- Store completed incoming files only in app-private storage.
- Encrypt completed files with AES-GCM using a non-exportable Android Keystore key and authenticated metadata (transfer ID, safe filename, and plaintext size).
- Never log plaintext, file bytes, keys, or raw QR payloads.
- Add Android instrumentation tests for vault round-trip and ciphertext tampering.
- Remaining gaps: sender and receiver still buffer file data in memory; transfer chunks have no authenticated per-chunk receipt; disconnect/restart resume is not implemented; the existing transport session uses static ECDH and does not provide forward secrecy.

## M4.2 — Reliable streaming transfer

1. Add a transfer offer containing a random transfer ID, safe display name, MIME type, declared size, and SHA-256 digest. Authenticate all metadata as part of the encrypted frame.
2. Require explicit receiver accept/decline before sending file bytes.
3. Stream bounded chunks from a file descriptor; never load the whole file into a byte array.
4. Persist each accepted chunk as independently authenticated ciphertext in a temporary app-private staging area. Bound total staged bytes, concurrent transfers, and age.
5. Send an authenticated chunk receipt only after the chunk is durably stored. Retries must be idempotent by transfer ID and chunk index.
6. Persist a compact transfer manifest and resume bitmap; revalidate metadata and peer identity after reconnect before resuming.
7. Verify the complete SHA-256 digest before atomically promoting the encrypted file into the vault. Send a final authenticated completion receipt only after promotion.
8. Delete abandoned partial transfers after an explicit expiry policy; do not silently delete a transfer still marked resumable.

## M4.3 — Voice notes and video attachments

- Voice note: request microphone permission only after the user taps Record; show a visible recording state and timer; provide preview, replay, discard, and explicit send; never record automatically.
- Video attachment: use the system picker first; request camera permission only when the user explicitly chooses capture; preview and confirm before sending.
- Both use the same bounded, resumable, encrypted media pipeline and encrypted-at-rest vault.
- No background microphone/camera capture and no automatic upload.

## M5 — One-to-one WebRTC calls

### Signaling and identity
- BLE remains discovery/control only; it is not the real-time media bearer.
- Establish calls over a supported IP path (local Wi-Fi first, then internet).
- Send call invitations only to a contact whose QR fingerprint is already verified.
- Bind call ID, caller/callee identities, SDP offer/answer, ICE candidates, expiry, and replay nonce to the authenticated signaling session.
- Show caller identity and explicit Accept/Decline. Never auto-answer.
- Signaling service should relay setup messages only and avoid storing SDP/ICE longer than required. TURN servers may observe network metadata.

### Media protection and permissions
- Use a maintained WebRTC implementation with DTLS-SRTP; do not implement media cryptography manually.
- Authenticate the signaling transcript against the QR-trusted identity. A successful DTLS connection alone does not authenticate the intended person.
- If a media server/SFU is introduced, add a separate reviewed frame-level E2EE layer; transport encryption to an SFU does not hide media from that SFU.
- Ask for microphone/camera permissions only after the user taps Call and selects audio/video. Keep clear microphone/camera indicators and an always-visible hang-up action.
- No recording feature, no automatic recording, and no capture when a call is not active.
- Handle call cancellation, timeout, busy, reconnect, network change, permission denial, and remote hang-up.

### Release gates
- No "secure call" claim until signaling authentication, key negotiation, media encryption, replay protection, and call lifecycle tests pass.
- Test on real Android devices over local Wi-Fi and internet, with and without TURN, under network changes and permission denial.
- Run dependency/SBOM checks, protocol review, and independent security review before sensitive use.
- Group calls/SFU are a later milestone, not part of M5.

## Security work that precedes sensitive use

The current static-identity ECDH + AES-GCM chat channel is experimental and does not provide forward secrecy. Do not bolt on a home-grown ratchet or claim key rotation. Replace it with a maintained, reviewed protocol that provides ephemeral key agreement, transcript binding, directional keys, replay protection, and defined key rotation. Preserve QR identity verification and add key-change warnings. Verify the selected library's Android/iOS support before migration.
