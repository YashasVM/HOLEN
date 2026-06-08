# ▼ YT-YVMX — Setup Guide

> Deploy this private YouTube downloader on any machine in minutes.  
> Works on **Linux**, **macOS**, and **Windows** (with Docker Desktop).

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Clone & Configure](#2-clone--configure)
3. [Convex Setup (One-Time)](#3-convex-setup-one-time)
4. [Deploy with Docker](#4-deploy-with-docker)
5. [Deploy without Docker (Dev Mode)](#5-deploy-without-docker-dev-mode)
6. [Cloudflare Tunnel (Optional)](#6-cloudflare-tunnel-optional)
7. [Deploy to Remote Server](#7-deploy-to-remote-server)
8. [Environment Reference](#8-environment-reference)
9. [Troubleshooting](#9-troubleshooting)
10. [Reset & Clean Start](#10-reset--clean-start)

---

## 1. Prerequisites

Install these **before** starting:

| Tool | Version | Check Command | Install |
|------|---------|---------------|---------|
| **Git** | Any | `git --version` | [git-scm.com](https://git-scm.com) |
| **Docker** | 24+ | `docker --version` | [docs.docker.com/get-docker](https://docs.docker.com/get-docker/) |
| **Docker Compose** | v2+ | `docker compose version` | Included with Docker Desktop |
| **Node.js** | 18+ | `node --version` | [nodejs.org](https://nodejs.org) |
| **npm** | 9+ | `npm --version` | Included with Node.js |

> **Note**: Node.js is only needed for the initial Convex setup and local dev.
> Docker handles everything in production — no Node.js needed on the server.

### Convex Account

You need a free [Convex](https://convex.dev) account for user authentication.

1. Go to [dashboard.convex.dev](https://dashboard.convex.dev)
2. Sign up with GitHub or Google
3. That's it — project creation happens in Step 3 below

---

## 2. Clone & Configure

### 2.1 — Get the code

```bash
git clone <your-repo-url> yt-yvmx
cd yt-yvmx
```

### 2.2 — Create the backend environment file

```bash
cp .env.example .env
```

Open `.env` and set these two **required** values:

```env
APP_PASSWORD=pick-a-strong-shared-password
APP_SECRET=generate-a-random-string-at-least-32-characters-long
```

**Generate a strong secret** (run in terminal):

```bash
# Linux / macOS
openssl rand -hex 32

# Windows PowerShell
-join ((1..32) | ForEach-Object { '{0:x2}' -f (Get-Random -Max 256) })

# Or just use any password manager to generate 64 random characters
```

### 2.3 — Review optional settings

All other `.env` values have sensible defaults. Only change if needed:

```env
PUBLIC_ORIGIN=http://localhost:8888      # Change to your domain if using Cloudflare
MAX_DURATION_SECONDS=7200               # 2 hours max video length
MAX_ACTIVE_JOBS=1                       # Concurrent downloads
MAX_QUEUED_JOBS=25                       # Queue capacity
MAX_TEMP_GB=20                          # Temp storage limit
FILE_TTL_SECONDS=3600                   # Files auto-delete after 1 hour
```

---

## 3. Convex Setup (One-Time)

> **You only need to do this once**, not on every machine.  
> After the first setup, just copy the `VITE_CONVEX_URL` to each new machine.

### 3.1 — Install frontend dependencies

```bash
cd frontend
npm install
```

### 3.2 — Initialize Convex

```bash
npx convex dev
```

This interactive command will:

1. Open your browser to log in to Convex
2. Ask you to **create a new project** — name it `yt-yvmx` (or anything)
3. Deploy the schema and functions to Convex cloud
4. **Auto-create** `frontend/.env.local` with your `VITE_CONVEX_URL`
5. Start watching for changes (you can press `Ctrl+C` after it's running)

### 3.3 — Save your Convex URL

After setup, check your URL:

```bash
cat .env.local
# Should show: VITE_CONVEX_URL=https://your-project-123.convex.cloud
```

**Save this URL** — you'll need it when deploying to other machines.

### 3.4 — First User = Admin

The **very first person** to sign up on the app automatically becomes the admin.  
All other users will see a "Pending Approval" screen until the admin approves them.

```
First signup  →  Auto-admin + Auto-approved
All others    →  Pending approval
```

---

## 4. Deploy with Docker

This is the **recommended** method for production and homelab use.

### 4.1 — Ensure environment files exist

```
yt-yvmx/
├── .env                          ← Backend config (from Step 2)
└── frontend/
    └── .env.local                ← Contains VITE_CONVEX_URL (from Step 3)
```

### 4.2 — Build and start

```bash
# From the project root (yt-yvmx/)
docker compose up --build -d
```

First build takes 2–5 minutes. Subsequent builds are much faster.

### 4.3 — Verify it's running

```bash
# Check services
docker compose ps

# Check backend health
curl http://localhost:8888/api/health
# Expected: {"status":"ok"}

# View logs
docker compose logs -f --tail=20
```

### 4.4 — Access the app

Open **`http://localhost:8888`** in your browser.

1. Sign up with your email + password (first user = admin)
2. Enter the app password (from your `.env` → `APP_PASSWORD`)
3. Start downloading

### 4.5 — Stop / Restart

```bash
# Stop
docker compose down

# Restart
docker compose up -d

# Rebuild after code changes
docker compose up --build -d

# Full rebuild (clear cache)
docker compose build --no-cache && docker compose up -d
```

---

## 5. Deploy without Docker (Dev Mode)

For local development with hot-reloading.

### Terminal 1 — Backend

```bash
cd backend
pip install -r requirements.txt

# Linux / macOS
APP_PASSWORD=dev-password APP_SECRET=dev-secret-minimum-32-characters-here \
  uvicorn app.main:app --reload --port 8000

# Windows PowerShell
$env:APP_PASSWORD="dev-password"
$env:APP_SECRET="dev-secret-minimum-32-characters-here"
uvicorn app.main:app --reload --port 8000
```

> **Note**: The backend requires `ffmpeg` and `yt-dlp` installed on your system.
>
> ```bash
> # Ubuntu/Debian
> sudo apt install ffmpeg
> pip install yt-dlp
>
> # macOS
> brew install ffmpeg yt-dlp
>
> # Windows (with Chocolatey)
> choco install ffmpeg
> pip install yt-dlp
> ```

### Terminal 2 — Frontend

```bash
cd frontend
npm install
npm run dev
```

### Terminal 3 — Convex (watch mode)

```bash
cd frontend
npx convex dev
```

Open **`http://localhost:5173`** (Vite dev server with hot-reload).

---

## 6. Cloudflare Tunnel (Optional)

Expose the app to the internet through Cloudflare's network.

### 6.1 — Create a tunnel

1. Go to [Cloudflare Zero Trust Dashboard](https://one.dash.cloudflare.com)
2. **Networks → Tunnels → Create a tunnel**
3. Name it (e.g., `yt-yvmx`)
4. Copy the **tunnel token**
5. Add a public hostname:
   - Hostname: `download.yourdomain.com`
   - Service: `http://frontend:80`

### 6.2 — Configure

Add the token to your `.env`:

```env
CLOUDFLARED_TOKEN=eyJhIjoiNjk...your-long-token-here
```

### 6.3 — Start with tunnel

```bash
docker compose --profile tunnel up -d --build
```

### 6.4 — Add Cloudflare Access (Recommended)

1. Go to **Access → Applications → Add an application**
2. Set policy to allow only specific email addresses
3. This adds network-level protection before anyone reaches the app

---

## 7. Deploy to Remote Server

### Option A — Git pull on server

```bash
# On the remote server
git clone <repo-url> yt-yvmx
cd yt-yvmx

# Create .env
cp .env.example .env
nano .env  # Set APP_PASSWORD and APP_SECRET

# Create frontend env
mkdir -p frontend
echo "VITE_CONVEX_URL=https://your-project-123.convex.cloud" > frontend/.env.local

# Build and run
docker compose up --build -d
```

### Option B — Use the deploy script

The included `deploy_to_server.py` script pushes all files via SSH:

```bash
# Edit the script first — update HOST, USER, PASSWORD
nano deploy_to_server.py

# Run
pip install paramiko
python deploy_to_server.py
```

### Option C — Quick copy checklist

When setting up on a **new machine**, you need exactly these things:

```
✅  The entire yt-yvmx/ directory (git clone or copy)
✅  .env file with APP_PASSWORD and APP_SECRET set
✅  frontend/.env.local with VITE_CONVEX_URL set
✅  Docker + Docker Compose installed
✅  Run: docker compose up --build -d
```

That's it. 5 steps.

---

## 8. Environment Reference

### Root `.env` — Backend Configuration

| Variable | Required | Default | What it does |
|----------|----------|---------|-------------|
| `APP_PASSWORD` | **Yes** | — | Shared password users enter to access the downloader |
| `APP_SECRET` | **Yes** | — | Secret key for signing session tokens (32+ chars) |
| `PUBLIC_ORIGIN` | No | `http://localhost:8080` | Public URL (used in logs) |
| `DOWNLOAD_DIR` | No | `./downloads` | Where downloaded files are stored |
| `SQLITE_PATH` | No | `./data/app.db` | SQLite database location |
| `MAX_DURATION_SECONDS` | No | `7200` | Max video length (seconds) |
| `MAX_ACTIVE_JOBS` | No | `1` | Simultaneous downloads |
| `MAX_QUEUED_JOBS` | No | `25` | Max jobs waiting in queue |
| `MAX_TEMP_GB` | No | `20` | Reject new jobs above this storage use |
| `FILE_TTL_SECONDS` | No | `3600` | Auto-delete completed files after this time |
| `CLEANUP_INTERVAL_SECONDS` | No | `900` | How often cleanup runs |
| `CLOUDFLARED_TOKEN` | No | — | Cloudflare Tunnel token |

### `frontend/.env.local` — Convex Configuration

| Variable | Required | What it does |
|----------|----------|-------------|
| `VITE_CONVEX_URL` | **Yes** | Your Convex deployment URL (from `npx convex dev`) |

---

## 9. Troubleshooting

### "Cannot connect to Convex" / blank screen

The `VITE_CONVEX_URL` is missing or wrong.

```bash
# Check the file exists
cat frontend/.env.local

# Should contain:
# VITE_CONVEX_URL=https://something.convex.cloud
```

If deploying with Docker, rebuild after creating/changing the file:

```bash
docker compose up --build -d
```

### Backend health check fails

```bash
# Check if backend container is running
docker compose ps

# Check backend logs
docker compose logs backend --tail=50

# Common fix: ensure .env has both APP_PASSWORD and APP_SECRET
```

### "Wrong password" on the app password screen

You're entering the wrong `APP_PASSWORD`. Check your `.env`:

```bash
grep APP_PASSWORD .env
```

### Frontend build fails in Docker

```bash
# View build output
docker compose build frontend --no-cache 2>&1

# Common fix: delete node_modules and rebuild
docker compose build --no-cache
```

### Port 8888 already in use

Change the port in `docker-compose.yml`:

```yaml
frontend:
  ports:
    - "9999:80"  # Change 8888 to any free port
```

### yt-dlp fails / "unable to download"

YouTube frequently changes their API. Update yt-dlp:

```bash
docker compose build --no-cache backend
docker compose up -d
```

### Convex functions not deploying

```bash
cd frontend
npx convex deploy  # Deploy to production
# or
npx convex dev     # Watch mode for development
```

### First user didn't become admin

This happens if the `setupNewUser` mutation failed. Fix manually:

1. Go to [dashboard.convex.dev](https://dashboard.convex.dev)
2. Open your project → **Data** tab
3. Find the user in the `users` table
4. Edit: set `isApproved: true` and `isAdmin: true`

---

## 10. Reset & Clean Start

### Reset job history (keep users)

```bash
# Stop services
docker compose down

# Delete the database
rm -rf data/

# Restart — fresh database
docker compose up -d
```

### Reset everything (nuclear option)

```bash
# Stop and remove everything
docker compose down --volumes --remove-orphans

# Delete all data
rm -rf data/ downloads/

# Rebuild from scratch
docker compose build --no-cache
docker compose up -d
```

### Reset Convex users

1. Go to [dashboard.convex.dev](https://dashboard.convex.dev)
2. Open your project → **Data** tab
3. Clear the `users` table
4. The next signup will become the new admin

---

## Quick Setup Cheatsheet

Copy-paste this block to set up on a new machine in under 2 minutes:

```bash
# 1. Clone
git clone <repo-url> yt-yvmx && cd yt-yvmx

# 2. Backend config
cp .env.example .env
# Edit .env → set APP_PASSWORD and APP_SECRET

# 3. Convex config
echo "VITE_CONVEX_URL=https://your-project.convex.cloud" > frontend/.env.local

# 4. Launch
docker compose up --build -d

# 5. Verify
docker compose ps && curl -s http://localhost:8888/api/health
```

**Done.** Open `http://localhost:8888` and sign in.
