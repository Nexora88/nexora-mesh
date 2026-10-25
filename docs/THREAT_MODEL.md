# Threat model

## Assets
- Message contents
- Peer identity and identity keys
- Local message history
- Device discovery and connection state

## Potential adversaries
- A nearby device attempting to send malformed data
- An unauthorized peer attempting to impersonate a contact
- A person with physical access to an unlocked device
- A passive observer able to observe radio transmissions
- A relay node that drops, delays, duplicates, or reorders messages

## Current reality

The current Android UI prototype has no radio transport and no end-to-end encryption. It must not be treated as a secure messenger.

## Planned mitigations
- Established cryptographic protocols and libraries; no custom cryptography
- Explicit peer identity verification and key-change warnings
- Authenticated encryption, replay protection, message expiry, and bounded parsing
- Local database protection appropriate to Android's platform capabilities
- Clear delivery states that distinguish queued, sent, and delivered
- Minimize retained metadata and avoid claiming radio invisibility

Protection against a fully compromised operating system, physical coercion, traffic analysis, RF detection, and guaranteed anonymity is out of scope for the initial MVP.
