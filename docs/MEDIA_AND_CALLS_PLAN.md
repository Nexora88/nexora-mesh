# Nexora Mesh — media, voice messages, calls, and privacy implementation plan

**Status: design and engineering gates; not an implementation claim.** This plan is based on the current repository at `main`. It deliberately separates the existing encrypted file-transfer prototype from features that do not yet exist.

## 1. Verified baseline

- Android has a document picker and calls `BluetoothChatManager.sendMedia`.
- Media is split into 16 KiB chunks, limited to 64 MiB, protected in transit with the current AES-GCM channel, and checked with SHA-256 after reassembly.
- The current sender streams chunks over the existing wire channel, but has no media-specific authenticated receipt, durable resume protocol, or per-chunk retry.
- The receiver reassembles the entire file in memory before writing it to app-specific storage. Received media is plaintext at rest.
- The current ECDH session is based on long-lived static identity keys. It has no forward secrecy and has not had an independent cryptographic review.
- No audio recorder/player, in-app video capture flow, or video-call implementation is present.
- The Android Bluetooth Classic and BLE paths are separate prototypes. The BLE path must not be assumed to provide the same end-to-end message/media guarantees until the full protocol is verified over it.

## 2. Product scope

### A. Photos, videos, and documents

1. Add distinct attachment actions: Photo, Video, File, and Camera.
2. Use Android Photo Picker / Storage Access Framework so the app receives access only to user-selected content. Do not request broad storage access.
3. Show filename, MIME type, size, preview, and recipient before sending. Reject empty or over-limit input before allocating a full in-memory copy.
4. Move to streaming file I/O: bounded chunk buffers, disk-backed partial receive files, strict maximum active transfers, and cleanup of abandoned partials.
5. Bind transfer ID, sender/recipient fingerprints, MIME type, file size, chunk count, expiry, and whole-file digest into authenticated metadata. Do not trust MIME type or filename as proof of file safety.
6. Use a media-specific authenticated protocol: transfer offer → recipient accept/reject → encrypted chunks → per-chunk ACK/window → final digest verification → authenticated completion receipt.
7. Persist transfer state atomically and resume only after a newly authenticated session. Reject duplicate/conflicting chunks, changed metadata, expired transfers, and unexpected sender identities.
8. Show honest states: Preparing, Waiting for recipient, Sending, Paused, Resuming, Verifying, Received, Failed. A successful Bluetooth write is not a delivery receipt.
9. Keep received content in app-private storage by default. Do not silently publish files to the public gallery or downloads folder. Add explicit export/share controls with a privacy warning.
10. Add optional thumbnail generation on-device. Never transmit a thumbnail before the recipient accepts the transfer; thumbnails must be encrypted like the original.

### B. Voice messages

1. Add a press-to-record / tap-to-lock interaction with a clear recording timer and cancel action.
2. Request microphone permission only after the user starts recording. Explain why the permission is needed; handle denial without blocking text chat.
3. Record a bounded AAC-LC `.m4a` file using Android MediaRecorder; enforce duration and file-size limits and stop recording on lifecycle interruptions.
4. Preview, replay, discard, or send. Never auto-send a recording.
5. Send through the same authenticated, encrypted, resumable media-transfer protocol. Do not create a separate weaker audio transport.
6. Playback only after explicit user action; never auto-play received audio. Provide duration, pause/resume, and accessible controls.
7. Store recordings encrypted at rest using per-file random data keys wrapped by Android Keystore. Delete temporary plaintext after successful encryption and use best-effort cleanup for interrupted recordings.

### C. Video capture and playback

1. Add camera capture as an explicit user action and a selected-video attachment path.
2. Request camera permission only when capture is opened; request microphone permission separately only if recording sound.
3. Preview before sending; show duration, dimensions, and size. Offer user-controlled compression with an original-quality option where size limits allow.
4. Keep capture local until the user taps Send. No automatic cloud upload, background recording, or hidden camera/microphone use.
5. Use the same media-transfer state machine, encrypted-at-rest storage, authenticated metadata, and explicit export behavior.
6. Do not auto-play incoming video. Provide a local preview and explicit play action.

### D. One-to-one video and voice calls

**A BLE radio is not a viable video-media bearer.** BLE may be used for discovery or a small authenticated call invitation, but real-time audio/video needs a high-throughput IP path such as local Wi-Fi/Wi-Fi Direct or an internet path. A call must not be advertised as offline-over-Bluetooth.

