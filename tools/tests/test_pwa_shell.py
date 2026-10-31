from pathlib import Path
import json
import unittest

ROOT = Path(__file__).resolve().parents[2]
WEB = ROOT / "web"


class PwaShellTests(unittest.TestCase):
    def test_manifest_is_valid_and_standalone(self):
        manifest = json.loads((WEB / "manifest.webmanifest").read_text())
        self.assertEqual(manifest["display"], "standalone")
        self.assertEqual(manifest["start_url"], "./")
        self.assertTrue((WEB / manifest["icons"][0]["src"].removeprefix("./")).is_file())

    def test_shell_assets_exist_and_are_referenced(self):
        html = (WEB / "index.html").read_text()
        for asset in ("styles.css", "app.js", "manifest.webmanifest", "icon.svg"):
            self.assertIn(asset, html)
            self.assertTrue((WEB / asset).is_file())

    def test_ui_does_not_claim_browser_radio_or_fake_send(self):
        html = (WEB / "index.html").read_text()
        js = (WEB / "app.js").read_text()
        self.assertIn("native radyo köprüsü", html.lower())
        self.assertIn("gerçek radyo köprüsü", js.lower())
        self.assertIn("Gönderilmedi", js)
        self.assertIn("verified:false", js)

    def test_desktop_native_ble_controls_are_not_faked_in_browser(self):
        html = (WEB / "index.html").read_text()
        js = (WEB / "app.js").read_text()
        self.assertIn('id="desktop-scan"', html)
        self.assertIn('id="desktop-stop"', html)
        self.assertIn("window.__TAURI__?.core?.invoke", js)
        self.assertIn("Native BLE yalnızca kurulu", js)

    def test_tauri_desktop_shell_declares_local_frontend_and_ble_host(self):
        desktop = ROOT / "desktop" / "src-tauri"
        config = json.loads((desktop / "tauri.conf.json").read_text())
        cargo = (desktop / "Cargo.toml").read_text()
        rust = (desktop / "src" / "lib.rs").read_text()
        self.assertEqual(config["build"]["frontendDist"], "../../web")
        self.assertIn('btleplug = "0.11"', cargo)
        self.assertIn("start_ble_discovery", rust)
        self.assertIn("nearby_ble_devices", rust)

    def test_service_worker_caches_offline_shell(self):
        worker = (WEB / "sw.js").read_text()
        self.assertIn("addAll(ASSETS)", worker)
        self.assertIn("e.request.mode==='navigate'", worker)


if __name__ == "__main__":
    unittest.main()
