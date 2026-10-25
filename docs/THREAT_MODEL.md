# Threat model

## Assets
- Message contents
- Peer identity and identity keys
- Local message history
- Device discovery and connection state
- User-controlled discoverability and location-sharing state

## Potential adversaries
- A nearby device attempting to send malformed data
- An unauthorized peer attempting to impersonate a contact
- A person with physical access to an unlocked device
- A passive observer able to observe radio transmissions
- A relay node that drops, delays, duplicates, or reorders messages
- A device attempting to enumerate nearby users or trick them into pairing

## Current reality

The Android UI prototype and iOS SwiftUI starter have no radio transport and no end-to-end encryption. They must not be treated as secure messengers or used as emergency communication tools.

## Planned mitigations

- Established cryptographic protocols and libraries; no custom cryptography
- Explicit peer identity verification and key-change warnings
- Pairing approval on both devices
- Time-limited, user-controlled discoverability; hidden by default where platform APIs permit
- Keep untrusted nearby devices separate from trusted contacts
- Never transmit the user's full contact list or chat history during discovery
- Authenticated encryption, replay protection, message expiry, and bounded parsing
- Local database protection appropriate to each platform
- Clear delivery states that distinguish queued, sent, and delivered
- Relay nodes forward ciphertext only and cannot access message plaintext
- Minimize retained metadata and avoid claiming radio invisibility

Protection against a fully compromised operating system, physical coercion, traffic analysis, RF detection, and guaranteed anonymity is out of scope for the initial MVP. Radio traffic may be observable even when message contents are encrypted.