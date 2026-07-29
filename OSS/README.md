# HOLEN OSS

HOLEN OSS is the MIT-licensed, local self-hosted edition. It has no accounts,
hosted services, or Android download delivery. For the fully on-device Android
app, see the [latest HOLEN Android release](https://github.com/YashasVM/HOLEN/releases/latest).

Holen is a small, self-hosted YouTube downloader. It has no accounts, database users, analytics, third-party authentication, or external services: paste a video or Short URL, choose a format, and save the completed file from the browser.

Only download media you have the right to save and use. You are responsible for complying with the source platform's terms and applicable law.

## What is included

- React + Vite single-page UI
- FastAPI queue backed by a local SQLite database
- Native Python backend with `yt-dlp` and host-provided `ffmpeg`
- MP4 video, M4A audio, and MP3 output options
- Two concurrent jobs by default, resumable downloads, retry handling, and four concurrent media fragments per job
- A bounded on-disk cache that removes the oldest completed files when it fills
- Per-network request limits suitable for a small private deployment

## Quick start

Requirements: Node.js 18+ (including `npm`). The launcher automatically downloads Python 3.10+ and `ffmpeg` when either is missing.

```bash
./run.sh
```

That one command creates `.env` from safe defaults when needed, installs missing system runtime packages, installs the isolated Python and frontend dependencies, builds the UI, and starts the local server. Open [http://localhost:8080](http://localhost:8080). The first run can take a few minutes.

On macOS the launcher uses Homebrew; on Windows it uses `winget`; on Linux it supports `apt`, `dnf`, `pacman`, `apk`, and `zypper`. It will request administrator permission where the package manager needs it. Node.js cannot be bootstrapped by this command because `npx` itself runs on Node—install Node.js 18+ first.

To use your own configuration, copy `.env.example` to `.env`, adjust it, then run `./run.sh`.

### Run without cloning

With Node.js 18+ installed, run this from any empty working directory:

```bash
npx github:YashasVM/HOLEN
```

The launcher downloads the release package, copies the runnable project to `./holen`, and starts it directly on the host—Docker is not required. Use `--dir <directory>` to choose a different destination. Running the command again updates an existing Holen installation while preserving its `.env`, downloads, and queue data.

To stop the stack:

On macOS or Linux, run `kill "$(cat holen.pid)"`. In PowerShell, run `Stop-Process -Id (Get-Content holen.pid)`.

Run that command from the installation directory (for the `npx` command, that is `./holen`). Server output is written to `holen.log`.

Completed files and queue data are stored in `downloads/` and `data/`. Both are ignored by Git. Removing either directory removes its local data.

## Using `yt.local`

Set `APP_PORT=80` in `.env`, then add this line to the machine that will use the app:

```text
127.0.0.1 yt.local
```

Open [http://yt.local](http://yt.local). On Linux and macOS edit `/etc/hosts`; on Windows edit `C:\Windows\System32\drivers\etc\hosts` as an administrator. If port 80 is occupied, use the default `http://localhost:8080` instead.

## Configuration

Copy `.env.example` to `.env`; its defaults are safe to use as-is for a personal server.

| Setting | Default | Meaning |
| --- | --- | --- |
| `APP_PORT` | `8080` | Host port for the web app. |
| `APP_BIND_HOST` | `127.0.0.1` | Listening address. Set to `0.0.0.0` or `::` only for a protected private-network deployment. |
| `MAX_ACTIVE_JOBS` | `2` | Downloads that may run at once. |
| `MAX_QUEUED_JOBS` | `20` | Maximum waiting downloads. |
| `MAX_DURATION_SECONDS` | `7200` | Maximum source duration (two hours). |
| `CACHE_LIMIT_GB` | `20` | Cache size before oldest completed files are evicted. |
| `ANALYZE_REQUESTS_PER_MINUTE` | `8` | Metadata checks per IP per minute. |
| `JOB_REQUESTS_PER_HOUR` | `12` | New download jobs per IP per hour. |
| `YTDLP_COOKIES_FILE` | unset | Optional absolute host path to a Netscape `cookies.txt` file. |

Cookies are optional. If you use them, set `YTDLP_COOKIES_FILE` to the absolute path of a local Netscape-format cookies file. Keep it outside this repository; `.gitignore` excludes it.

## Local development

Run the backend and frontend in separate terminals:

```bash
cd backend
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

```bash
cd frontend
npm install
npm run dev
```

Vite proxies `/api` to `http://127.0.0.1:8000` while developing. Create `downloads/` and `data/` if necessary, or set `DOWNLOAD_DIR` and `SQLITE_PATH` before starting the backend. For the native launcher, FastAPI serves the built Vite files itself on one port.

## Reference

- [Architecture](../docs/architecture.md)
- [Troubleshooting](../docs/troubleshooting.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

## Public deployment note

This project deliberately has no login system. Do not expose it directly to the public internet: anybody who can reach the page can queue, view, cancel, remove, and download files. Keep it on a trusted network, or put it behind your own VPN, reverse-proxy authentication, firewall rules, and rate limiting.

The native server binds to `127.0.0.1` by default. Put it behind your own reverse proxy only if you also add access controls. The application only accepts YouTube URLs, enforces queue/cache limits, and has basic IP throttling, but those safeguards are not a substitute for access control.

To intentionally make the launcher reachable from your local network, set `APP_BIND_HOST=0.0.0.0` (or `::` for IPv6) in `.env`. The launcher prints a warning for this opt-in mode. Do this only when a VPN, firewall rule, or authenticated reverse proxy limits access to people you trust.

## Checks

```bash
cd frontend && npm run build
cd ../backend && python3 -m compileall -q app
cd .. && node --check bin/holen.js
```

## License

MIT. See the repository [LICENSE](../LICENSE).
