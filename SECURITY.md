# Security Policy

Nexora Mesh is an early prototype. It has not received an independent cryptographic audit and is not ready for production or emergency use.

## Current Android implementation

- Android source includes a direct Bluetooth Classic RFCOMM text-chat path.
- The direct session code uses ECDH and AES-GCM, QR-fingerprint-based peer trust, replay checks, and authenticated delivery acknowledgements.
- A direct-chat-only debug APK can be built with `scripts/build-android-direct-debug.ps1`. That test APK deliberately disables BLE, Rust relay, and multi-hop features.
- Local Android unit tests and APK signature verification passed. Two physical Android devices have not yet completed a verified offline end-to-end exchange.

## Current limitations

- No independent cryptographic audit; forward secrecy is not implemented.
- Persistent chat history and cross-platform interoperability remain incomplete.
- BLE and multi-hop relay remain prototypes and are not included in the direct-chat test APK.
- No anonymity, untraceability, or metadata-protection guarantee is made.
- Do not use this prototype to exchange sensitive information or for emergency communication.

## Reporting a vulnerability

Please do not publish exploit details for an issue that could affect users. Open a private security advisory through GitHub's Security tab when available, or contact the maintainers through the repository owner profile.

## Remaining engineering requirements

Before the app is presented as a production private messenger, it needs an independent security review, forward-secret session design, reviewed key lifecycle and rotation, validation and fuzzing of all wire inputs, physical-device interoperability testing, and a documented threat-model review.
