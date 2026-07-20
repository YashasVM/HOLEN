# Holen

Holen is a small, self-hosted YouTube downloader. It has no accounts, database users, analytics, third-party authentication, or external services: paste a video or Short URL, choose a format, and save the completed file from the browser.

Only download media you have the right to save and use. You are responsible for complying with the source platform's terms and applicable law.

## What is included

- React + Vite single-page UI
- FastAPI queue backed by a local SQLite database
- `yt-dlp` and `ffmpeg` in the backend image
- MP4 video, M4A audio, and MP3 output options
- Two concurrent jobs by default, resumable downloads, retry handling, and four concurrent media fragments per job
- A bounded on-disk cache that removes the oldest completed files when it fills
- Per-network request limits suitable for a small private deployment

## Quick start

Requirements: Docker Engine with Docker Compose v2.

```bash
cp .env.example .env
docker compose up --build -d
curl http://localhost:8080/api/health
```

Open [http://localhost:8080](http://localhost:8080). The first image build downloads the frontend and backend dependencies, so it can take a few minutes.

To stop the stack:

```bash
docker compose down
```

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
| `MAX_ACTIVE_JOBS` | `2` | Downloads that may run at once. |
| `MAX_QUEUED_JOBS` | `20` | Maximum waiting downloads. |
| `MAX_DURATION_SECONDS` | `7200` | Maximum source duration (two hours). |
| `CACHE_LIMIT_GB` | `20` | Cache size before oldest completed files are evicted. |
| `ANALYZE_REQUESTS_PER_MINUTE` | `8` | Metadata checks per IP per minute. |
| `JOB_REQUESTS_PER_HOUR` | `12` | New download jobs per IP per hour. |
| `YTDLP_COOKIES_PATH` | unset | Optional absolute host path to a Netscape `cookies.txt` file. |

Cookies are optional. If you use them, keep the file outside this repository; Compose mounts it read-only and `.gitignore` excludes it.

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

Vite proxies `/api` to `http://127.0.0.1:8000` while developing. Create `downloads/` and `data/` if necessary, or set `DOWNLOAD_DIR` and `SQLITE_PATH` before starting the backend.

## Reference

- [Architecture](docs/architecture.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

## Public deployment note

This project deliberately has no login system. Do not expose it directly to the public internet: anybody who can reach the page can queue, view, cancel, remove, and download files. Keep it on a trusted network, or put it behind your own VPN, reverse-proxy authentication, firewall rules, and rate limiting.

The backend is not published as a Docker host port; the frontend is the only public container entry point. The application only accepts YouTube URLs, enforces queue/cache limits, and has basic IP throttling, but those safeguards are not a substitute for access control.

## Checks

```bash
cd frontend && npm run build
cd ../backend && python3 -m compileall -q app
cd .. && docker compose config --quiet
```

## License

MIT. See [LICENSE](LICENSE).
