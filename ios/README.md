# iOS / Apple platform notes

## Current state

- `NexoraMeshApp.swift` — SwiftUI UI prototype.
- `MeshRadioManager.swift` — CoreBluetooth skeleton that respects OS limits.
- `Info.plist.notes.md` — required usage strings and background-mode guidance.

## What is intentionally not claimed

- No physical device discovery or message delivery yet.
- No permanent background relay (iOS does not allow it reliably).
- No Xcode project or App Store build yet.

## Next steps for a real Xcode project

1. Create an iOS app target and add the Swift files.
2. Add the usage description and (if needed) the two Bluetooth background modes.
3. Wire `MeshRadioManager` into the Devices tab with an explicit time-limited discovery button.
4. Later: call the Rust core for the relay queue and power policy.
5. Test on a physical iPhone; the Simulator has no real BLE radio.
