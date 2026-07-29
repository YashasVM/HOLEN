# Holen — Private YouTube Downloader

A private homelab downloader built with React, FastAPI, Clerk, and Docker.

## What it does

- Clerk-authenticated accounts with per-user download history and bandwidth limits
- YouTube videos, Shorts, and selectable playlist entries
- 4K/best, 1080p, 720p, audio, and MP3 downloads via yt-dlp and ffmpeg
- Live queue progress, automatic cache cleanup, and expiring file availability
- Owner dashboard for users, quotas, cached files, and activity telemetry

## Architecture

```
Browser ── Clerk sign-in ──> React/Vite + Nginx ──> FastAPI + SQLite + yt-dlp
                                      │                    │
                                      └── Clerk session ────┘
```

Clerk session tokens are sent only in `Authorization` headers. To start a file
download, the backend issues a single-use, 60-second HttpOnly cookie scoped to
that file endpoint; session tokens are never included in URLs.

## Quick start

### 1. Configure Clerk

Create a Clerk application and configure its allowed origins for the address
where Holen will run. Create these files:

```bash
cp .env.example .env
mkdir -p frontend
```

Set the Clerk credentials and service settings in `.env`:

```env
CLERK_FRONTEND_API_URL=https://your-instance.clerk.accounts.dev
CLERK_SECRET_KEY=sk_live_...
VITE_CLERK_PUBLISHABLE_KEY=pk_live_...
OWNER_GITHUB_USERNAME=your-github-username
PUBLIC_ORIGIN=https://download.example.com
```

For local Vite development only, create `frontend/.env.local` with the public
key:

```env
VITE_CLERK_PUBLISHABLE_KEY=pk_live_...
```

Docker receives the publishable key as a build argument. The frontend build
explicitly excludes all `.env` files, so the Clerk secret remains in the root
`.env` and is passed only to the backend container.

### 2. Start the stack

```bash
docker compose up --build -d
docker compose ps
curl http://localhost:8888/api/health
```

Open `http://localhost:8888`.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `CLERK_FRONTEND_API_URL` | required | Clerk issuer URL |
| `CLERK_SECRET_KEY` | required | Server-side Clerk API key |
| `VITE_CLERK_PUBLISHABLE_KEY` | required | Clerk browser key, injected at frontend build time |
| `OWNER_GITHUB_USERNAME` | `YashasVM` | GitHub username allowed to administer Holen |
| `PUBLIC_ORIGIN` | `http://localhost:8080` | Public application origin |
| `DOWNLOAD_DIR` | `./downloads` | Host download/cache directory |
| `MAX_DURATION_SECONDS` | `7200` | Maximum source video duration |
| `MAX_ACTIVE_JOBS` | `1` | Concurrent downloads |
| `MAX_QUEUED_JOBS` | `25` | Global queue capacity |
| `MAX_JOBS_PER_USER` | `5` | Active or queued jobs per account |
| `DEFAULT_USAGE_LIMIT_GB` | `5` | Per-account bandwidth allowance |
| `RESTRICTED_EMAIL_USAGE_LIMIT_GB` | `2` | Allowance for accounts outside the trusted email domains |
| `TRUSTED_EMAIL_DOMAINS` | `gmail.com,duck.com,hotmail.com,outlook.com` | Exact email domains eligible for the standard allowance |
| `CACHE_LIMIT_GB` | `45` | Cache capacity before old files are removed |
| `DOWNLOAD_LINK_TTL_SECONDS` | `3600` | How long a completed file remains downloadable |

## Deployment

For the bundled SSH deployment script, use SSH keys and set these variables in
your shell:

```bash
export HOLEN_DEPLOY_HOST=server.example.com
export HOLEN_DEPLOY_USER=deploy
export HOLEN_DEPLOY_PATH=/srv/holen
python3 deploy_to_server.py
```

The script uses the local `.env`; it does not create credentials or include a
password. `HOLEN_DEPLOY_PASSWORD` is an optional temporary fallback, not the
recommended authentication method. Add the server host key to `known_hosts`
before running it.

## Development checks

```bash
cd frontend && npm run build
cd ../backend && python3 -m compileall -q app
cd .. && docker compose config --quiet
```

## Security notes

- Use Cloudflare Access or an equivalent identity-aware proxy before exposing a
  homelab deployment publicly.
- Rotate any credential that was ever committed to version control.
- Store `cookies.txt` outside the repository and mount it with
  `YTDLP_COOKIES_PATH` only when needed.

## Native Android app

The standalone, fully on-device Android app lives in [`android/`](android/).
It does not use this server, Clerk, or a WebView. See
[`android/README.md`](android/README.md) for build, signing, storage, and
release instructions.
