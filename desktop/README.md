# Nexora Mesh Desktop

This is the native desktop shell foundation built with Tauri 2. The web UI in `../web` is used as its frontend. The Rust host exposes BLE discovery commands through `btleplug` on supported desktop operating systems.

## Current scope

- Native desktop window and installer targets (Windows MSI/NSIS, macOS DMG/app, Linux DEB/AppImage).
- Native BLE adapter discovery, scan start/stop, and nearby-device listing commands.
- The existing PWA remains usable as a browser-installed management console.

## Not implemented yet

- Pairing and cryptographic identity confirmation through the desktop BLE adapter.
- Sending/receiving the Nexora wire protocol from the desktop host.
- Desktop store-and-forward, durable hop ACKs, and end-to-end encrypted conversations.
- Signed/notarized release artifacts and physical adapter testing.

Do not describe this shell as a working desktop messenger until those items are completed and tested.
