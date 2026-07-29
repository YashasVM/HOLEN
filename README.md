<h1 align="center">HOLEN</h1>

<p align="center">
  <em>A clean, on-device downloader for Android.</em>
</p>

<p align="center">
  <img src="https://img.shields.io/github/stars/YashasVM/HOLEN?style=flat-square&color=111111&label=stars" alt="Stars">
  <img src="https://img.shields.io/github/v/release/YashasVM/HOLEN?style=flat-square&color=111111&label=release" alt="Release">
  <img src="https://img.shields.io/badge/android-native-111111?style=flat-square" alt="Native Android">
</p>

<p align="center">
  <strong>Share a link &middot; choose a format &middot; download on your device</strong><br>
  <sub>HOLEN is a native Kotlin Android app for direct HTTPS files and supported media pages, with no account, analytics, or hosted download server.</sub>
</p>

---

## Description

HOLEN keeps downloading simple: paste an HTTPS link or share it from another app, choose the format you want, and let Android continue the transfer in the background. Downloads stay on your device and save to the folder you choose.

> [!IMPORTANT]
> HOLEN V3.3.1 is for Android 10 and newer. Modern Samsung and Pixel phones, including the Galaxy S24 Ultra, should install the ARM64 APK from [HOLEN V3](https://github.com/YashasVM/HOLEN/releases/tag/V3).

## What is HOLEN?

HOLEN is an Android-first downloader built with Kotlin and Jetpack Compose. Direct files use Android networking; supported media pages use the bundled yt-dlp and FFmpeg runtime.

```text
Share or paste a link -> HOLEN -> Android background download -> Your selected folder
```

## Features

| Feature | Details |
|---|---|
| **Native Android App** | Kotlin and Jetpack Compose UI; no WebView or required server. |
| **Share to HOLEN** | Send a supported HTTPS link from another app straight into the format picker. |
| **Direct & Media Downloads** | Save direct files and supported public media pages. |
| **Format Choices** | Best MP4, 1080p MP4, 720p MP4, M4A, MP3, or the original direct file. |
| **Background Transfers** | Notifications, playlist support, resume, retry, cancel, and safe staging. |
| **Your Folder** | Android's system folder picker keeps destination access explicit. |
| **Private by Default** | No analytics or telemetry. Optional `cookies.txt` stays in private app storage. |

## APKs

| APK | Use on |
|---|---|
| [`HOLEN-v3.3.1-arm64-debug.apk`](https://github.com/YashasVM/HOLEN/releases/download/V3/HOLEN-v3.3.1-arm64-debug.apk) | Modern ARM64 phones, including Galaxy S24 Ultra |

> [!NOTE]
> This public V3 package is ARM64-only. Verify it against [SHA256SUMS-V3](https://github.com/YashasVM/HOLEN/releases/download/V3/SHA256SUMS-V3) before installing.

## Quick start

1. Download and install the ARM64 APK above.
2. Complete onboarding and select a download folder.
3. Paste an HTTPS URL, or share one to **Download with HOLEN**.
4. Choose a format and keep HOLEN running while Android finishes the download.

## Build

Requirements: JDK 17, Android SDK Platform 37.1, and Build Tools 36.0.0.

```powershell
cd prod/android
.\gradlew.bat testEmulatorDebugUnitTest assembleArm64Debug --no-daemon
```

For installation, signing, and release details, see [the Android guide](prod/android/README.md).

## Repository layout

```text
.
|-- prod/android/       # Native HOLEN Android app
|-- docs/releases/      # Release notes
|-- OSS/                # Account-free self-hosted edition
|-- prod/               # Private production deployment
|-- backend/ frontend/  # Root self-hosted source
`-- README.md
```

## Release notes

Read the current [HOLEN V3 release notes](docs/releases/HOLEN-V3.md).

## Responsible use

Download only files and media you own or are authorized to save. HOLEN does not bypass DRM, accounts, age gates, access controls, or source-platform terms.

<p align="center">
  <a href="https://github.com/YashasVM"><strong>Made by @yashas.vm</strong></a><br>
  <sub>Small, direct, and always getting sharper.</sub>
</p>
