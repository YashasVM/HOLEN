# HOLEN for Android

HOLEN is a native, fully on-device downloader for Android 10 and newer. Direct HTTPS files use Android networking; supported media pages use the bundled `youtubedl-android`, Python, yt-dlp, and FFmpeg runtime. HOLEN does not require a HOLEN server, Clerk, a WebView sign-in, analytics, or telemetry.

Download the current signed builds from the [latest GitHub Release](https://github.com/YashasVM/HOLEN/releases/latest). Use the ARM64 APK on modern phones, including the Galaxy S24 Ultra; use the universal APK only when the ABI is unknown. Verify the matching `SHA256SUMS` entry before installing.

Only download files you own or are authorized to save. HOLEN does not bypass DRM or access controls, and supported-site availability can change.

## Usage, sharing, and storage

First launch presents the cinematic onboarding, a responsible-download acknowledgment, and Android's system folder picker. HOLEN persists only the read/write tree grant. Later, share a text/plain HTTPS link to **Download with HOLEN** or paste it into the app, choose a format, and queue it. Queuing closes the share dialog so the source app stays visible while HOLEN downloads in the background.

- Media downloads provide video/audio format choices and playlist support.
- Background work shows notifications and supports resume, retry, and cancel.
- Active work is staged in app-specific external storage, then byte-verified while copying to the selected folder.
- Network failures preserve `.part` files for Retry; explicit Cancel removes staging data.
- Clear Finished removes history, not downloaded files. Delete File confirms deletion of the saved document and then removes history.
- Files in the selected folder remain after uninstall. Interrupted jobs become queued and resumable when HOLEN next opens.

Android 15 and newer impose a six-hour-per-day limit on `dataSync` foreground services. If Android stops a very long transfer at that boundary, HOLEN preserves resumable partial data.

## Cookies

Advanced settings can accept a pasted Netscape-format `cookies.txt` file for an account you own or are authorized to use. HOLEN keeps it only in private on-device storage and applies it only to media analysis and media downloads; it is never used for direct HTTPS downloads, engine updates, FFmpeg, logs, notifications, or diagnostics.

Cookies are sensitive, can expire, and may grant account access. Do not share them, place them in source control, or include them in bug reports. They enable only content that the authenticated account is already authorized to access and cannot bypass DRM or other access controls. Clear cookies in Advanced settings whenever they are no longer needed.

## Build

Requirements:

- JDK 17
- Android SDK Platform 37.1 and Build Tools 36.0.0

The app targets API 36; compile SDK 37.1 is required by the pinned Lifecycle 2.11.0 libraries and does not opt the app into API 37 runtime behavior.

From this directory:

```bash
./gradlew testEmulatorDebugUnitTest lintEmulatorDebug assembleEmulatorDebug
```

Emulator debug builds contain x86 and x86_64 native libraries. Never distribute them to physical-device users. A requested ARM64 debug handoff is built with `:app:assembleArm64Debug` and must be copied as `prod/APKs/HOLEN-v3.3.0-arm64-debug.apk` after ABI and version verification.

Release builds are signed and produced by the tag workflow:

```bash
./gradlew assembleArm64Release assembleArmv7Release assembleUniversalRelease
```

The universal APK supports `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. The project pins Gradle 9.5, Android Gradle Plugin 9.3.0, Kotlin 2.3.21, and runtime dependency versions in `gradle/libs.versions.toml`.

## Signing and release verification

Tags matching `android-v*` trigger `.github/workflows/android-release.yml`. Configure these repository secrets only in the tag release environment:

- `HOLEN_KEYSTORE_BASE64`
- `HOLEN_KEYSTORE_PASSWORD`
- `HOLEN_KEY_ALIAS`
- `HOLEN_KEY_PASSWORD`

Back up the signing key outside GitHub and reuse it permanently; Android upgrades must use the same signing key. The release workflow publishes signed ARM64, ARMv7, and universal APKs plus `SHA256SUMS`, and verifies signing, ABI contents, manifest label/version, and checksums before publication.

APK and AAB files are release artifacts and remain ignored by Git. Use GitHub Release assets rather than committing local packages. Before broad distribution, complete any required Android developer verification for `com.yashasvm.holen` and its release certificate.

## Release checklist

Before tagging, manually test Android 10, 13, and 16, including a physical lower-midrange ARM phone:

- Direct PDF, ZIP, and media links.
- A public YouTube video and playlist, plus two other public yt-dlp sites.
- Best MP4, 1080p, 720p, M4A, and MP3.
- Share intents, backgrounding, screen lock, network loss, process death, reboot, cancellation, low storage, revoked folder access, and duplicate names.
- Engine update, failed-update fallback, reset, and APK upgrade without losing history or the folder grant.
- Valid and invalid cookies.txt input, cookie clearing, and confirmation that direct files do not use cookies.

Live extractor checks remain manual because public sites change too often for deterministic CI.

## License and notices

HOLEN Android is GPL-3.0 because it includes GPL-licensed media components. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md); third-party/runtime components retain their own notices. HOLEN OSS is a separate MIT-licensed edition. The root self-hosted source and private production server likewise have their own behavior and licensing.
