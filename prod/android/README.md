# Holen for Android

Holen is a native, on-device downloader for Android 10 and newer. Direct HTTPS
files use Android networking; public media pages use the bundled
`youtubedl-android`, Python, yt-dlp, and FFmpeg runtime. There is no Holen
server, account, WebView, analytics, or telemetry.

Only download files you own or are authorized to save. Holen does not bypass
DRM, logins, age gates, or access controls, and it does not promise that every
site supported by yt-dlp will continue to work.

## Build

Requirements:

- JDK 17
- Android SDK Platform 37.1 and Build Tools 36.0.0

The app still targets API 36; compile SDK 37.1 is required by the pinned
Lifecycle 2.11.0 libraries and does not opt the app into API 37 runtime
behavior.

```bash
cd android
./gradlew testEmulatorDebugUnitTest lintEmulatorDebug assembleEmulatorDebug
```

Debug builds contain x86 and x86_64 native libraries for emulators. Public
release variants contain ARM libraries only:

```bash
./gradlew assembleArm64Release assembleArmv7Release assembleUniversalRelease
```

The project pins Gradle 9.5, Android Gradle Plugin 9.3.0, Kotlin 2.3.21, and all
runtime dependency versions in `gradle/libs.versions.toml`.

## Storage and recovery

The first launch asks for a destination through Android's system folder picker.
Holen persists only that read/write tree grant. Active work is staged under the
app-specific external files directory and then byte-verified while copying to
the selected folder.

- Network failures preserve `.part` files so Retry can resume.
- Explicit Cancel removes staging data.
- Clear Finished removes history, not downloaded files.
- Delete File asks for confirmation, deletes the saved document, and then
  removes history.
- Files in the selected folder remain after uninstall.
- Interrupted jobs become queued and resumable when Holen is next opened.

Android 15 and newer impose a six-hour-per-day limit on `dataSync` foreground
services. If Android stops a very long transfer at that boundary, Holen leaves
it resumable instead of discarding the partial file.

## Signed GitHub releases

Tags matching `android-v*` trigger `.github/workflows/android-release.yml`.
Configure these repository secrets:

- `HOLEN_KEYSTORE_BASE64`
- `HOLEN_KEYSTORE_PASSWORD`
- `HOLEN_KEY_ALIAS`
- `HOLEN_KEY_PASSWORD`

Back up the signing key outside GitHub and reuse it permanently; Android will
not accept an upgrade signed by a different key. The workflow publishes
ARM64, ARMv7, universal APKs, and `SHA256SUMS`.

Before broad distribution, register `com.yashasvm.holen` and the release
certificate in Android Developer Console. GitHub distribution does not exempt
the app from Android developer verification.

## Release checklist

Before tagging, manually test Android 10, 13, and 16, including a physical
lower-midrange ARM phone:

- Direct PDF, ZIP, and media links
- A public YouTube video and playlist, plus two other public yt-dlp sites
- Best MP4, 1080p, 720p, M4A, and MP3
- Share and view intents, backgrounding, screen lock, network loss, process
  death, reboot, cancellation, low storage, revoked folder access, and
  duplicate names
- Engine update, failed update fallback, reset, and APK upgrade without losing
  history or the folder grant

Live extractor tests remain manual because public sites change too often for
deterministic CI.

## License and notices

Holen for Android is GPL-3.0; see `LICENSE`. Dependency and font notices are in
`THIRD_PARTY_NOTICES.md`. The existing hosted and OSS applications outside this
directory keep their own licensing and behavior.
