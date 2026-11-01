# iOS Info.plist & background modes (required reading)

When you create the real Xcode project, add only the keys that match the behaviour you ship.

## Bluetooth usage description (required)

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Nexora Mesh uses Bluetooth to discover and exchange messages with nearby devices when you enable discovery.</string>
```

## Background modes — only what the OS actually supports

```xml
<key>UIBackgroundModes</key>
<array>
  <string>bluetooth-central</string>
  <string>bluetooth-peripheral</string>
</array>
```

### Hard constraints (do not fight the OS)

1. iOS may suspend or coalesce scans/advertising when the app is backgrounded.
2. State restoration is recommended if you use background modes.
3. There is **no** supported way to guarantee continuous background mesh relay on iOS.
4. Continuous background advertising for “always-on relay” will be throttled and may lead to App Review rejection.
5. Prefer opportunistic store-and-forward.
