# Store-and-forward, power policy and desktop console

## Implemented in mesh-core (Rust)

- `Envelope` — opaque payload with `message_id`, `destination_token`, `ttl_hops`, `expires_at`.
- `RelayQueue` — bounded queue, duplicate suppression by message_id, expiry checks, hop-limit enforcement, per-destination take, snapshot/restore for durable storage by the host app.
- `PowerSignals` + `recommended_scan_interval_secs` / `may_forward` — battery, charging, low-power, foreground, recent-connection and urgent-pending aware scan intervals (smart sleep).
- In-memory `MeshSim` with A→B→C path and unit tests that verify TTL decrement, delivery and deduplication.

## Native integration required next

1. **Android host integration:** call the Rust APIs through JNI (or UniFFI), feed BatteryManager/PowerManager and foreground signals, and use the returned interval to pace BLE scans. Persist `snapshot()` records atomically in app-private storage. Never use external/shared storage for message queues.
2. **iOS host integration:** use CoreBluetooth background modes only for documented, user-visible supported behavior. iOS may suspend or coalesce scans/advertising. Do not promise a permanent background relay or attempt to bypass OS restrictions. Persist encrypted envelope snapshots in app-private storage and schedule opportunistic forwarding when the OS permits.
3. **Radio relay loop:** on authenticated BLE ingress, validate the binary envelope, reject expired/duplicate packets, check explicit opt-in and power policy, store encrypted bytes, and advertise/send only through a live supported radio connection. Decrement TTL exactly once when forwarding; never decrypt relay payloads.
4. **Delivery semantics:** distinguish received by relay, forwarded, delivered to destination, and acknowledged by sender. A relay's local receipt is not end-to-end delivery. Retries must be idempotent.
5. **Target privacy:** derive a rotating, keyed target token from a reviewed protocol design. The current 16-byte destination_token is only a field; no blind-token derivation or rotation is implemented. Static tokens can correlate traffic.
6. **Storage limits:** enforce per-sender quotas, global byte limits, expiry cleanup, bounded replay persistence, and transactional delete-on-ACK. Protect local database files with OS app-private storage and appropriate data protection.
7. **Power testing:** measure average/peak current on actual devices, not just infer it from scan intervals. Test 5 %, 15 %, 30 %, charging, low-power mode, screen off, app background, crowded RF, and repeated reconnects.
8. **Desktop distribution:** a PWA can serve as an installable management console. Real radio messaging on desktop requires a signed Tauri / native shell with platform-specific BLE APIs, permissions, packaging and update strategy.

## Critical platform constraints

- A web page cannot access arbitrary Bluetooth on all browsers/platforms; Web Bluetooth support differs and is not a universal iOS Safari solution.
- iOS CoreBluetooth background behavior is controlled by the OS. The app must respect its background modes, state restoration and suspension rules.
- Store-and-forward only improves reach when compatible relay devices are physically encountered. It cannot guarantee delivery when no relays are nearby.
- The Rust queue is an in-memory structure with snapshot/restore APIs. Durable storage is not automatically enabled until native hosts call these APIs and commit records to storage.
- No physical A→B→C test, battery measurement, independent protocol audit, or signed desktop package is claimed by this document. Physical-device verification of encrypted packet transfer, persistent queue, target tag, TTL, duplicate blocking and delivery confirmation remains outstanding.
