# Protocol notes

This is an evolving design note, not a finalized wire-protocol specification.

## Outer frame

Bounded frame: 4-byte big-endian payload length + payload. Maximum 1 MiB.
Framing is not encryption or authentication.

## Logical mesh packet (version 1)

After the outer frame, the payload is:

| Field | Size | Notes |
|-------|------|--------|
| version | 1 | currently `1` |
| ttl_hops | 1 | decremented once per forward; default max 5 |
| flags | 1 | reserved |
| reserved | 1 | 0 |
| message_id | 16 | random per message; used for dedup |
| blind_target | 16 | keyed tag — only intended recipient recognises it |
| expires_at | 8 | Unix seconds, big-endian |
| payload | rest | opaque ciphertext (E2E not implemented in core) |

Relays must not inspect or modify the payload. They only validate TTL/expiry/dedup and match `blind_target` against local roots.

## Ephemeral IDs (over-the-air identity)

- Long-term identity secret stays on device after pairing (QR/PIN).
- Advertised identity rotates every 15 minutes (`EphemeralIdRotator`).
- Derived as a keyed function of (secret, time slot). The current implementation uses a **development placeholder KDF** — replace with HKDF before production.

## Blind multi-hop

1. Sender computes `blind_target = KDF(recipient_root, message_id)`.
2. Intermediate nodes try local roots; on match → local delivery.
3. Otherwise, if power policy allows and TTL > 0 → `RelayQueue`.
4. On forward, TTL is decremented exactly once.

## C ABI for radio hosts

| Function | Role |
|----------|------|
| `nm_node_create` / `nm_node_free` | opaque node lifetime |
| `nm_process_incoming` | feed raw BLE bytes; returns deliver / queue / drop |
| `nm_current_ephemeral_id` | 16-byte ID for advertising |
| `nm_ephemeral_tick` | rotate when slot changes |
| `nm_recommended_scan_interval_secs` / `nm_may_forward` | power policy |

## Milestones

1. Frame parser and tests — done
2. Relay queue + blind target + ephemeral rotator — structure done (dev KDF)
3. Android/iOS radio path calling `nm_process_incoming` — host wiring next
4. Reviewed crypto (HKDF/HMAC) + real E2E
5. Physical two/three-device field tests

No claim of anonymity, global reach, or guaranteed delivery is made.
