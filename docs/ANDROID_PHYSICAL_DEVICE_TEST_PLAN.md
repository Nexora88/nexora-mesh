# Android Physical-Device End-to-End Test Plan

This plan validates the direct Android Bluetooth Classic path. It is a manual release gate; emulator-only tests do not replace two-device testing.

## Test setup

- Two real Android phones, labelled **A (sender)** and **B (receiver)**.
- Install the same debug APK on both phones; record Android version, model, app version and APK SHA-256.
- Enable Bluetooth on both phones. Disable Wi-Fi and mobile data on both; confirm internet is unavailable.
- Pair the phones through Android system Bluetooth settings. Grant the app only the Bluetooth permissions it requests.
- Open Nexora Mesh on both phones. Exchange contact QR codes out-of-band and verify the displayed fingerprints on both sides before sending.

## Required scenarios

| ID | Scenario | Expected evidence |
|---|---|---|
| E2E-01 | A discovers B and connects over Bluetooth Classic | Both devices show the peer/connection state; no internet connection is available |
| E2E-02 | QR trust and encrypted-channel handshake | Both sides show the same verified peer fingerprint and secure-session-ready state |
| E2E-03 | A sends a unique text message to B | B displays the exact text once; A retains it as pending until authenticated delivery ACK |
| E2E-04 | B receives and acknowledges the message | A shows delivery confirmation only after the authenticated ACK is accepted |
| E2E-05 | Disconnect B before sending, then reconnect | Message remains in A's durable pending queue and is retried after reconnect |
| E2E-06 | Force-close and reopen A while a message is pending | Pending message and retry metadata survive process restart |
| E2E-07 | Re-send a previously received message frame | B does not display a duplicate; replay history survives reconnect/restart |
| E2E-08 | Use an untrusted QR identity or mismatched fingerprint | Secure session is not activated and message sending is refused |
| E2E-09 | Send a small media file and compare its SHA-256 after export/decryption | Receiver obtains byte-identical content; vault file on disk is ciphertext |
| E2E-10 | Modify one byte of a stored vault ciphertext | AES-GCM authentication fails; plaintext is not returned |
| E2E-11 | Send an oversized or malformed media frame | Receiver rejects it without crashing or allocating unbounded memory |
| E2E-12 | Repeat connect/disconnect and send/ACK cycles | No stuck connection state, duplicate UI messages, or lost pending queue entries |

## Evidence to capture

For every scenario record pass/fail, device model and Android version, timestamp, app commit SHA, APK SHA-256, and screenshots or sanitized logs. Never include private message contents, QR secrets, key material, Bluetooth addresses, or personal contact details in public logs.

## Release gate

- E2E-01 through E2E-08 must pass on two physical phones with Wi-Fi and mobile data disabled.
- E2E-09 and E2E-10 must pass on a physical Android device.
- Unit tests, Android lint/build, and CI must be green for the exact PR head SHA.
- A failed or unexecuted scenario remains a blocker; do not describe the app as production-ready or emergency-ready.
- This plan does not claim multi-hop relay, iOS interoperability, forward secrecy, or independent cryptographic review.
