# Security Policy

Nexora Mesh is an early prototype, not a secure messenger yet.

## Current limitations

- The Android prototype does not send messages to another device.
- End-to-end encryption and peer identity verification are not implemented.
- No anonymity, untraceability, or metadata-protection guarantee is made.
- Do not use this prototype to exchange sensitive information.

## Reporting a vulnerability

Please do not publish exploit details for an issue that could affect users. Open a private security advisory through GitHub's Security tab when available, or contact the maintainers through the repository owner profile.

## Engineering requirements

Before real messaging is advertised as private, the project must use established cryptographic libraries, authenticate peer identities, define replay protection and key lifecycle, validate all network inputs, and receive independent review.
