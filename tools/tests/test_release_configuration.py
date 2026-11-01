from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ReleaseConfigurationTests(unittest.TestCase):
    def read(self, relative: str) -> str:
        path = ROOT / relative
        self.assertTrue(path.is_file(), f"Required release file is missing: {relative}")
        return path.read_text(encoding="utf-8")

    def test_android_project_is_real_and_native_build_is_wired(self):
        settings = self.read("android/settings.gradle.kts")
        app = self.read("android/app/build.gradle.kts")
        manifest = self.read("android/app/src/main/AndroidManifest.xml")
        cmake = self.read("android/app/src/main/cpp/CMakeLists.txt")
        jni = self.read("android/app/src/main/cpp/mesh_jni.cpp")
        self.read("android/app/src/main/java/org/nexora/mesh/MainActivity.kt")
        self.read("android/app/src/main/java/org/nexora/mesh/BleGattTransport.kt")
        self.assertIn('include(":app")', settings)
        self.assertIn("externalNativeBuild", app)
        self.assertIn("compileSdk = 35", app)
        self.assertIn("BLUETOOTH_SCAN", manifest)
        self.assertIn("mesh_core", cmake)
        self.assertIn("nm_wire_validate", jni)

    def test_android_and_ios_share_the_same_ble_service_contract(self):
        android = self.read("android/app/src/main/java/org/nexora/mesh/BleGattTransport.kt")
        ios = self.read("ios/MeshBLETransport.swift")
        def uuid_on_line(source: str, marker: str) -> str | None:
            line = next((line for line in source.splitlines() if marker in line), "")
            match = re.search(r'"([0-9a-fA-F-]{36})"', line)
            return match.group(1).lower() if match else None

        self.assertEqual(uuid_on_line(android, "val SERVICE_UUID"), uuid_on_line(ios, "static let serviceUUID"))
        self.assertEqual(uuid_on_line(android, "val DATA_UUID"), uuid_on_line(ios, "static let dataUUID"))
        self.assertIsNotNone(uuid_on_line(android, "val SERVICE_UUID"))
        self.assertIsNotNone(uuid_on_line(android, "val DATA_UUID"))

    def test_android_workflows_build_and_upload_apk(self):
        ci = self.read(".github/workflows/ci.yml")
        release = self.read(".github/workflows/android-release.yml")
        for workflow in (ci, release):
            self.assertIn("Require Android SDK license approval", workflow)
            self.assertIn("ANDROID_SDK_LICENSES_ACCEPTED", workflow)
            self.assertIn("accept-android-sdk-licenses:", workflow)
            self.assertIn("cargo ndk", workflow)
            self.assertIn("testDebugUnitTest", workflow)
            self.assertIn("assembleDebug", workflow)
            self.assertIn("upload-artifact@v4", workflow)
            self.assertIn("app-debug.apk", workflow)

    def test_apple_project_and_framework_script_exist(self):
        project = self.read("ios/project.yml")
        script = self.read("scripts/build-ios-xcframework.sh")
        workflow = self.read(".github/workflows/ci.yml")
        self.read("ios/MeshBLETransport.swift")
        self.read("ios/MeshSecureChat.swift")
        self.assertIn("NexoraMeshTests", project)
        self.assertIn("xcodebuild -create-xcframework", script)
        self.assertIn("aarch64-apple-ios", script)
        self.assertIn("nexora-mesh-ios-simulator-app", workflow)
        self.assertIn("Debug-iphonesimulator/NexoraMesh.app", workflow)

    def test_desktop_targets_and_installable_web_shell_exist(self):
        tauri = self.read("desktop/src-tauri/tauri.conf.json")
        workflow = self.read(".github/workflows/desktop-native.yml")
        self.read("web/manifest.webmanifest")
        self.read("web/sw.js")
        self.assertIn('"msi"', tauri)
        self.assertIn('"dmg"', tauri)
        self.assertIn('"deb"', tauri)
        for target in ("windows-latest", "macos-latest", "ubuntu-24.04"):
            self.assertIn(target, workflow)
        self.assertIn("nexora-mesh-pwa", workflow)

    def test_security_limitations_are_not_hidden(self):
        threat = self.read("docs/THREAT_MODEL.md")
        gates = self.read("docs/PLATFORM_AND_RELEASE_GATES.md")
        self.assertIn("Forward secrecy is not provided", threat)
        self.assertIn("Independent security review", gates)
        self.assertIn("Do not claim yet", gates)


if __name__ == "__main__":
    unittest.main()
