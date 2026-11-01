# Android Bluetooth, E2EE and media test plan

This is a direct Android-to-Android prototype using Bluetooth Classic RFCOMM. The current branch adds QR-based identity verification, an encrypted text channel, and encrypted chunked file transfer. It is still not a multi-hop mesh, and the new features require physical-device validation.

## Requirements

- Two physical Android devices with Bluetooth support.
- Install the same current CI debug APK on both phones.
- Keep both devices close together for the first test.
- Enable Bluetooth in Android Settings on both devices.
- Do not use this prototype for emergency communications.

## 1. Pair and connect

1. On both phones, open Android Settings → Bluetooth and make the phones discoverable if required.
2. In Nexora Mesh on each phone, open **Cihazlar** and tap **Yakındaki cihazları ara**. Grant Bluetooth permissions.
3. Select the other phone. Confirm Android's system pairing code matches on both phones before accepting.
4. On phone A, tap **Gelen bağlantıyı bekle**.
5. On phone B, select the paired phone A to connect. If needed, swap listener/client roles.
6. Keep the connection open and proceed to identity verification. Bluetooth bonding alone does not unlock encrypted messaging.

## 2. QR identity and mandatory trust gate

1. On phone A, open **Güvenlik** and show its device identity QR.
2. On phone B, open **Güvenlik → Güvenilir kişi QR kodunu tara**, scan A's QR, compare the full fingerprint shown on A, then explicitly save it.
3. Repeat in the opposite direction: A scans and saves B's QR.
4. Return to the connection. Both sides should show the verified encrypted-channel state. A message must not be sent before the peer's QR public key matches the saved contact.
5. Restart each app and confirm the identity QR and trusted contact remain the same.
6. Remove a saved contact and verify that the encrypted channel is no longer trusted. Do not rely on the status label alone; verify that sending is blocked.

The transport sends a public-key HELLO in plaintext, then uses ECDH and AES-GCM for message payloads only after a stored QR fingerprint matches. This is an implementation milestone, not a completed independent security audit.

## 3. File and media transfer

1. With both QR identities trusted and the encrypted connection active, tap **Dosya / medya gönder**.
2. Select a small non-sensitive test file (first try under 1 MiB).
3. Confirm the receiver reports the file name and SHA-256 integrity validation.
4. Locate the saved file under the receiving app's app-specific Downloads directory and compare its checksum with the original.
5. Repeat with a larger file and with a connection interruption. Current transfer does not provide resume, receipt acknowledgements, or multi-peer routing; an interrupted transfer should be treated as failed.

The current limit is 1–64 MiB. Chunks are encrypted independently using AES-GCM and the complete file SHA-256 is checked after reassembly.

## 4. Required relay test — not yet implemented

The current Android manager supports one direct RFCOMM socket at a time. Therefore a real A → B → C test is not currently possible from the app, even though the Rust relay queue has unit tests. Do not mark multi-hop as passed until all of the following are demonstrated on three physical phones:

- A can send an end-to-end encrypted envelope addressed to C while only connected to B.
- B can queue and forward the opaque envelope without decrypting it.
- C decrypts the message and validates the end-to-end authentication tag.
- Duplicate, expired, zero-hop, and queue-full packets are rejected.
- The test works with Wi-Fi and mobile data disabled and includes logs for all three devices.

## 5. iPhone ↔ Android — not yet implemented

The current Android radio uses Bluetooth Classic RFCOMM, which is not a generic iOS app-to-app transport. Cross-platform offline radio needs a shared Bluetooth Low Energy GATT service and compatible Android and CoreBluetooth implementations on iOS. There is no verified iPhone–Android session yet.

## Test report template

Record the phone model, Android/iOS version, app commit SHA, connection direction, QR fingerprint match, text round-trip, file checksum, transfer size, and outcome. CI compilation is not a substitute for physical-device radio testing.
