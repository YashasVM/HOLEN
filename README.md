# HOLEN

HOLEN is a free, on-device Android downloader for direct files and supported media pages. Paste a link or share it from another app, choose a format, and let HOLEN download in the background.

## Download HOLEN for Android

### [Download HOLEN V3 for Android (ARM64)](https://github.com/YashasVM/HOLEN/releases/download/V3/HOLEN-v3.3.0-arm64-debug.apk)

- Requires Android 10 or newer.
- ARM64 is recommended for modern phones. Galaxy S24 Ultra users should download the ARM64 APK.
- This V3 asset is for ARM64 phones, including the Galaxy S24 Ultra.
- Verify the download against the release `SHA256SUMS-V3` file.

## What HOLEN does

HOLEN downloads direct HTTPS files and supported public media pages on the device. It offers video and audio format choices, background downloads with notifications, playlist support, resume/retry/cancel controls, and a user-selected output folder. Media-page processing uses the bundled engine; direct files use Android networking.

HOLEN has no Android account requirement, analytics, telemetry, hosted server dependency, or Clerk integration. Some supported pages can require account cookies that you are authorized to use; cookies never bypass DRM or other access controls.

## Choose your edition

| Edition | Location | Purpose | License |
| --- | --- | --- | --- |
| HOLEN Android | [`prod/android/`](prod/android/) | Native, fully on-device Android downloader | GPL-3.0 |
| HOLEN OSS | [`OSS/`](OSS/) | Local self-hosted downloader without accounts or hosted services | MIT |
| HOLEN production server | [`prod/backend/`](prod/backend/), [`prod/frontend/`](prod/frontend/) | Private authenticated Clerk/Docker deployment | MIT, subject to dependency licenses |
| Root self-hosted source | [`backend/`](backend/), [`frontend/`](frontend/), [`bin/`](bin/) | Current root-level self-hosted launcher and development source | MIT |

These editions do not share runtime state. Android does not require the server or use Clerk. OSS does not provide Android downloads. The production server is intended for private authenticated deployments. Each edition's own README and license file controls that edition.

## Android quick start

1. Download the ARM64 APK for a modern phone, or the universal APK if unsure.
2. Install it from the release page and complete Android's installer prompts.
3. On first launch, complete the setup and choose an output folder.
4. Paste an HTTPS link into HOLEN, or share it from another app and choose **Download with HOLEN**.

See the [Android guide](prod/android/README.md) for storage, cookies, troubleshooting, build, signing, and release verification.

## How sharing to HOLEN works

From an app that can share text links, choose **Share**, select **Download with HOLEN**, then select a supported format. HOLEN queues the transfer and continues in the background; the source app remains available after you queue it.

Only download files and media you own or are authorized to save. You are responsible for complying with source-platform terms and applicable law.

## Repository structure

- [`prod/android/`](prod/android/) — Android app and Android-specific notices.
- [`OSS/`](OSS/) — account-free local self-hosted server.
- [`prod/`](prod/) — private Clerk/Docker production deployment.
- [`backend/`](backend/), [`frontend/`](frontend/), [`bin/`](bin/) — root self-hosted launcher and development source.
- [`docs/releases/HOLEN-V3.md`](docs/releases/HOLEN-V3.md) — V3 release notes.

## Licenses

- HOLEN Android is GPL-3.0 because it includes GPL-licensed media components. See [`prod/android/LICENSE`](prod/android/LICENSE) and [Android third-party notices](prod/android/THIRD_PARTY_NOTICES.md).
- HOLEN OSS is MIT-licensed; see the repository [`LICENSE`](LICENSE).
- The root self-hosted source and the private production server are MIT-licensed, subject to their dependency licenses. See [`LICENSE`](LICENSE) and the applicable edition documentation.

## Responsible-use notice

HOLEN is not a tool for bypassing DRM, access controls, or terms of service. Authentication cookies may enable only content your account is already authorized to access; they may expire and are not a universal age-restriction workaround.

## Security and privacy

The Android app processes downloads on the device and has no analytics or telemetry. Optional `cookies.txt` data is stored only in private on-device storage; never commit, share, or report cookies. For supported versions, vulnerability reporting, and secret-reporting guidance, see [SECURITY.md](SECURITY.md).

## Development and contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a change. Use the edition-specific README for local setup and checks.

Useful references: [Android guide](prod/android/README.md), [OSS guide](OSS/README.md), [private production deployment](prod/README.md), [changelog](CHANGELOG.md), [architecture](docs/architecture.md), and [troubleshooting](docs/troubleshooting.md).
