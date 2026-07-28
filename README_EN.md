<p align="right"><a href="README.md">简体中文</a></p>

# Codex Quota for Xiaomi Smart Band 10

View your **Codex weekly quota, next reset date, and available reset credits** on Xiaomi Smart Band 10.

<p align="center">
  <img src="assets/icon.svg" alt="Codex Quota icon" width="96">
</p>

Current version: **0.5.2 (local release candidate)**

[View changelog](CHANGELOG.md)

## Band screen previews

<table>
  <tr>
    <th align="center">Synced</th>
    <th align="center">Offline</th>
    <th align="center">Cached</th>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/band-synced.png" alt="Synced state" width="180"></td>
    <td align="center"><img src="docs/images/band-offline.png" alt="Offline state" width="180"></td>
    <td align="center"><img src="docs/images/band-cached.png" alt="Cached state" width="180"></td>
  </tr>
</table>

## Before you start

Install Xiaomi Fitness on the Android phone and connect Xiaomi Smart Band 10 there first. AstroBox is only used temporarily to sideload or upgrade the RPK; it is not part of the daily sync path.

## Requirements

- A Windows 10/11 x64 computer with Codex installed and in use
- Xiaomi Smart Band 10
- An Android phone with Xiaomi Fitness installed and connected to the band
- The phone and computer connected to the same trusted local network

The current architecture targets Android. AstroBox is used only to sideload or upgrade the band RPK; Xiaomi Fitness keeps the daily band connection.

Building the Android app from source also requires `xms-wearable-lib_1.4_release.aar` from Xiaomi's official developer channel in `android-app/app/libs/`; this third-party SDK is not redistributed here.

### Phone compatibility

- **Windows → Android dashboard**: Any Android 8.0+ phone that can install the APK should work in principle; a Xiaomi phone is not required.
- **Band sync**: Xiaomi Fitness must be installed and kept running, with Xiaomi Smart Band 10 paired there first. Android phones from other manufacturers may work, but OEM background, autostart, battery, and permission policies can affect continuous sync.
- **AstroBox**: It is only for sideloading or upgrading the band RPK. It does not replace Xiaomi Fitness for the daily connection. Without Xiaomi Fitness, the phone dashboard can still work, but band sync is not guaranteed.
- Xiaomi officially lists Android 8.0+ support for Smart Band 10; Xiaomi-only phone features are outside this project's dependency.

## Download

Download all three files from the same version on the [Releases](https://github.com/Vincent-hechuan/codex-quota-band/releases) page:

| Install on | File |
| --- | --- |
| Windows computer | `Codex-Quota-Setup-0.5.2.exe` |
| Android phone | `CodexQuota-0.5.2.apk` |
| Xiaomi Smart Band 10 (AstroBox only for sideloading) | `com.codex.quota.android.release.0.5.2.rpk` |

All three components should have the same version number.

## Installation

### 1. Install the Windows app

1. Run `Codex-Quota-Setup-0.5.2.exe`.
2. After installation, the app stays in the Windows notification area. If it is hidden, click the `^` icon in the taskbar.
3. The current test build is not commercially code-signed, so Windows may display an “Unknown publisher” warning. Download only from this repository and verify the SHA-256 value shown on the Release page.

### 2. Install the Android app and band RPK

1. Open AstroBox and enter the page for the connected Xiaomi Smart Band 10.
2. Import `com.codex.quota.android.release.0.5.2.rpk` and wait for the upgrade animation.
3. Install `CodexQuota-0.5.2.apk` on the Android phone.
4. Exit AstroBox after the upgrade and keep Xiaomi Fitness connected to the band.

## First pairing

1. Make sure the phone and computer are on the same Wi-Fi or trusted local network.
2. Right-click the Codex Quota icon in the Windows notification area and select 「显示配对信息…」 (Show pairing information).
3. Scan the QR code on the computer with the phone's system camera.
4. Open the link in CodexQuota and complete pairing.
5. Open 「Codex 额度」 on the band. Quota data should appear within a few seconds.

The QR code and six-digit pairing code expire quickly. You normally do not need to type the computer address. Use the advanced manual information in the Windows pairing window only if QR pairing fails.

## Troubleshooting

### Scanning does not open CodexQuota

Use the Android system camera to scan the Windows QR code and choose CodexQuota for the pairing link. AstroBox is not used for pairing.

### The plugin cannot find Windows

- Confirm that the phone and computer are on the same local network.
- If a VPN or proxy is enabled, allow AstroBox and local-network addresses to bypass it.
- When the Windows app starts for the first time, allow it through Windows Firewall on private networks.
- Avoid guest Wi-Fi, public Wi-Fi, and networks with client isolation enabled.

### The band shows offline or stops updating

- Confirm that Xiaomi Fitness is still running in the background and connected to the band.
- Disable phone-level battery restrictions for Xiaomi Fitness and CodexQuota, and grant Bluetooth/nearby-device permissions.
- If the phone or Xiaomi Fitness has restarted, open CodexQuota Settings and use “Check band connection” again.

### Only the weekly quota appears after reinstalling Codex, and reset credits show `--`

The weekly quota and available reset credits come from different local Codex data. Reinstalling Codex may preserve the weekly quota source while clearing the network cache that contains reset-credit information.

1. Open the Usage page in the Codex client.
2. Expand the reset-credit section and wait until its cards are fully displayed.
3. Right-click the Codex Quota icon in the Windows notification area and select 「立即刷新」 (Refresh now).
4. Wait about 5–10 seconds, then reopen the band app.

`0.5.2` keeps the last unexpired reset-credit data while the new cache is unavailable and shows 「缓存」 (Cached). It returns to 「已同步」 (Synced) automatically after Codex recreates the cache.

### Xiaomi Fitness competes for the connection

AstroBox should be closed after the RPK upgrade. If Xiaomi Fitness disconnects repeatedly, adjust the phone's background, autostart, and battery settings so Xiaomi Fitness and CodexQuota can coexist.

## Privacy

- Phone and band data moves only between your Windows computer, phone, and band. This project has no cloud relay; Windows contacts the official ChatGPT/Codex quota endpoint directly when confirming quota.
- It reads and displays only quota summaries. It does not read or transmit conversations, prompts, project files, or terminal content.
- It does not read ChatGPT/Codex cookies or passwords. Windows reads the existing local Codex access token only to confirm quota with the official endpoint; the token remains in Windows process memory and never enters logs, caches, diagnostics, the phone, or the band.
- You can revoke all paired devices from the Windows tray menu at any time.
- Use it only on a trusted local network. Do not expose the Windows service port to the public internet.

## Uninstall

- Windows: uninstall Codex Quota from Settings → Apps → Installed apps.
- Phone: uninstall the CodexQuota APK from Android Settings.
- Band: uninstall 「Codex 额度」 through AstroBox.

<details>
<summary>Developer build and test instructions</summary>

Requires Node.js 24+, PowerShell, a Rust/WASI environment, and the Xiaomi Vela quick-app toolchain.

```powershell
npm install
npm test
npm run test:plugin
npm run build:win
npm run build:plugin

Set-Location band-app
npm install
npm run build
```

See [docs/security.md](docs/security.md) for the security model.

</details>

## Notice

This is a community open-source project, not an official product of OpenAI, Xiaomi, or AstroBox.

Licensed under the [MIT License](LICENSE).
