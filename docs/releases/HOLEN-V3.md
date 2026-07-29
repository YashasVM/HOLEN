# HOLEN V3 release notes

HOLEN V3 is the public Android release for version 3.3.0 (`android-v3.3.0`).
The installed app is named **HOLEN**.

## Highlights

- Native Android app for Android 10 and newer.
- Share a link from another app to **Download with HOLEN**, or paste a link.
- Direct-file and supported media-page downloads.
- Video and audio format choices, including MP4, M4A, and MP3 where supported.
- Background downloads with notifications, playlist support, and resume/retry/
  cancel controls.
- User-selected output folder with Android's system folder picker.
- Cinematic onboarding and a responsible-download acknowledgment.
- Advanced pasted Netscape `cookies.txt` support stored only in private
  on-device storage for authorized media-page access.
- Engine updating with a bundled fallback.

## Privacy and responsible use

HOLEN processes Android downloads on-device and has no analytics or telemetry.
Cookies are sensitive, may expire, and can only help access content an account
is already authorized to access. They cannot bypass DRM or other access
controls. Download only files and media you own or are authorized to save, and
follow applicable law and source-platform terms.

## Download and verification

Download HOLEN from the [latest GitHub Release](https://github.com/YashasVM/HOLEN/releases/latest).
Android 10 or newer is required. ARM64 is recommended for modern devices,
including the Galaxy S24 Ultra; use the universal APK when the ABI is unknown.
Verify the matching asset against `SHA256SUMS` before installing.

## Licensing

HOLEN Android is GPL-3.0 because it includes GPL-licensed media components.
See [the Android license](../../prod/android/LICENSE) and
[third-party notices](../../prod/android/THIRD_PARTY_NOTICES.md). HOLEN OSS is
a separate MIT-licensed edition; server editions are MIT-licensed subject to
their dependency licenses.
