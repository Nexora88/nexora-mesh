# Protocol notes

This is an evolving design note, not a finalized wire-protocol specification.

## Initial frame

The Rust core starts with a bounded frame: a 4-byte unsigned big-endian payload length followed by the payload. The current maximum is 1 MiB. Framing is not encryption, authentication, routing, or a complete message protocol.

## Planned logical envelope

A future version may define protocol version, message type, random message identifier, sender and recipient identity references, creation time and expiry, hop limit/routing metadata, and ciphertext payload. Field sizes, canonical encoding, compatibility, signatures, replay protection, and error behavior must be specified and tested before wire compatibility is promised.

## Transport independence

Logical messages should not depend on Bluetooth or Wi-Fi Direct APIs. Transport adapters provide byte streams or bounded packets; the core validates and processes frames.

## Milestones

1. Frame parser and tests.
2. Android local data model and chat UI.
3. One-hop local connection and authenticated session.
4. End-to-end encrypted text messages.
5. Offline queue and delivery receipts.
6. Store-and-forward relay with loop prevention and expiry.
7. Attachments and further media only after text reliability and battery tests.

No claim of global reach, automatic connectivity at any distance, anonymity, or guaranteed delivery is made.
