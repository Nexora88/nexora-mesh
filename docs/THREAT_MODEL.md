# Threat model

## Assets
- Message and media contents
- Device identity keys and saved QR-verified contacts
- Local received files and transfer metadata
- Device discovery and connection state
- User-controlled discoverability and permissions

## Potential adversaries
- A nearby device sending malformed or oversized frames
- An unauthorized peer attempting to impersonate a trusted QR identity
- A person with physical access to an unlocked device
- A passive observer able to observe radio transmissions
- A relay node that drops, delays, duplicates, or reorders messages
- A device attempting to enumerate nearby users or trick them into pairing

## Current Android prototype

- Android uses Bluetooth Classic RFCOMM for one direct connection at a time.
- Device identity is persistent. The private key is encrypted in app preferences using an AES-GCM key held by Android Keystore.
- Contacts are added only after QR parsing, fingerprint validation, and explicit user confirmation.
- A peer must match a saved QR public key. ECDH/AES-GCM payload traffic is enabled only after encrypted proof/ack messages confirm key possession.
- Text frames and media chunks are encrypted and authenticated. Media reassembly checks the whole-file SHA-256 digest and enforces a 64 MiB maximum.
- The code has not undergone an independent security audit and has not been verified on physical devices in this work session.

## Known security limitations

- ECDH currently uses persistent identity keys for session derivation. **Forward secrecy is not provided**: compromise of a long-term private key could expose recorded sessions.
- Text messages do not yet have a persistent replay/deduplication ID or a delivery acknowledgement protocol. AES-GCM detects modification, but an authenticated old frame could be replayed within the same accepted session.
- There is no key rotation, multi-device session management, persistent message history, or resumable media transfer.
- The Rust relay queue is not connected to the Bluetooth transport. The A→B→C Rust test is an in-memory simulation, not a radio test.
- iOS has no interoperable BLE GATT transport yet; current Android RFCOMM cannot be assumed to connect to an iPhone app.
- Bluetooth metadata, device identifiers, timing, file sizes, and radio presence may remain observable.

## Planned mitigations

- Add ephemeral ECDH with identity-key signatures to provide forward secrecy.
- Add authenticated message IDs, replay protection, expiry, acknowledgements, and bounded persistent relay queues.
- Add a shared BLE GATT framing/transport on Android and iOS and test it on physical devices.
- Keep relay nodes limited to opaque ciphertext and explicit user opt-in.
- Use a reviewed cryptographic protocol/library and arrange independent security review before presenting this as a secure messenger.

Protection against a fully compromised operating system, physical coercion, traffic analysis, RF detection, and guaranteed anonymity is out of scope for the prototype. Radio traffic may be observable even when message contents are encrypted.

Do not use this experimental prototype for emergency communications or sensitive information until the known limitations are addressed and the implementation is independently reviewed.