Recommended first implementation:
1. Add an explicit call invitation and acceptance flow, bound to the QR-verified contact identity and a fresh call ID.
2. Use a maintained WebRTC implementation for ICE, DTLS-SRTP, congestion control, jitter buffering, echo cancellation, and network adaptation. Do not implement codecs, SRTP, ICE, or key exchange from scratch.
3. Prefer peer-to-peer media. Where connectivity requires TURN, configure a privacy-reviewed TURN service that relays encrypted WebRTC packets; document that IP/traffic metadata may still be visible to infrastructure.
4. Bind the authenticated call signaling transcript to the verified contact identity and ephemeral call keys. Reject stale/replayed offers, changed fingerprints, and unknown peers.
5. For 1:1 peer-to-peer calls, DTLS-SRTP protects the media transport. If a media server/SFU is introduced later, add a separately reviewed end-to-end media layer (for example, a standards-based insertable-streams design); transport encryption alone does not protect content from an SFU.
6. Display clear states: Ringing, Connecting, Encrypted, Reconnecting, Ended, Failed. Show camera/microphone indicators and an always-visible hang-up control.
7. Request camera and microphone access only after the user accepts/starts a call. Stop capture immediately on hang-up, permission revocation, app termination, or device interruption.
8. Add foreground-service and notification behavior only after platform policy review. Do not silently keep camera/microphone active in the background.
9. Start with 1:1 calls. Group calls require a separate threat model, group key management, and a reviewed E2EE design; they are not part of the first milestone.

## 3. Security work required before real media/calls

- Replace the custom static-ECDH session protocol with a reviewed, maintained protocol/library that provides ephemeral key agreement, transcript binding, directional keys, replay resistance, and key rotation. Do not label the current protocol forward-secret.
- Use platform Keystore/Keychain-backed key protection and per-file encryption keys for data at rest. Define recovery and deletion behavior.
- Authenticate every transfer control message and receipt; do not accept delivery status based on transport writes.
- Keep plaintext, media bytes, private keys, fingerprints, and session secrets out of logs, analytics, crash reports, and notifications.
- Enforce resource limits before allocation: file size, chunk count, concurrent transfers, receive disk quota, call bitrate, and timeout.
- Verify contact key changes with a visible warning and explicit re-verification; never silently replace a trusted key.
- Add dependency review, SBOM, static analysis, fuzzing, malformed-frame tests, and an independent security review.
- Document endpoint compromise, rooted devices, screen capture, traffic analysis, IP metadata, backups, and exported-file limitations.

## 4. Test plan and release gates

### Automated
- Media: empty/oversized files, malformed metadata, invalid MIME, chunk reorder, missing/duplicate/conflicting chunks, digest mismatch, expiry, reconnect, resume, disk-full, app restart, and malicious filenames.
- Privacy: permission denied/revoked, lifecycle interruption during recording, no auto-record, no auto-send, no auto-play, and no plaintext in logs.
- Crypto: unknown contact, changed QR key, invalid authentication tag, replayed control/receipt, expired call invite, and transcript/fingerprint mismatch.
- Calls: permission denial, network switch, poor bandwidth, packet loss, call interruption, reconnect, and hang-up cleanup.

### Physical devices
- Two Android devices: bidirectional text, photo, video, document, voice message, interrupted transfer/resume, and call over Wi-Fi.
- Android ↔ iPhone: only after iOS implements the same reviewed identity/session/media protocol and a real Xcode build exists.
- BLE-only mode: verify text/control/file limitations honestly; do not claim video calling over BLE.
- Test on multiple Android API levels and OEMs; record device, OS, network type, transfer size, throughput, battery, and failure rate.

### Release decision
An APK is a build artifact, not proof of feature completeness or security. Do not publish a production/sensitive-use claim until all applicable automated tests, device tests, privacy review, and independent security review pass.

## 5. Suggested delivery sequence

1. **M4.1 — reliable encrypted file transfer:** streaming I/O, transfer offer/accept, ACK/retry/resume, final receipt, encrypted-at-rest files, truthful UI states.
2. **M4.2 — voice messages:** explicit recording, preview/cancel, encrypted transfer, explicit playback.
3. **M4.3 — photo/video attachments:** picker/camera, preview/compression, secure storage/export.
4. **M5.1 — 1:1 calls:** WebRTC over local Wi-Fi/IP, authenticated signaling, DTLS-SRTP, permissions and lifecycle handling.
5. **M5.2 — cross-platform calls:** iOS parity, interoperability tests, independent review.
6. **M6 — group calls:** only after group identity/key management and E2EE threat model are reviewed.
