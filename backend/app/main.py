from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import os
import platform
import re
import secrets
import shutil
import sqlite3
import subprocess
import threading
import time
import urllib.error
import urllib.request
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

import jwt
from jwt import InvalidTokenError, PyJWKClient
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from pydantic import BaseModel, Field


APP_PASSWORD = os.environ.get("APP_PASSWORD", "")  # optional — only used by legacy /api/session/login
APP_SECRET = os.environ["APP_SECRET"].encode("utf-8")


def clerk_frontend_api_from_publishable_key(value: str) -> str:
    """Derive Clerk's frontend API host when only the publishable key is configured."""
    if not value.startswith("pk_"):
        return ""
    try:
        encoded_host = value.split("_", 2)[2]
        padding = "=" * (-len(encoded_host) % 4)
        host = base64.urlsafe_b64decode(encoded_host + padding).decode("utf-8").rstrip("$")
    except (IndexError, UnicodeDecodeError, ValueError):
        return ""
    return f"https://{host}" if host.endswith(".clerk.accounts.dev") or host.endswith(".clerk.com") else ""


CLERK_FRONTEND_API_URL = (
    os.environ.get("CLERK_FRONTEND_API_URL", "").rstrip("/")
    or clerk_frontend_api_from_publishable_key(os.environ.get("VITE_CLERK_PUBLISHABLE_KEY", ""))
)
CLERK_SECRET_KEY = os.environ.get("CLERK_SECRET_KEY", "")
OWNER_GITHUB_USERNAME = os.environ.get("OWNER_GITHUB_USERNAME", "YashasVM")
PUBLIC_ORIGIN = os.environ.get("PUBLIC_ORIGIN", "http://localhost:8080")
DOWNLOAD_DIR = Path(os.environ.get("DOWNLOAD_DIR", "./downloads")).resolve()
SQLITE_PATH = Path(os.environ.get("SQLITE_PATH", "./data/app.db")).resolve()
TOKEN_TTL_SECONDS = 60 * 60 * 24 * 30
MAX_DURATION_SECONDS = int(os.environ.get("MAX_DURATION_SECONDS", str(2 * 60 * 60)))
MAX_ACTIVE_JOBS = int(os.environ.get("MAX_ACTIVE_JOBS", "1"))
MAX_QUEUED_JOBS = int(os.environ.get("MAX_QUEUED_JOBS", "25"))
MAX_TEMP_GB = float(os.environ.get("MAX_TEMP_GB", "20"))
PLEX_THRESHOLD_GB = 15.0
FILE_TTL_SECONDS = int(os.environ.get("FILE_TTL_SECONDS", str(60 * 60)))
CLEANUP_INTERVAL_SECONDS = int(os.environ.get("CLEANUP_INTERVAL_SECONDS", str(15 * 60)))
YTDLP_COOKIES_FILE = os.environ.get("YTDLP_COOKIES_FILE", "")
# Per-user limits: max queued+running jobs per verified Clerk user ID
MAX_JOBS_PER_USER = int(os.environ.get("MAX_JOBS_PER_USER", "5"))
DEFAULT_USAGE_LIMIT_BYTES = int(float(os.environ.get("DEFAULT_USAGE_LIMIT_GB", "5")) * 1024**3)
SERVER_START_TIME = time.time()
_clerk_jwks = PyJWKClient(f"{CLERK_FRONTEND_API_URL}/.well-known/jwks.json") if CLERK_FRONTEND_API_URL else None

DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
SQLITE_PATH.parent.mkdir(parents=True, exist_ok=True)

db_lock = threading.Lock()
scheduler_lock: asyncio.Lock | None = None

_sse_clients: set[asyncio.Queue] = set()
running_processes: dict[str, asyncio.subprocess.Process] = {}
_clerk_profile_cache: dict[str, tuple[float, dict[str, Any]]] = {}

# Rate limiting: store as {key: [timestamps]}
_analyze_calls: dict[str, list[float]] = {}
_login_calls: dict[str, list[float]] = {}  # IP-based login rate limiting


class LoginRequest(BaseModel):
    password: str = Field(min_length=1, max_length=256)


class UrlRequest(BaseModel):
    url: str = Field(min_length=8, max_length=4096)


class JobRequest(UrlRequest):
    format: str = Field(pattern="^(best|best_video|1080p|720p|audio|mp3)$")
    # title/thumbnail are optional, capped to prevent DB pollution
    title: str | None = Field(default=None, max_length=512)
    thumbnail: str | None = Field(default=None, max_length=2048)


class AccessUpdateRequest(BaseModel):
    is_admin: bool | None = None
    usage_limit_bytes: int | None = Field(default=None, ge=1024**3, le=10 * 1024**4)


@asynccontextmanager
async def lifespan(_: FastAPI):
    global scheduler_lock
    scheduler_lock = asyncio.Lock()
    reset_interrupted_jobs()
    cleanup_expired()
    await schedule_next_jobs()
    cleanup_task = asyncio.create_task(cleanup_loop())
    try:
        yield
    finally:
        cleanup_task.cancel()


app = FastAPI(title="Homelab Downloader", lifespan=lifespan)

# ── CORS ─────────────────────────────────────────────────────────────────────
# Only allow the configured public origin + localhost for dev
_allowed_origins = [PUBLIC_ORIGIN, "http://localhost:5173", "http://localhost:8888"]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
    max_age=600,
)


