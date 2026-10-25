# Store-and-forward, power policy and desktop console

## Implemented in this branch

- Rust RelayQueue supports bounded opaque-envelope queues, expiry checks, hop limits, duplicate suppression, explicit opt-in, and encoded snapshots for app-layer durable storage.
- SmartRelayPolicy recommends scan intervals from battery, charging, low-power, foreground, recent connection and urgent-pending signals. Its C ABI is exposed through nm_relay_scan_interval_seconds and nm_relay_may_forward for native callers.
- The installable PWA under web/ includes an offline-cached management UI, local contact labels, local draft storage, export/delete controls, and a relay-preference panel.
- The PWA intentionally disables message sending because a browser-only UI is not connected to a native radio bridge and its QR text field is not cryptographic trust verification.

## Native integration required next

1. **Android host integration:** call the C ABI through JNI, feed BatteryManager/PowerManager and foreground signals, and use the returned interval to pace BLE scans. Persist persisted_snapshot() records atomically in app-private storage. Never use external/shared storage for message queues.
2. **iOS host integration:** use CoreBluetooth background modes only for documented, user-visible supported behavior. iOS may suspend or coalesce scans/advertising. Do not promise a permanent background relay or attempt to bypass OS restrictions. Persist encrypted envelope snapshots in app-private storage and schedule opportunistic forwarding when the OS permits.
3. **Radio relay loop:** on authenticated BLE ingress, validate the binary envelope, reject expired/duplicate packets, check explicit opt-in and power policy, store encrypted bytes, and advertise/send only through a live supported radio connection. Decrement TTL exactly once when forwarding; never decrypt relay payloads.
4. **Delivery semantics:** distinguish received by relay, forwarded, delivered to destination, and acknowledged by sender. A relay's local receipt is not end-to-end delivery. Retries must be idempotent.
5. **Target privacy:** derive a rotating, keyed target token from a reviewed protocol design. The current 16-byte destination_token is only a field; no blind-token derivation or rotation is implemented. Static tokens can correlate traffic.
6. **Storage limits:** enforce per-sender quotas, global byte limits, expiry cleanup, bounded replay persistence, and transactional delete-on-ACK. Protect local database files with OS app-private storage and appropriate data protection.
7. **Power testing:** measure average/peak current on actual devices, not just infer it from scan intervals. Test 5%, 15%, 30%, charging, low-power mode, screen off, app background, crowded RF, and repeated reconnects.
8. **Desktop distribution:** the PWA is installable from a secure origin where supported and works offline after first load. It is a management console, not a radio driver. A desktop messenger requires a signed Tauri/native shell with platform-specific BLE APIs, permissions, packaging, update strategy, and its own end-to-end test matrix.

## Critical platform constraints

- A web page cannot access arbitrary Bluetooth on all browsers/platforms; Web Bluetooth support differs and is not a universal iOS Safari solution.
- iOS CoreBluetooth background behavior is controlled by the OS. The app must respect its background modes, state restoration and suspension rules.
- Store-and-forward only improves reach when compatible relay devices are physically encountered. It cannot guarantee delivery when no relays are nearby.
- The current Rust queue is an in-memory structure with snapshot/restore APIs. Durable storage is not automatically enabled until native hosts call these APIs and commit records to storage.
- No physical A→B→C test, battery measurement, independent protocol audit, or signed desktop package is claimed by this document.
