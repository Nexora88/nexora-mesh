# Nexora Mesh web console

The PWA source is in the web/ directory. The Publish Nexora Mesh web console workflow deploys that directory to GitHub Pages after changes reach main.

One-time repository setup:
1. Open Settings → Pages.
2. Under Build and deployment, choose GitHub Actions as the source.
3. After the first successful deployment, open the workflow run and use its page_url output.

The web app is a local management/preview console. It does not currently send native Bluetooth messages and must not be presented as the Android/iOS messenger transport. It can be installed from a supported browser using Add to Home Screen after the Pages site is live.

The new SVG brand mark and PWA icon are included in this source. The Android and iOS native apps have separate native UI assets.
