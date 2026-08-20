# HOLEN for Windows

Native Windows app — fast, local, on-device downloader. Same engine family as HOLEN Android (yt-dlp + ffmpeg), but using the Windows filesystem (no SAF) so it's **fast as hell**.

> No account, no telemetry, no hosted server. Downloads stay on your device.

## What it does

- Paste or **Share → HOLEN** an HTTPS link
- Direct files: instant, resumable via `Range` + `fetch`
- Media pages: local `yt-dlp` + `ffmpeg` with `--concurrent-fragments 2`, `--continue`, `--embed-metadata`
- Formats: Best MP4, 1080p, 720p, M4A, MP3, Original
- Playlist picker (up to 25 at once)
- Sequential queue with live speed/ETA, cancel/retry, staging-then-move (crash-safe)
- Native Windows folder picker (any path, SSD = fastest)
- Optional `cookies.txt` for gated content you can access

## Speed notes

- Uses Win32 filesystem directly (not SAF/content resolver)
- Staging dir per job → atomic move to chosen folder
- `concurrent-fragments 2` + resume — real network is the bottleneck, not HOLEN

## Requirements

- Windows 10/11 x64
- `yt-dlp.exe` + `ffmpeg.exe` (see below)

## Engine setup

HOLEN looks for binaries in:

- **Packaged app:** `resources/bin/yt-dlp.exe` + `resources/bin/ffmpeg.exe` (electron-builder `extraResources: bin/ → bin/`)
- **Dev:** `prod/windows/bin/yt-dlp.exe` + `prod/windows/bin/ffmpeg.exe`
- **Fallback:** system `PATH` (`yt-dlp`, `ffmpeg`)

Grab them:

```powershell
# dev
mkdir prod\windows\bin
# download yt-dlp.exe from https://github.com/yt-dlp/yt-dlp/releases
# download ffmpeg from https://ffmpeg.org/download.html (ffmpeg.exe)
```

HOLEN works without ffmpeg for direct/BEST_MP4; ffmpeg is needed for merges and audio extracts.

## Dev

```powershell
cd prod/windows
npm install
npm run dev          # electron-vite dev (renderer HMR)
```

## Build

```powershell
cd prod/windows
npm run build        # vite build (main/preload/renderer)
npm run dist:win     # NSIS installer → dist-elevated/HOLEN-*-x64-setup.exe
```

Icons: place `build/icon.ico` (256x256). If missing, electron-builder uses a default.

## File layout

```
prod/windows/
  src/main/         # Electron main: ytDlp, directDownloader, jobStore, store
  src/preload/      # Context-isolated bridge (window.holen)
  src/renderer/     # React Fluent-dark UI
  bin/              # yt-dlp.exe / ffmpeg.exe (gitignored, bundled as extraResources)
  build/icon.ico    # installer icon
```

## Storage

- Prefs + jobs: `%APPDATA%/holen/` (electron `userData/holen`)
- Downloads: chosen folder (default `%USERPROFILE%/Downloads/HOLEN`)
- Staging: `%APPDATA%/holen/holen/staging/<jobId>/`

## Updater

`electron-updater` is wired; publish via `electron-builder --publish always` with GitHub releases configured.

## License

MIT — see `LICENSE`.
