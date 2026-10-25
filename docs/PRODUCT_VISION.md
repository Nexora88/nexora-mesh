# Nexora Mesh — product vision

## Mission

Build a recognizable, dependable messenger for everyday nearby communication and for teams working through disrupted connectivity: wildfire response, earthquakes, floods, and mountain search-and-rescue. It should feel like a modern messenger, not merely a command-line radio utility, while making its offline limitations obvious.

This is a long-term product direction, not a claim that the current prototype is ready for emergency use.

## Platforms

- Android: first implementation target.
- Apple iOS: native SwiftUI interface starter exists in `ios/NexoraMeshApp.swift`; a complete Xcode project and transport are still pending.
- Shared protocol: versioned, transport-independent messages and identity rules, with conformance tests shared between implementations.

## Discovery, identity, and trust

The product must distinguish three states: not known, discovered nearby, and explicitly trusted/paired.

- Devices should be undiscoverable by default where the operating system and transport allow it.
- Discovery should be a deliberate, time-limited action controlled by the user.
- A discovered device must not automatically become a contact.
- Both sides should approve pairing; show a short verification code or QR fingerprint and warn on identity-key changes.
- Only trusted contacts should appear in the normal contact list. Nearby untrusted devices belong in a separate temporary pairing view.
- Never expose a user's full contact list or chat history over discovery.
- Do not equate a Bluetooth name or MAC address with a cryptographic identity.

## Internet-free two-device link

First reliable milestone: two real devices establish a local link with mobile data and Wi-Fi internet access disabled, verify each other's identity, and exchange encrypted text with visible queued/sent/delivered states.

Transport work should evaluate Bluetooth Low Energy for discovery/control and Wi-Fi Direct or other platform-supported local links for higher-throughput sessions. iOS and Android have different radio APIs and policy limits; the implementation must use compatible cross-platform behavior rather than assuming Android-only APIs exist on Apple devices.

## Range extension

Range must not be advertised as unlimited. A direct radio link is constrained by hardware, terrain, interference, antenna placement, and battery. Improve reach in stages:

1. Reliable direct links and honest signal/link status.
2. Store-and-forward for offline messages.
3. Explicitly opted-in relay devices, with hop limits, message expiry, deduplication, battery safeguards, and abuse controls.
4. Field testing across terrain and adverse conditions before publishing any range claims.

Mesh relaying increases the number of possible paths, not guaranteed range or delivery. A relay must not gain access to plaintext message contents.

## Emergency-use safeguards

- Make it clear when no peer is connected and when a message has not been delivered.
- Do not imply that an SOS was received unless an acknowledgement actually arrived.
- Preserve local battery and radio controls; do not keep scanning indefinitely by default.
- Support concise emergency messages and optional location sharing only with explicit permission and clear recipient scope.
- Provide field-test documentation and fail-safe limitations before claiming suitability for rescue or public-safety operations.

## Current implementation boundary

The Android app is a local UI prototype, the iOS file is a UI starter, and the Rust crate currently provides bounded frame encoding/decoding only. Real radio discovery, device pairing, encryption, device-to-device delivery, voice/video calls, and mesh relaying remain unimplemented.
