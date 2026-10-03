# HOLEN production server — private authenticated deployment

This is the private authenticated deployment built with React, FastAPI, Clerk,
and Docker. It is not required by the Android app and is not a public hosting
guide.

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
download, the backend issues a reusable, expiring HttpOnly cookie scoped to
that file endpoint, so the browser can resume interrupted transfers. The API
checks ownership and bandwidth before nginx streams the file; session tokens
are never included in URLs. Requested byte ranges count toward the allowance.

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
curl http://localhost:8088/api/health
```

Open `http://localhost:8088` (or `8888` with the on-demand service below).

### On-demand mode (this PC)

`holen-on-demand.service` keeps only a small local proxy and the existing
Cloudflare tunnel running. The first request starts the app containers, waits
up to 90 seconds for the API, and proxies that request; the containers stop after 15 idle
minutes only when there are no queued jobs, active jobs, or transfers. Install it as the login user:

```bash
cd /home/yvm/holen/HOLEN
mkdir -p ~/.config/systemd/user
cp prod/holen-on-demand.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now holen-on-demand
```

The frontend is bound to `127.0.0.1:8088`; the proxy is the only service on
port `8888`, which keeps the Cloudflare tunnel target stable.

The proxy and Cloudflare tunnel remain resident while the application containers
are stopped. Idle application-container RAM falls to zero; the listener and
tunnel still need memory. Background account polling stops when the page is idle. Restarting the wake
proxy leaves the containers running, so its recovery does not interrupt jobs.
For an intentional manual shutdown, stop the proxy and run `docker compose
--env-file .env -f prod/docker-compose.yml stop` from the repository root.

### Existing laptop deployment (`holen.yash0.in`)

The installed service currently uses `/home/yvm/yt-yvmx`, while this repository
lives in `/home/yvm/holen/HOLEN`. Build/redeploy intentionally after merging;
editing this checkout alone does not update the deployed containers. Preserve
its `data/app.db`, download directory, and Clerk environment when changing the
service working directory. Set `PUBLIC_ORIGIN=https://holen.yash0.in` and
`DEFAULT_USAGE_LIMIT_GB=20`, and build with the matching public Clerk key.
Back up SQLite before the upgrade. Legacy default 2/5 GB account limits migrate
to the configured allowance once. Other limits and subsequent admin edits stay
unchanged; old custom limits of exactly 2/5 GB also migrate.

On-demand startup uses existing images, not an image build. Build both images
before enabling the updated service. The one-second startup health probe avoids
waiting for the normal 30-second health interval on every wake. The frontend
mounts the same download directory read-only; its `/_downloads/` route is internal
and cannot be requested directly. Keep `DOWNLOAD_ACCEL_REDIRECT` enabled only
behind this nginx configuration. Direct local API development uses FileResponse.

The optional Cloudflare Pages function needs `API_UPSTREAM_ORIGIN` set to a
**distinct** HTTPS tunnel hostname. It is not needed for the laptop-hosted nginx
website. Header timeouts do not cut off ongoing file streams.

### Optional Plex export

The download website starts without Plex or an attached media drive. To enable
the owner-only export, set `PLEX_MUSIC_DROP_DIR` to an existing dedicated drop
folder and include `docker-compose.plex.yml`:

```bash
# From prod/, for an always-running stack:
docker compose -f docker-compose.yml -f docker-compose.plex.yml up --build -d
# In the on-demand service ExecStart (from the repository root):
# .../prod/holen-on-demand.py --compose prod/docker-compose.yml --compose prod/docker-compose.plex.yml
```

Keep both compose files in the on-demand command so wakes retain the mount.
Without this override the export action reports that Plex is unavailable.
The Docker stack loads its Clerk credentials and public key from `.env`; a
frontend development `.env.local` file is not required for production startup.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `CLERK_FRONTEND_API_URL` | required | Clerk issuer URL |
| `CLERK_SECRET_KEY` | required | Server-side Clerk API key |
| `VITE_CLERK_PUBLISHABLE_KEY` | required | Clerk browser key, injected at frontend build time |
| `OWNER_GITHUB_USERNAME` | `YashasVM` | GitHub username allowed to administer Holen |
| `PUBLIC_ORIGIN` | `http://localhost:8080` | Public application origin |
| `DOWNLOAD_DIR` | `./downloads` | Host download/cache directory |
| `PLEX_MUSIC_DROP_DIR` | unset | Pre-created dedicated Plex drop folder; only needed with the optional Plex override |
| `MAX_DURATION_SECONDS` | `7200` | Maximum source video duration |
| `MAX_ACTIVE_JOBS` | `1` | Concurrent downloads |
| `MAX_QUEUED_JOBS` | `25` | Global queue capacity |
| `MAX_JOBS_PER_USER` | `5` | Active or queued jobs per account |
| `DEFAULT_USAGE_LIMIT_GB` | `20` | Per-account allowance; source downloads and file delivery both count |
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
cd .. && python3 test_on_demand.py
python3 backend/checks/download_regression.py  # backend requirements + httpx
node frontend/check-api-proxy.mjs
node frontend/src/cancellation.test.cjs
cd frontend
./node_modules/.bin/tsc src/lib/api.ts --outDir /tmp/holen-api-check --module commonjs --target ES2020 --lib ES2020,DOM --skipLibCheck
node src/lib/api.test.cjs /tmp/holen-api-check/lib/api.js
cd ..
docker compose config --quiet
```

## Security notes

- Use Cloudflare Access or an equivalent identity-aware proxy before exposing a
  homelab deployment publicly.
- Rotate any credential that was ever committed to version control.
- Store `cookies.txt` outside the repository and mount it with
  `YTDLP_COOKIES_PATH` only when needed.
- If you use the Plex copy action, create a dedicated import/drop folder and
  set `PLEX_MUSIC_DROP_DIR` to it. Do not mount the root of a Plex library;
  the backend has write access to the mounted folder.

## Native Android app

The standalone, fully on-device Android app lives in [`android/`](android/).
It does not use this server, Clerk, or a WebView. Download it from the
[latest GitHub Release](https://github.com/YashasVM/HOLEN/releases/latest), or
see [`android/README.md`](android/README.md) for build, signing, storage, and
release instructions.
