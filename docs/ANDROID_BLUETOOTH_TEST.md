# Android Bluetooth two-device test

This milestone uses Android Bluetooth Classic RFCOMM for a direct, internet-independent text link. It is not a mesh network, and the app-level end-to-end encryption layer is not implemented.

## Requirements

- Two physical Android devices with Bluetooth support.
- Install the same debug APK on both devices.
- Keep both devices close together for the first test.
- Enable Bluetooth in Android Settings on both devices.
- Do not use this prototype for emergency communications.

## Pair and connect

1. On both phones, open Android Settings → Bluetooth and make the phones discoverable if the system requires it.
2. In Nexora Mesh on each phone, open **Cihazlar** and tap **Yakındaki cihazları ara**. Grant Bluetooth permissions when prompted.
3. Select the other phone. Android's system pairing prompt should appear; verify the pairing code on both phones and accept only if the codes match.
4. On phone A, return to Nexora Mesh and tap **Bağlantı bekle** (or **Gelen bağlantıyı bekle** in the chat tab).
5. On phone B, select the now-paired phone A from the device list to connect. If connection fails, swap which phone listens and which connects.
6. When the header shows **Bağlı**, send a short message each way. Confirm the other phone receives it while mobile data and Wi-Fi internet are turned off.

## Current boundaries

- Bluetooth system bonding is the initial trust gate; app-level QR/code identity verification is not present yet.
- This implementation handles one direct connection at a time.
- The initial transport does not persist chat history.
- No relay, store-and-forward, multi-hop mesh, Apple interoperability, or reviewed end-to-end encryption is included.
- CI can compile the Android app, but successful CI is not proof of physical-device Bluetooth behavior. Record Android versions, phone models, and test outcomes before considering this milestone verified.
