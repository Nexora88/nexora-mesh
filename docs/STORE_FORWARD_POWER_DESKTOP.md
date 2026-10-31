# Store-and-forward, power policy and desktop status

## Implemented source changes in this branch

- Rust relay queue now distinguishes queue-full from oversized-packet errors, can purge expired records, and exposes a hop-decremented copy without deleting the original.
- A relay entry is removed only after the next-hop storage ACK is explicitly reported to the core API. This prevents a disconnect during transfer from silently losing the only copy.
- Android exposes Rust battery policy through JNI and stores opt-in opaque wire envelopes plus a bounded replay-ID set in app-private preferences.
- iOS exposes the same Rust scan/relay policy through C-FFI and stores opt-in opaque wire envelopes plus bounded replay history in Keychain.
- Android BLE scan uses OS low-power/balanced modes and batched scan results; advertising uses low-power mode. Discovery remains user initiated and should be stopped when leaving the nearby-device screen.
- iOS CoreBluetooth managers use restoration identifiers and the app declares bluetooth-central / bluetooth-peripheral background modes. This allows supported OS-managed wakeups; it does not promise continuous scanning or advertising.
- The web UI now detects the Tauri desktop host and can invoke native desktop BLE discovery start/stop/list commands. The browser/PWA mode continues to report that native radio access is unavailable.
- A Tauri 2 desktop shell and btleplug-based BLE discovery host are scaffolded for Windows/macOS/Linux packaging.

## Important incomplete integration

- The Android/iOS store classes accept only complete Rust wire envelopes. The current chat path still carries its experimental application frames separately; the store-forward queue is not yet inserted into a complete authenticated multi-hop data/ACK state machine.
- The BLE transport does not yet schedule durable queue draining, wait for a next-hop durable-store ACK, retry with backoff, or issue end-to-end delivery receipts. Do not count the current queue as live A→B→C messaging.
- Blind destination token generation/rotation and local-destination delivery detection are not complete. The destination token is opaque routing metadata, not anonymity.
- The Tauri shell currently scans and lists nearby BLE devices only. Desktop QR trust, pairing, encrypted chat, relay queue and message transport are not implemented.
- PWA contacts/drafts are local management data only; the PWA cannot provide native BLE radio access on iOS Safari.

## Power policy

The Rust policy defaults to:
- Foreground / charging / recently connected / urgent pending: 5-second recommended scan cadence.
- Idle: 45-second recommended cadence.
- Low battery or OS low-power mode: 300-second deep-sleep recommendation and relay disabled unless the host policy permits.
- Relay is opt-in and disabled by default.

These are policy recommendations. Android WorkManager/foreground-service rules, iOS CoreBluetooth background scheduling, hardware, OEM battery management and user permissions remain authoritative. No app should attempt to evade those limits. Measure battery drain on real devices before selecting production defaults.

## Store-and-forward protocol requirements still open

1. Define an authenticated next-hop STORE_ACK that confirms durable storage, distinct from a GATT write/notification acknowledgement.
2. Keep original envelope until STORE_ACK; retry with bounded exponential backoff and expiry.
3. Define end-to-end recipient receipt separately from hop-by-hop storage ACK.
4. Add persistent replay/deduplication across process restarts and atomic queue+seen updates.
5. Define destination-token derivation and rotation with privacy review; do not use a static public-key hash as an anonymity guarantee.
6. Test A→B→C across two Android devices and one iPhone, including B process restart and battery/OS background transitions.

## Desktop release gates

- Finish desktop BLE connect, pair, QR trust, native session crypto and encrypted send/receive.
- Connect the Tauri host to the same Rust mesh-core queue and protocol as mobile.
- Add OS-specific permission and adapter tests, code signing/notarization, packaging and update/revocation.
- Verify on physical Windows/macOS/Linux hardware. A successful compile is not a hardware test.

## Physical test record template

Record device model, OS version, app commit SHA, battery level at start/end, relay opt-in state, foreground/background state, scenario, expected outcome, observed outcome, and logs with message contents and private keys removed. Required scenarios: A→B→C, queue persistence after restart, duplicate packet, expiry, TTL exhaustion, next-hop ACK loss/retry, full queue, disconnect/reconnect, low-power mode, and iOS background wakeup.
