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

## Development build

Install the Tauri v2 prerequisites for your operating system (Rust stable plus the platform webview/build toolchain), then from this directory run:

```bash
cd src-tauri
cargo install tauri-cli --version "^2"
cargo tauri dev
```

To package installers:

```bash
cargo tauri build
```

The first desktop milestone exposes BLE discovery only. Do not enter sensitive data or assume that a discovered BLE name is a trusted identity.
