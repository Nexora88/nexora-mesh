# Android release signing

The normal CI build remains a debug APK for easy test installation. A separate optimized release APK is built only when the repository owner configures all four Actions secrets:

- `MESH_RELEASE_KEYSTORE_BASE64` — base64-encoded private Android keystore file
- `MESH_RELEASE_STORE_PASSWORD` — keystore password
- `MESH_RELEASE_KEY_ALIAS` — signing key alias
- `MESH_RELEASE_KEY_PASSWORD` — signing key password

The release build enables R8 code shrinking/obfuscation and resource shrinking. The private keystore must remain private: never commit it, upload it as a public artifact, or paste passwords into chat/issues. Keep a secure offline backup; losing the key can prevent updates to an already distributed package.

When configured, the workflow uploads `nexora-mesh-android-release` separately from the debug artifact. If secrets are absent, the signed release step is skipped; CI does not silently create or substitute a signing key.

Native release libraries are built with the Android NDK release configuration. Verify the final APK's signature, ABI contents, crash behavior, and Bluetooth operation on physical devices before distributing it broadly.