# ── Security headers middleware ───────────────────────────────────────────────
@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["X-XSS-Protection"] = "0"  # modern browsers ignore; CSP is the real protection
    return response


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def open_db() -> sqlite3.Connection:
    conn = sqlite3.connect(SQLITE_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with db_lock, open_db() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY,
                url TEXT NOT NULL,
                title TEXT,
                thumbnail TEXT,
                format TEXT NOT NULL,
                status TEXT NOT NULL,
                progress REAL NOT NULL DEFAULT 0,
                message TEXT,
                file_path TEXT,
                file_name TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                expires_at TEXT,
                user_email TEXT
            )
            """
        )
        columns = [row["name"] for row in conn.execute("PRAGMA table_info(jobs)").fetchall()]
        for col, coldef in [
            ("expires_at", "TEXT"),
            ("thumbnail", "TEXT"),
            ("user_email", "TEXT"),
        ]:
            if col not in columns:
                conn.execute(f"ALTER TABLE jobs ADD COLUMN {col} {coldef}")
        conn.commit()

        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS access_users (
                user_id TEXT PRIMARY KEY,
                name TEXT,
                email TEXT,
                github_username TEXT,
                is_admin INTEGER NOT NULL DEFAULT 0,
                is_owner INTEGER NOT NULL DEFAULT 0,
                usage_limit_bytes INTEGER NOT NULL,
                ingress_bytes INTEGER NOT NULL DEFAULT 0,
                egress_bytes INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
        # Admin access is reserved for the verified GitHub owner account.
        # This also removes any legacy elevated roles on startup.
        conn.execute("UPDATE access_users SET is_admin = 0 WHERE is_owner = 0")
        conn.commit()


init_db()


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def sign(payload: dict[str, Any]) -> str:
    body = b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    signature = b64url(hmac.new(APP_SECRET, body.encode("ascii"), hashlib.sha256).digest())
    return f"{body}.{signature}"


def verify_token(token: str) -> dict[str, Any]:
    if not CLERK_FRONTEND_API_URL or not _clerk_jwks:
        raise HTTPException(status_code=503, detail="Clerk authentication is not configured")
    try:
        signing_key = _clerk_jwks.get_signing_key_from_jwt(token)
        return jwt.decode(
            token,
            signing_key.key,
            algorithms=["RS256"],
            issuer=CLERK_FRONTEND_API_URL,
            options={"require": ["exp", "iat", "sub"], "verify_aud": False},
        )
    except (InvalidTokenError, ValueError) as exc:
        raise HTTPException(status_code=401, detail="Invalid or expired Clerk session") from exc


def require_auth(authorization: str | None = Header(default=None)) -> dict[str, Any]:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing token")
    return verify_token(authorization.removeprefix("Bearer ").strip())


def require_auth_or_query(
    authorization: str | None = Header(default=None),
    token: str | None = Query(default=None),
) -> dict[str, Any]:
    if authorization and authorization.startswith("Bearer "):
        return verify_token(authorization.removeprefix("Bearer ").strip())
    if token:
        return verify_token(token)
    raise HTTPException(status_code=401, detail="Missing token")


def clerk_profile(user_id: str) -> dict[str, Any]:
    cached = _clerk_profile_cache.get(user_id)
    if cached and time.time() - cached[0] < 300:
        return cached[1]
    if not CLERK_SECRET_KEY:
        raise HTTPException(status_code=503, detail="Clerk server authentication is not configured")
    request = urllib.request.Request(
        f"https://api.clerk.com/v1/users/{user_id}",
        headers={
            "Authorization": f"Bearer {CLERK_SECRET_KEY}",
            "Accept": "application/json",
            # Clerk rejects urllib's default Python-urllib/* user agent.
            "User-Agent": "Holen-Downloader/1.0",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            profile = json.loads(response.read())
    except (urllib.error.URLError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=503, detail="Could not verify the Clerk account") from exc
    _clerk_profile_cache[user_id] = (time.time(), profile)
    return profile


def profile_fields(profile: dict[str, Any]) -> tuple[str | None, str | None, str | None]:
    github_username = next(
        (
            account.get("username")
            for account in profile.get("external_accounts", [])
            if account.get("provider") in {"github", "oauth_github"} and account.get("username")
        ),
        None,
    )
    email = next(
        (item.get("email_address") for item in profile.get("email_addresses", []) if item.get("email_address")),
        None,
    )
    name = " ".join(filter(None, [profile.get("first_name"), profile.get("last_name")])).strip()
    return name or profile.get("username"), email, github_username


def ensure_access_user(auth: dict[str, Any]) -> dict[str, Any]:
    user_id = str(auth["sub"])
    profile = clerk_profile(user_id)
    name, email, github_username = profile_fields(profile)
    is_owner = bool(github_username and github_username.casefold() == OWNER_GITHUB_USERNAME.casefold())
    timestamp = now_iso()
    with db_lock, open_db() as conn:
        existing = conn.execute("SELECT * FROM access_users WHERE user_id = ?", (user_id,)).fetchone()
        if existing:
            conn.execute(
                "UPDATE access_users SET name = ?, email = ?, github_username = ?, is_owner = ?, is_admin = ?, updated_at = ? WHERE user_id = ?",
                (name, email, github_username, int(is_owner), int(is_owner), timestamp, user_id),
            )
        else:
            conn.execute(
                "INSERT INTO access_users (user_id, name, email, github_username, is_admin, is_owner, usage_limit_bytes, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (user_id, name, email, github_username, int(is_owner), int(is_owner), DEFAULT_USAGE_LIMIT_BYTES, timestamp, timestamp),
            )
        conn.commit()
        row = conn.execute("SELECT * FROM access_users WHERE user_id = ?", (user_id,)).fetchone()
    return dict(row)


def require_user(auth: dict[str, Any] = Depends(require_auth)) -> dict[str, Any]:
    return ensure_access_user(auth)


def require_user_or_query(
    authorization: str | None = Header(default=None),
    token: str | None = Query(default=None),
) -> dict[str, Any]:
    return ensure_access_user(require_auth_or_query(authorization, token))


def require_admin(user: dict[str, Any] = Depends(require_user)) -> dict[str, Any]:
    if not user["is_admin"]:
        raise HTTPException(status_code=403, detail="Admin access required")
    return user


def require_owner(user: dict[str, Any] = Depends(require_user)) -> dict[str, Any]:
    if not user["is_owner"]:
        raise HTTPException(status_code=403, detail=f"Only GitHub user {OWNER_GITHUB_USERNAME} can change admin roles")
    return user


def public_user(row: dict[str, Any] | sqlite3.Row) -> dict[str, Any]:
    data = dict(row)
    used = int(data["ingress_bytes"]) + int(data["egress_bytes"])
    return {
        "id": data["user_id"],
        "name": data["name"],
        "email": data["email"],
        "github_username": data["github_username"],
        "is_admin": bool(data["is_admin"]),
        "is_owner": bool(data["is_owner"]),
        "usage_limit_bytes": int(data["usage_limit_bytes"]),
        "ingress_bytes": int(data["ingress_bytes"]),
        "egress_bytes": int(data["egress_bytes"]),
        "used_bytes": used,
        "remaining_bytes": max(0, int(data["usage_limit_bytes"]) - used),
        "created_at": data["created_at"],
    }


def assert_quota(user: dict[str, Any], additional_bytes: int = 0) -> None:
    used = int(user["ingress_bytes"]) + int(user["egress_bytes"])
    if used + additional_bytes > int(user["usage_limit_bytes"]):
        raise HTTPException(status_code=429, detail="Your bandwidth allowance is exhausted. Ask an admin to raise it.")


def add_usage(user_id: str, column: str, amount: int) -> None:
    if column not in {"ingress_bytes", "egress_bytes"} or amount <= 0:
        return
    with db_lock, open_db() as conn:
        conn.execute(
            f"UPDATE access_users SET {column} = {column} + ?, updated_at = ? WHERE user_id = ?",
            (amount, now_iso(), user_id),
        )
        conn.commit()


def validate_url(url: str) -> str:
    parsed = urlparse(url.strip())
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise HTTPException(status_code=400, detail="Enter a valid http(s) URL")
    host = parsed.netloc.lower().removeprefix("www.")
    allowed_hosts = {"youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"}
    if host not in allowed_hosts:
        raise HTTPException(status_code=400, detail="Only YouTube URLs are allowed")
    return url.strip()


def _rate_limit(store: dict[str, list[float]], key: str, limit: int, window: int) -> None:
    """Raise 429 if key has hit `limit` calls within `window` seconds."""
    now = time.time()
    calls = store.setdefault(key, [])
    store[key] = [t for t in calls if now - t < window]
    if len(store[key]) >= limit:
        raise HTTPException(status_code=429, detail=f"Rate limit: {limit} requests per {window}s")
    store[key].append(now)


def _notify_sse(jobs: list[dict[str, Any]]) -> None:
    if not _sse_clients:
        return
    data = json.dumps(jobs)
    dead = set()
    for q in _sse_clients:
        try:
            q.put_nowait(data)
        except asyncio.QueueFull:
            dead.add(q)
    _sse_clients.difference_update(dead)


def update_job(job_id: str, **fields: Any) -> None:
    fields["updated_at"] = now_iso()
    assignments = ", ".join(f"{key} = ?" for key in fields)
    values = list(fields.values()) + [job_id]
    with db_lock, open_db() as conn:
        conn.execute(f"UPDATE jobs SET {assignments} WHERE id = ?", values)
        conn.commit()
        rows = conn.execute("SELECT * FROM jobs ORDER BY created_at DESC LIMIT 50").fetchall()
    jobs = [row_to_job(row) for row in rows]
    try:
        loop = asyncio.get_event_loop()
        if loop.is_running():
            loop.call_soon_threadsafe(_notify_sse, jobs)
    except RuntimeError:
        pass


def row_to_job(row: sqlite3.Row) -> dict[str, Any]:
    data = dict(row)
    if data.get("file_path"):
        data["download_url"] = f"/api/jobs/{data['id']}/download"
    return data


def get_job_or_404(job_id: str) -> dict[str, Any]:
    with db_lock, open_db() as conn:
        row = conn.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="Job not found")
    return row_to_job(row)


def directory_size_bytes(path: Path) -> int:
    total = 0
    for item in path.rglob("*"):
        if item.is_file():
            total += item.stat().st_size
    return total


def ensure_temp_capacity() -> None:
    used = directory_size_bytes(DOWNLOAD_DIR)
    limit = int(MAX_TEMP_GB * 1024 * 1024 * 1024)
    if used >= limit:
        raise HTTPException(status_code=507, detail=f"Temporary storage is full. Limit is {MAX_TEMP_GB:g} GB")


def reset_interrupted_jobs() -> None:
    with db_lock, open_db() as conn:
        conn.execute(
            """
            UPDATE jobs
            SET status = 'failed', message = 'Server restarted during this job', updated_at = ?
            WHERE status = 'running'
            """,
            (now_iso(),),
        )
        conn.commit()


def cleanup_expired() -> None:
    """
    Two-pass cleanup:
    1. Delete individual files whose expires_at has passed.
    2. If storage still >= PLEX_THRESHOLD_GB, delete oldest completed files.
    """
    now = now_iso()

    # Pass 1: TTL-based individual file expiry
    with db_lock, open_db() as conn:
        ttl_rows = conn.execute(
            "SELECT id, file_path FROM jobs WHERE file_path IS NOT NULL AND status = 'completed' AND expires_at IS NOT NULL AND expires_at <= ?",
            (now,),
        ).fetchall()
        for row in ttl_rows:
            if row["file_path"]:
                path = Path(row["file_path"]).resolve()
                if path.exists() and (path == DOWNLOAD_DIR or DOWNLOAD_DIR in path.parents):
                    path.unlink(missing_ok=True)
            conn.execute(
                "UPDATE jobs SET file_path = NULL, file_name = NULL, message = 'File expired (TTL)', updated_at = ? WHERE id = ?",
                (now_iso(), row["id"]),
            )
        conn.commit()

    # Pass 2: Storage threshold — clear oldest completed files if over limit
    threshold = int(PLEX_THRESHOLD_GB * 1024 * 1024 * 1024)
    if directory_size_bytes(DOWNLOAD_DIR) < threshold:
        return

    with db_lock, open_db() as conn:
        rows = conn.execute(
            "SELECT id, file_path FROM jobs WHERE file_path IS NOT NULL AND status = 'completed' ORDER BY created_at ASC"
        ).fetchall()
        for row in rows:
            if directory_size_bytes(DOWNLOAD_DIR) < threshold:
                break
            if row["file_path"]:
                path = Path(row["file_path"]).resolve()
                if path.exists() and (path == DOWNLOAD_DIR or DOWNLOAD_DIR in path.parents):
                    path.unlink(missing_ok=True)
            conn.execute(
                "UPDATE jobs SET file_path = NULL, file_name = NULL, message = 'Auto-deleted: storage limit reached', updated_at = ? WHERE id = ?",
                (now_iso(), row["id"]),
            )
        conn.commit()

    # Remove any orphan files
    for path in DOWNLOAD_DIR.glob("*"):
        if path.is_file():
            path.unlink(missing_ok=True)


async def cleanup_loop() -> None:
    while True:
        await asyncio.sleep(CLEANUP_INTERVAL_SECONDS)
        cleanup_expired()


def normalized_options(info: dict[str, Any]) -> list[dict[str, Any]]:
    heights = sorted(
        {
            item.get("height")
            for item in info.get("formats", [])
            if isinstance(item.get("height"), int) and item.get("vcodec") != "none"
        },
        reverse=True,
    )
    max_height = heights[0] if heights else 0
    has_audio = any(item.get("acodec") != "none" for item in info.get("formats", []))
    return [
        {
            "id": "best",
            "label": "4K / Best",
            "description": "Highest available quality with automatic fallback",
            "available": max_height > 0,
            "detail": f"Up to {max_height}p" if max_height else "Unavailable",
        },
        {
            "id": "1080p",
            "label": "1080p",
            "description": "MP4 video capped at 1080p",
            "available": max_height >= 1080,
            "detail": "Available" if max_height >= 1080 else "Falls back lower",
        },
        {
            "id": "720p",
            "label": "720p",
            "description": "MP4 video capped at 720p",
            "available": max_height >= 720,
            "detail": "Available" if max_height >= 720 else "Falls back lower",
        },
        {
            "id": "audio",
            "label": "Audio Only",
            "description": "Best audio stream",
            "available": has_audio,
            "detail": "Best audio" if has_audio else "Unavailable",
        },
    ]


def cookies_args() -> list[str]:
    if YTDLP_COOKIES_FILE:
        p = Path(YTDLP_COOKIES_FILE)
        if p.is_file() and p.stat().st_size > 0:
            return ["--cookies", YTDLP_COOKIES_FILE]
    return []


def run_metadata(url: str) -> dict[str, Any]:
    completed = subprocess.run(
        ["yt-dlp", "--dump-single-json", "--no-playlist", "--no-warnings"] + cookies_args() + [url],
        check=False,
        capture_output=True,
        text=True,
        timeout=45,
    )
    if completed.returncode != 0:
        raise HTTPException(status_code=400, detail=(completed.stderr or "Metadata lookup failed")[-500:])
    info = json.loads(completed.stdout)
    duration = info.get("duration")
    if isinstance(duration, (int, float)) and duration > MAX_DURATION_SECONDS:
        raise HTTPException(status_code=400, detail=f"Videos must be {MAX_DURATION_SECONDS // 60} minutes or shorter")
    formats = [
        {
            "format_id": item.get("format_id"),
            "ext": item.get("ext"),
            "resolution": item.get("resolution"),
            "fps": item.get("fps"),
            "filesize": item.get("filesize") or item.get("filesize_approx"),
            "vcodec": item.get("vcodec"),
            "acodec": item.get("acodec"),
            "height": item.get("height"),
        }
        for item in info.get("formats", [])
        if item.get("format_id")
    ]
    return {
        "title": info.get("title"),
        "thumbnail": info.get("thumbnail"),
        "duration": duration,
        "uploader": info.get("uploader"),
        "webpage_url": info.get("webpage_url") or url,
        "formats": formats[-12:],
        "options": normalized_options(info),
    }


def output_template(job_id: str) -> str:
    return str(DOWNLOAD_DIR / f"{job_id}.%(title).180B.%(ext)s")


def command_for(job_id: str, url: str, selected_format: str) -> list[str]:
    base = [
        "yt-dlp",
        "--newline",
        "--no-playlist",
        "--restrict-filenames",
        "-o",
        output_template(job_id),
    ] + cookies_args()
    if selected_format == "best_video":
        return base + ["-f", "bv*+ba/b", "--merge-output-format", "mp4", url]
    if selected_format == "1080p":
        return base + ["-f", "bv*[height<=1080]+ba/b[height<=1080]/b/bv*+ba/b", "--merge-output-format", "mp4", url]
    if selected_format == "720p":
        return base + ["-f", "bv*[height<=720]+ba/b[height<=720]/b/bv*+ba/b", "--merge-output-format", "mp4", url]
    if selected_format == "audio":
        return base + ["-f", "ba/b", "-x", "--audio-format", "m4a", "--embed-thumbnail", "--embed-metadata", url]
    if selected_format == "mp3":
        return base + ["-x", "--audio-format", "mp3", "--audio-quality", "0", "--embed-thumbnail", "--embed-metadata", url]
    return base + ["-f", "bv*+ba/b", "--merge-output-format", "mp4", url]


def detect_output_file(job_id: str) -> Path | None:
    files = sorted(DOWNLOAD_DIR.glob(f"{job_id}.*"), key=lambda path: path.stat().st_mtime, reverse=True)
    return files[0] if files else None


async def run_job(job_id: str, url: str, selected_format: str) -> None:
    update_job(job_id, progress=1, message="Starting")
    process = await asyncio.create_subprocess_exec(
        *command_for(job_id, url, selected_format),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    running_processes[job_id] = process
    assert process.stdout is not None
    progress_pattern = re.compile(r"\[download\]\s+(\d+(?:\.\d+)?)%")
    last_message = "Downloading"
    last_written_progress = 1.0
    last_write_time = time.monotonic()

    async for raw_line in process.stdout:
        line = raw_line.decode("utf-8", errors="replace").strip()
        if not line:
            continue
        last_message = line[-300:]
        match = progress_pattern.search(line)
        if match:
            new_progress = float(match.group(1))
            now = time.monotonic()
            if abs(new_progress - last_written_progress) >= 2.0 or (now - last_write_time) >= 3.0:
                update_job(job_id, progress=new_progress, message=last_message)
                last_written_progress = new_progress
                last_write_time = now
        else:
            update_job(job_id, message=last_message)

    running_processes.pop(job_id, None)
    return_code = await process.wait()

    with db_lock, open_db() as conn:
        row = conn.execute("SELECT status, user_email FROM jobs WHERE id = ?", (job_id,)).fetchone()
    if row and row["status"] == "cancelled":
        return

    if return_code != 0:
        update_job(job_id, status="failed", message=last_message, progress=0)
        await schedule_next_jobs()
        return

    file_path = detect_output_file(job_id)
    if not file_path:
        update_job(job_id, status="failed", message="Download finished but no file was created")
        await schedule_next_jobs()
        return

    from datetime import timezone as tz
    expires_at = datetime.fromtimestamp(time.time() + FILE_TTL_SECONDS, tz=timezone.utc).isoformat()
    update_job(
        job_id,
        status="completed",
        progress=100,
        message="Ready",
        file_path=str(file_path),
        file_name=file_path.name.removeprefix(f"{job_id}."),
        expires_at=expires_at,
    )
    if row and row["user_email"]:
        add_usage(str(row["user_email"]), "ingress_bytes", file_path.stat().st_size)
    await schedule_next_jobs()


async def schedule_next_jobs() -> None:
    if scheduler_lock is None:
        return
    async with scheduler_lock:
        with db_lock, open_db() as conn:
            running_count = conn.execute("SELECT COUNT(*) FROM jobs WHERE status = 'running'").fetchone()[0]
            slots = max(0, MAX_ACTIVE_JOBS - running_count)
            if slots == 0:
                return
            rows = conn.execute(
                "SELECT id, url, format FROM jobs WHERE status = 'queued' ORDER BY created_at ASC LIMIT ?",
                (slots,),
            ).fetchall()
            for row in rows:
                conn.execute(
                    "UPDATE jobs SET status = 'running', progress = 1, message = 'Starting', updated_at = ? WHERE id = ?",
                    (now_iso(), row["id"]),
                )
            conn.commit()
        for row in rows:
            asyncio.create_task(run_job(row["id"], row["url"], row["format"]))


# ── Health ────────────────────────────────────────────────────────────────────

@app.get("/api/health")
def health() -> dict[str, Any]:
    try:
        result = subprocess.run(
            ["yt-dlp", "--version"],
            capture_output=True, text=True, timeout=5, check=False,
        )
        ytdlp_version = result.stdout.strip() if result.returncode == 0 else "unknown"
    except Exception:
        ytdlp_version = "unknown"
    return {"status": "ok", "yt_dlp_version": ytdlp_version}


# ── Auth ──────────────────────────────────────────────────────────────────────

@app.get("/api/me")
def me(user: dict[str, Any] = Depends(require_user)) -> dict[str, Any]:
    return public_user(user)


@app.get("/api/admin/users")
def admin_users(_admin: dict[str, Any] = Depends(require_admin)) -> list[dict[str, Any]]:
    with db_lock, open_db() as conn:
        rows = conn.execute("SELECT * FROM access_users ORDER BY created_at DESC LIMIT 500").fetchall()
    return [public_user(row) for row in rows]


@app.patch("/api/admin/users/{user_id}")
def update_access_user(user_id: str, payload: AccessUpdateRequest, admin: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    with db_lock, open_db() as conn:
        target = conn.execute("SELECT * FROM access_users WHERE user_id = ?", (user_id,)).fetchone()
        if not target:
            raise HTTPException(status_code=404, detail="User not found")
        if payload.is_admin is not None:
            raise HTTPException(status_code=403, detail="Admin access is reserved for the verified GitHub owner account")
        if payload.usage_limit_bytes is not None:
            conn.execute("UPDATE access_users SET usage_limit_bytes = ?, updated_at = ? WHERE user_id = ?", (payload.usage_limit_bytes, now_iso(), user_id))
        conn.commit()
        updated = conn.execute("SELECT * FROM access_users WHERE user_id = ?", (user_id,)).fetchone()
    return public_user(updated)


@app.delete("/api/admin/users/{user_id}")
def remove_access_user(user_id: str, owner: dict[str, Any] = Depends(require_owner)) -> dict[str, bool]:
    if user_id == owner["user_id"]:
        raise HTTPException(status_code=409, detail="The owner account cannot be removed")
    with db_lock, open_db() as conn:
        conn.execute("DELETE FROM access_users WHERE user_id = ?", (user_id,))
        conn.commit()
    _clerk_profile_cache.pop(user_id, None)
    return {"deleted": True}


@app.post("/api/session/auto")
def session_auto() -> dict[str, str]:
    raise HTTPException(status_code=410, detail="Use a Clerk session token")


@app.post("/api/auth/login")
@app.post("/api/session/login")
def session_login(payload: LoginRequest, request: Request) -> dict[str, str]:
    raise HTTPException(status_code=410, detail="Password login has been replaced by Clerk")


# ── Analyze ───────────────────────────────────────────────────────────────────

@app.post("/api/metadata")
@app.post("/api/analyze")
def metadata(payload: UrlRequest, user: dict[str, Any] = Depends(require_user)) -> dict[str, Any]:
    assert_quota(user)
    _rate_limit(_analyze_calls, user["user_id"], limit=10, window=60)
    return run_metadata(validate_url(payload.url))


@app.post("/api/playlist")
def get_playlist(payload: UrlRequest, user: dict[str, Any] = Depends(require_user)) -> dict[str, Any]:
    assert_quota(user)
    url = validate_url(payload.url)
    completed = subprocess.run(
        ["yt-dlp", "--dump-single-json", "--flat-playlist", "--no-warnings"] + cookies_args() + [url],
        check=False, capture_output=True, text=True, timeout=60,
    )
    if completed.returncode != 0:
        raise HTTPException(status_code=400, detail=(completed.stderr or "Playlist lookup failed")[-500:])
    info = json.loads(completed.stdout)
    if info.get("_type") not in ("playlist", "multi_video") or not info.get("entries"):
        raise HTTPException(status_code=400, detail="Not a playlist URL")
    entries = []
    for e in info.get("entries", []):
        vid_id = e.get("id") or e.get("url", "").split("=")[-1]
        entries.append({
            "id": vid_id,
            "title": e.get("title") or e.get("url") or vid_id,
            "url": e.get("url") if e.get("url", "").startswith("http") else f"https://www.youtube.com/watch?v={vid_id}",
            "thumbnail": e.get("thumbnails", [{}])[-1].get("url") if e.get("thumbnails") else f"https://i.ytimg.com/vi/{vid_id}/mqdefault.jpg",
            "duration": e.get("duration"),
        })
    return {
        "title": info.get("title"),
        "uploader": info.get("uploader") or info.get("channel"),
        "entries": entries,
    }


# ── Jobs ──────────────────────────────────────────────────────────────────────

@app.post("/api/jobs")
async def create_job(payload: JobRequest, user: dict[str, Any] = Depends(require_user)) -> dict[str, Any]:
    url = validate_url(payload.url)
    ensure_temp_capacity()
    assert_quota(user)
    user_id = str(user["user_id"])

    with db_lock, open_db() as conn:
        queued_count = conn.execute("SELECT COUNT(*) FROM jobs WHERE status = 'queued'").fetchone()[0]
        if queued_count >= MAX_QUEUED_JOBS:
            raise HTTPException(status_code=429, detail=f"Queue is full ({MAX_QUEUED_JOBS} queued jobs)")

        user_active = conn.execute(
            "SELECT COUNT(*) FROM jobs WHERE user_email = ? AND status IN ('queued', 'running')",
            (user_id,),
        ).fetchone()[0]
        if user_active >= MAX_JOBS_PER_USER:
            raise HTTPException(
                status_code=429,
                detail=f"You already have {user_active} active/queued jobs. Wait for one to finish.",
            )

    title = payload.title
    thumbnail = payload.thumbnail
    if not title or not thumbnail:
        try:
            meta = run_metadata(url)
            title = title or meta.get("title")
            thumbnail = thumbnail or meta.get("thumbnail")
        except Exception:
            pass

    job_id = secrets.token_urlsafe(12)
    created_at = now_iso()
    with db_lock, open_db() as conn:
        conn.execute(
            """
            INSERT INTO jobs (id, url, title, thumbnail, format, status, progress, message, created_at, updated_at, user_email)
            VALUES (?, ?, ?, ?, ?, 'queued', 0, 'Queued', ?, ?, ?)
            """,
            (job_id, url, title, thumbnail, payload.format, created_at, created_at, user_id),
        )
        conn.commit()
    await schedule_next_jobs()
    return get_job_or_404(job_id)


@app.get("/api/jobs")
def list_jobs(user: dict[str, Any] = Depends(require_user)) -> list[dict[str, Any]]:
    with db_lock, open_db() as conn:
        rows = conn.execute("SELECT * FROM jobs WHERE user_email = ? ORDER BY created_at DESC LIMIT 50", (user["user_id"],)).fetchall()
    return [row_to_job(row) for row in rows]


class BulkDeleteRequest(BaseModel):
    ids: list[str]


@app.delete("/api/jobs")
def bulk_delete_jobs(req: BulkDeleteRequest, user: dict[str, Any] = Depends(require_user)) -> dict[str, int]:
    deleted = 0
    with db_lock, open_db() as conn:
        for job_id in req.ids:
            row = conn.execute("SELECT status, file_path FROM jobs WHERE id = ? AND user_email = ?", (job_id, user["user_id"])).fetchone()
            if not row or row["status"] in ("running", "queued"):
                continue
            if row["file_path"]:
                path = Path(row["file_path"]).resolve()
                if path.exists() and DOWNLOAD_DIR in path.parents:
                    path.unlink(missing_ok=True)
            conn.execute("DELETE FROM jobs WHERE id = ?", (job_id,))
            deleted += 1
        conn.commit()
    return {"deleted": deleted}


@app.delete("/api/jobs/{job_id}")
async def cancel_job(job_id: str, user: dict[str, Any] = Depends(require_user)) -> dict[str, str]:
    job = get_job_or_404(job_id)
    if job.get("user_email") != user["user_id"]:
        raise HTTPException(status_code=404, detail="Job not found")
    status = job["status"]
    if status in ("completed", "failed", "cancelled"):
        raise HTTPException(status_code=409, detail=f"Job is already {status}")
    if status == "running":
        proc = running_processes.get(job_id)
        if proc:
            try:
                proc.terminate()
            except Exception:
                pass
    update_job(job_id, status="cancelled", message="Cancelled by user", progress=0)
    return {"detail": "Cancelled"}


@app.delete("/api/admin/jobs/clear")
def clear_jobs(_admin: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    with db_lock, open_db() as conn:
        rows = conn.execute(
            "SELECT id, file_path FROM jobs WHERE status NOT IN ('running', 'queued')"
        ).fetchall()
        deleted = 0
        for row in rows:
            if row["file_path"]:
                path = Path(row["file_path"]).resolve()
                if path.exists() and DOWNLOAD_DIR in path.parents:
                    path.unlink(missing_ok=True)
            conn.execute("DELETE FROM jobs WHERE id = ?", (row["id"],))
            deleted += 1
        conn.commit()
    return {"deleted": deleted}


@app.get("/api/admin/jobs")
def admin_jobs(_admin: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    with db_lock, open_db() as conn:
        rows = conn.execute("SELECT * FROM jobs ORDER BY created_at DESC LIMIT 100").fetchall()
        total_count = conn.execute("SELECT COUNT(*) FROM jobs").fetchone()[0]
        running_count = conn.execute("SELECT COUNT(*) FROM jobs WHERE status = 'running'").fetchone()[0]
        queued_count = conn.execute("SELECT COUNT(*) FROM jobs WHERE status = 'queued'").fetchone()[0]
        completed_count = conn.execute("SELECT COUNT(*) FROM jobs WHERE status = 'completed'").fetchone()[0]
        failed_count = conn.execute("SELECT COUNT(*) FROM jobs WHERE status = 'failed'").fetchone()[0]
    usage = shutil.disk_usage(DOWNLOAD_DIR)
    return {
        "jobs": [row_to_job(row) for row in rows],
        "temp": {
            "used_bytes": directory_size_bytes(DOWNLOAD_DIR),
            "limit_bytes": int(MAX_TEMP_GB * 1024 * 1024 * 1024),
            "free_bytes": usage.free,
        },
        "limits": {
            "max_duration_seconds": MAX_DURATION_SECONDS,
            "max_active_jobs": MAX_ACTIVE_JOBS,
            "max_queued_jobs": MAX_QUEUED_JOBS,
            "file_ttl_seconds": FILE_TTL_SECONDS,
        },
        "system": {
            "platform": platform.system(),
            "python": platform.python_version(),
            "uptime_seconds": round(time.time() - SERVER_START_TIME, 1),
            "pid": os.getpid(),
        },
        "disk": {
            "total_bytes": usage.total,
            "used_bytes": usage.used,
            "free_bytes": usage.free,
            "percent_used": round(usage.used / usage.total * 100, 1),
        },
        "job_stats": {
            "total": total_count,
            "running": running_count,
            "queued": queued_count,
            "completed": completed_count,
            "failed": failed_count,
        },
    }


@app.get("/api/admin/files")
def list_cached_files(_admin: dict[str, Any] = Depends(require_admin)) -> list[dict[str, Any]]:
    with db_lock, open_db() as conn:
        rows = conn.execute(
            "SELECT id, title, file_name, file_path, format, created_at, expires_at FROM jobs WHERE file_path IS NOT NULL AND status = 'completed' ORDER BY created_at DESC"
        ).fetchall()
    result = []
    for row in rows:
        path = Path(row["file_path"]).resolve() if row["file_path"] else None
        size = path.stat().st_size if path and path.exists() else 0
        result.append({
            "id": row["id"],
            "title": row["title"],
            "file_name": row["file_name"],
            "format": row["format"],
            "created_at": row["created_at"],
            "expires_at": row["expires_at"],
            "size_bytes": size,
        })
    return result


@app.delete("/api/admin/files")
def delete_cached_files(job_ids: list[str], _admin: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    deleted = 0
    freed = 0
    for job_id in job_ids:
        with db_lock, open_db() as conn:
            row = conn.execute("SELECT file_path FROM jobs WHERE id = ?", (job_id,)).fetchone()
        if not row or not row["file_path"]:
            continue
        path = Path(row["file_path"]).resolve()
        if path.exists() and (path == DOWNLOAD_DIR or DOWNLOAD_DIR in path.parents):
            freed += path.stat().st_size
            path.unlink(missing_ok=True)
            deleted += 1
        with db_lock, open_db() as conn:
            conn.execute(
                "UPDATE jobs SET file_path = NULL, file_name = NULL, message = 'File deleted by admin', updated_at = ? WHERE id = ?",
                (now_iso(), job_id),
            )
            conn.commit()
    return {"deleted": deleted, "freed_bytes": freed}


@app.delete("/api/admin/cache")
def purge_cache(_admin: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    target_bytes = int(15 * 1024 * 1024 * 1024)
    deleted_files = 0
    freed_bytes = 0

    with db_lock, open_db() as conn:
        rows = conn.execute(
            "SELECT id, file_path FROM jobs WHERE file_path IS NOT NULL AND status = 'completed' ORDER BY created_at ASC"
        ).fetchall()

    for row in rows:
        if directory_size_bytes(DOWNLOAD_DIR) <= target_bytes:
            break
        file_path = row["file_path"]
        if not file_path:
            continue
        path = Path(file_path).resolve()
        if path.exists() and (path == DOWNLOAD_DIR or DOWNLOAD_DIR in path.parents):
            size = path.stat().st_size
            path.unlink(missing_ok=True)
            freed_bytes += size
            deleted_files += 1
        with db_lock, open_db() as conn:
            conn.execute(
                "UPDATE jobs SET file_path = NULL, file_name = NULL, message = 'File deleted by admin', updated_at = ? WHERE id = ?",
                (now_iso(), row["id"]),
            )
            conn.commit()

    return {
        "deleted_files": deleted_files,
        "freed_bytes": freed_bytes,
        "used_bytes": directory_size_bytes(DOWNLOAD_DIR),
    }


@app.get("/api/jobs/stream")
async def jobs_stream(token: str | None = Query(default=None)) -> StreamingResponse:
    if not token:
        raise HTTPException(status_code=401, detail="Missing token")
    user = ensure_access_user(verify_token(token))

    queue: asyncio.Queue = asyncio.Queue(maxsize=20)
    _sse_clients.add(queue)

    async def event_generator():
        try:
            with db_lock, open_db() as conn:
                rows = conn.execute("SELECT * FROM jobs ORDER BY created_at DESC LIMIT 50").fetchall()
            initial = json.dumps([row_to_job(row) for row in rows if row["user_email"] == user["user_id"]])
            yield f"data: {initial}\n\n"

            while True:
                try:
                    data = await asyncio.wait_for(queue.get(), timeout=25.0)
                    visible = [job for job in json.loads(data) if job.get("user_email") == user["user_id"]]
                    yield f"data: {json.dumps(visible)}\n\n"
                except asyncio.TimeoutError:
                    yield ": ping\n\n"
        except asyncio.CancelledError:
            pass
        finally:
            _sse_clients.discard(queue)

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@app.get("/api/jobs/{job_id}")
def get_job(job_id: str, user: dict[str, Any] = Depends(require_user)) -> dict[str, Any]:
    job = get_job_or_404(job_id)
    if job.get("user_email") != user["user_id"]:
        raise HTTPException(status_code=404, detail="Job not found")
    return job


# ── File download ─────────────────────────────────────────────────────────────

PLEX_MUSIC_DIR = Path("/mnt/BackupDrive/Media/Audio")


def clean_filename(title: str, ext: str) -> str:
    name = re.sub(r'[<>:"/\\|?*]', "", title)
    name = name.strip(". ")
    return f"{name}{ext}" if name else f"audio{ext}"


@app.post("/api/admin/files/{job_id}/send-to-plex")
def send_to_plex(job_id: str, _admin: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    job = get_job_or_404(job_id)
    if job["status"] != "completed" or not job.get("file_path"):
        raise HTTPException(status_code=404, detail="File not ready")
    src = Path(job["file_path"]).resolve()
    if src != DOWNLOAD_DIR and DOWNLOAD_DIR not in src.parents:
        raise HTTPException(status_code=403, detail="Invalid file path")
    if not src.exists():
        raise HTTPException(status_code=404, detail="File missing")
    if not PLEX_MUSIC_DIR.exists():
        raise HTTPException(status_code=503, detail="Plex Audio directory not mounted or unavailable")
    title = job.get("title") or src.stem
    dest_name = clean_filename(title, src.suffix)
    dest = PLEX_MUSIC_DIR / dest_name
    shutil.copy2(src, dest)
    return {"detail": "Copied to Plex", "destination": str(dest)}


@app.get("/api/files/{job_id}")
@app.get("/api/jobs/{job_id}/download")
def download_file(job_id: str, user: dict[str, Any] = Depends(require_user_or_query)) -> FileResponse:
    job = get_job_or_404(job_id)
    if job.get("user_email") != user["user_id"] and not user["is_admin"]:
        raise HTTPException(status_code=404, detail="File not found")
    if job["status"] != "completed" or not job.get("file_path"):
        raise HTTPException(status_code=404, detail="File not ready")
    path = Path(job["file_path"]).resolve()
    if path != DOWNLOAD_DIR and DOWNLOAD_DIR not in path.parents:
        raise HTTPException(status_code=403, detail="Invalid file path")
    if not path.exists():
        raise HTTPException(status_code=404, detail="File expired or missing")
    assert_quota(user, path.stat().st_size)
    add_usage(user["user_id"], "egress_bytes", path.stat().st_size)
    return FileResponse(path, filename=job.get("file_name") or path.name)
