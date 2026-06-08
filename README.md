# ▼ YT-YVMX — Private YouTube Downloader

> A Bauhaus-inspired, admin-controlled YouTube downloader for private homelabs. Built with React, FastAPI, Convex, and Docker.

![License](https://img.shields.io/badge/license-private-red) ![Stack](https://img.shields.io/badge/stack-React%20%2B%20FastAPI%20%2B%20Convex-blue)

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Cloudflare Access                        │
│                  (Email allow-list)                         │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│                   Nginx (Frontend)                          │
│              Serves React SPA + proxies /api                │
├─────────────────────────────────────────────────────────────┤
│  React/Vite Frontend                                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │  Login    │  │ Pending  │  │Downloader│  │   Admin    │  │
│  │  (Convex) │  │ Approval │  │   Page   │  │ Dashboard  │  │
│  └──────────┘  └──────────┘  └──────────┘  └────────────┘  │
├─────────────────────────────────────────────────────────────┤
│  Convex Cloud                │  FastAPI Backend             │
│  • Email auth                │  • yt-dlp + ffmpeg           │
│  • User management           │  • SQLite job history        │
│  • Admin approval            │  • File downloads            │
│  • Session handling          │  • Cleanup scheduler         │
└──────────────────────────────┴──────────────────────────────┘
```

---

## Features

### 🔐 Multi-Layer Security
- **Cloudflare Access** — email allow-list (outermost layer)
- **Convex Email Auth** — email + password sign up/sign in
- **Admin Approval** — new users require admin approval before downloading
- **App Password** — shared password for backend API access (innermost layer)

### 📥 Download Engine
- Supports YouTube videos and Shorts
- Quality options: 4K/Best, 1080p, 720p, Audio-only, MP3
- Real-time progress tracking with queue management
- Automatic file cleanup after configurable TTL

### 👑 Admin Dashboard
- View all registered users
- Approve or revoke download access per user
- Promote/demote admin status
- View system stats (storage, jobs, limits)

### 🎨 Bauhaus Design
- Geometric precision with primary color accents (Red, Blue, Yellow)
- DM Sans + Syne typography
- Hard-offset shadows, zero border-radius
- Smooth micro-animations and transitions
- Fully responsive layout

### ⚙️ Safety Defaults
- YouTube-only URLs, playlists blocked
- 2-hour max video duration
- 1 active job, 5 queued jobs max
- 20 GB temp storage limit
- 60-minute file TTL with automatic cleanup

---

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) & Docker Compose
- [Node.js](https://nodejs.org/) v18+ (for local development)
- A [Convex](https://convex.dev/) account (free tier works)

---

## Quick Start

### 1. Clone & Configure

```bash
git clone <repo-url> yt-yvmx
cd yt-yvmx
cp .env.example .env
```

Edit `.env` and set:
- `APP_PASSWORD` — shared app password
- `APP_SECRET` — long random secret (32+ chars)

### 2. Set Up Convex

```bash
cd frontend
npm install
npx convex dev
```

This will:
1. Prompt you to create a Convex project (or link to existing)
2. Generate the `convex/_generated/` directory
3. Deploy your schema and functions
4. Create a `.env.local` file with your `VITE_CONVEX_URL`

> **First User = Admin**: The very first user to sign up automatically becomes an admin with full access. All subsequent users require admin approval.

### 3. Run with Docker

```bash
cd ..
docker compose up --build
```

Open `http://localhost:8888`

### 4. Local Development (without Docker)

Terminal 1 — Backend:
```bash
cd backend
pip install -r requirements.txt
APP_PASSWORD=dev APP_SECRET=dev-secret-32-chars-minimum-here uvicorn app.main:app --reload
```

Terminal 2 — Frontend:
```bash
cd frontend
npm install
npm run dev
```

Terminal 3 — Convex:
```bash
cd frontend
npx convex dev
```

---

## User Flow

```
Visit App
  │
  ├── Not logged in ──▶ Email + Password Login (Convex Auth)
  │                          │
  │                    ┌─────▼──────┐
  │                    │  New User? │
  │                    └─────┬──────┘
  │                     Yes  │  No
  │                          │
  │                    ┌─────▼──────────┐
  │                    │ First user?    │
  │                    │ → Auto-admin   │
  │                    │ Others → Wait  │
  │                    └────────────────┘
  │
  ├── Pending approval ──▶ "Waiting for admin" screen
  │
  ├── Approved ──▶ App Password Gate ──▶ Downloader
  │
  └── Admin ──▶ Admin Dashboard (toggle from header)
```

---

## Cloudflare Tunnel

Create a Cloudflare Tunnel and public hostname (e.g., `download.yourdomain.com`), point it at:

```
http://frontend:80
```

Put Cloudflare Access in front and allow only approved emails.

To use the bundled connector:
```bash
# Set CLOUDFLARED_TOKEN in .env
docker compose --profile tunnel up -d --build
```

---

## Configuration

### Environment Variables (`.env`)

| Variable | Default | Purpose |
|---|---|---|
| `APP_PASSWORD` | *required* | Shared app password for backend API access |
| `APP_SECRET` | *required* | HMAC secret for signed session tokens |
| `PUBLIC_ORIGIN` | `http://localhost:8080` | Public base URL |
| `DOWNLOAD_DIR` | `./downloads` | Host temp download folder |
| `MAX_DURATION_SECONDS` | `7200` | Reject videos longer than this |
| `MAX_ACTIVE_JOBS` | `1` | Concurrent download limit |
| `MAX_QUEUED_JOBS` | `25` | Max queued jobs |
| `MAX_TEMP_GB` | `20` | Temp storage limit |
| `FILE_TTL_SECONDS` | `3600` | File expiry time |
| `CLEANUP_INTERVAL_SECONDS` | `900` | Cleanup cadence |
| `CLOUDFLARED_TOKEN` | *empty* | Cloudflare Tunnel token |

### Convex Variables (`frontend/.env.local`)

| Variable | Purpose |
|---|---|
| `VITE_CONVEX_URL` | Your Convex deployment URL |

---

## API Reference

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `GET` | `/api/health` | None | Health check |
| `POST` | `/api/session/login` | None | Get JWT with app password |
| `POST` | `/api/analyze` | JWT | Analyze a YouTube URL |
| `POST` | `/api/jobs` | JWT | Create a download job |
| `GET` | `/api/jobs` | JWT | List recent jobs |
| `GET` | `/api/jobs/{id}` | JWT | Get job status |
| `GET` | `/api/jobs/{id}/download` | JWT | Download completed file |
| `GET` | `/api/admin/jobs` | JWT | Admin: all jobs + stats |

Legacy aliases: `/api/auth/login`, `/api/metadata`, `/api/files/{id}`

---

## Project Structure

```
yt-yvmx/
├── frontend/
│   ├── convex/                 # Convex backend functions
│   │   ├── schema.ts           # Database schema
│   │   ├── auth.ts             # Auth configuration
│   │   ├── http.ts             # HTTP routes
│   │   ├── users.ts            # User management functions
│   │   └── _generated/         # Auto-generated (git-ignored)
│   ├── src/
│   │   ├── main.tsx            # App entry + routing
│   │   ├── LoginPage.tsx       # Email auth page
│   │   ├── PendingPage.tsx     # Approval waiting page
│   │   ├── DownloaderPage.tsx  # Main downloader UI
│   │   ├── AdminDashboard.tsx  # User management panel
│   │   └── styles.css          # Bauhaus design system
│   ├── index.html
│   ├── package.json
│   └── vite.config.ts
├── backend/
│   ├── app/
│   │   └── main.py             # FastAPI application
│   ├── Dockerfile
│   └── requirements.txt
├── docker-compose.yml
├── .env.example
└── README.md
```

---

## Security Model

The application implements defense-in-depth with four security layers:

1. **Cloudflare Access** — Network-level email allow-list
2. **Convex Auth** — Application-level email + password authentication
3. **Admin Approval** — Only admin-approved users can access the downloader
4. **App Password** — Backend API protected by shared password + HMAC JWT

Each layer is independent. Bypassing one layer does not grant access to the application.

---

## Test Checklist

- [ ] First signup creates an auto-admin user
- [ ] Subsequent signups show "pending approval" screen
- [ ] Admin can approve/revoke users from the dashboard
- [ ] Approved users can enter app password and use downloader
- [ ] Revoked users are blocked from downloading
- [ ] YouTube video and Shorts URLs analyze successfully
- [ ] Playlist, malformed, and non-YouTube URLs are rejected
- [ ] All format options (4K, 1080p, 720p, audio, MP3) work
- [ ] Queue respects active/queued job limits
- [ ] Completed files download and auto-expire after TTL
- [ ] Docker Compose stack starts cleanly
- [ ] Responsive layout works on mobile
