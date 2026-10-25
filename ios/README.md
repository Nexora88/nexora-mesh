# Apple platform prototype

`NexoraMeshApp.swift` is a native SwiftUI interface starter for iOS. It is not yet a complete Xcode project and does not implement Bluetooth discovery, peer pairing, message transport, or encryption.

## Next steps

1. Add an Xcode project and configure supported iOS deployment targets.
2. Design a shared cross-platform protocol and stable device identity format.
3. Implement an iOS-compatible local transport. Apple's platform restrictions mean Android Wi-Fi Direct APIs cannot be reused directly on iOS.
4. Implement explicit user-approved pairing, key verification, encrypted messaging, and disconnect/reconnect handling.
5. Test with real iPhones and Android devices with internet disabled.

The UI intentionally labels itself a prototype. Do not use it for emergency operations until radio transport, security, and field reliability have been independently tested.
