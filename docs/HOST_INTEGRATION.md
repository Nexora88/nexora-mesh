# Host integration guide (Android / iOS / Desktop)

## Shared contract

1. Read power signals (battery %, charging, low-power, foreground, recent connection, urgent pending).
2. Call the same policy used by Rust (`recommended_scan_interval_secs` / `may_forward`) — via C ABI or host fallback.
3. Persist `RelayQueue.snapshot()` in app-private storage only.
4. Never decrypt relay payloads.
5. Discovery is opt-in and time-limited; discovery ≠ trust.

## Android

- `MeshCoreBridge.kt` — BatteryManager + PowerManager → power snapshot + interval.
- Manifest declares BLE permissions (runtime request still required on API 31+).
- Next: build mesh-core with cargo-ndk / UniFFI and replace Kotlin fallback with JNI.

## iOS

- `MeshRadioManager.swift` — CBCentralManager / CBPeripheralManager skeleton.
- `Info.plist.notes.md` — usage strings and background-mode rules.
- Background modes are optional and limited; do not promise always-on relay.

## Desktop

- `desktop/README.md` — Tauri + btleplug skeleton notes.

## Tests you can run today

```bash
cargo test -p mesh-core
python3 tools/mesh_sim_demo.py
```
