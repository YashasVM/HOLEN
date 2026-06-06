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
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel, Field


APP_PASSWORD = os.environ["APP_PASSWORD"]
APP_SECRET = os.environ["APP_SECRET"].encode("utf-8")
PUBLIC_ORIGIN = os.environ.get("PUBLIC_ORIGIN", "http://localhost:8080")
DOWNLOAD_DIR = Path(os.environ.get("DOWNLOAD_DIR", "./downloads")).resolve()
SQLITE_PATH = Path(os.environ.get("SQLITE_PATH", "./data/app.db")).resolve()
TOKEN_TTL_SECONDS = 60 * 60 * 24 * 30
MAX_DURATION_SECONDS = int(os.environ.get("MAX_DURATION_SECONDS", str(2 * 60 * 60)))
MAX_ACTIVE_JOBS = int(os.environ.get("MAX_ACTIVE_JOBS", "1"))
MAX_QUEUED_JOBS = int(os.environ.get("MAX_QUEUED_JOBS", "5"))
MAX_TEMP_GB = float(os.environ.get("MAX_TEMP_GB", "20"))
FILE_TTL_SECONDS = int(os.environ.get("FILE_TTL_SECONDS", str(60 * 60)))
CLEANUP_INTERVAL_SECONDS = int(os.environ.get("CLEANUP_INTERVAL_SECONDS", str(15 * 60)))
SERVER_START_TIME = time.time()

DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
SQLITE_PATH.parent.mkdir(parents=True, exist_ok=True)

db_lock = threading.Lock()
scheduler_lock: asyncio.Lock | None = None

# SSE: set of queues, one per connected client
_sse_clients: set[asyncio.Queue] = set()

# Running subprocess handles keyed by job_id
running_processes: dict[str, asyncio.subprocess.Process] = {}

# Rate limiting: token subject -> list of call timestamps
_analyze_calls: dict[str, list[float]] = {}


class LoginRequest(BaseModel):
    password: str = Field(min_length=1)


class UrlRequest(BaseModel):
    url: str = Field(min_length=8, max_length=4096)


class JobRequest(UrlRequest):
    format: str = Field(pattern="^(best|best_video|1080p|720p|audio|mp3)$")
    title: str | None = None
    thumbnail: str | None = None


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
                expires_at TEXT
            )
            """
        )
        columns = [row["name"] for row in conn.execute("PRAGMA table_info(jobs)").fetchall()]
        if "expires_at" not in columns:
            conn.execute("ALTER TABLE jobs ADD COLUMN expires_at TEXT")
        if "thumbnail" not in columns:
            conn.execute("ALTER TABLE jobs ADD COLUMN thumbnail TEXT")
        conn.commit()


init_db()


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def sign(payload: dict[str, Any]) -> str:
    body = b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    signature = b64url(hmac.new(APP_SECRET, body.encode("ascii"), hashlib.sha256).digest())
    return f"{body}.{signature}"


def verify_token(token: str) -> dict[str, Any]:
    try:
        body, signature = token.split(".", 1)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="Invalid token") from exc

    expected = b64url(hmac.new(APP_SECRET, body.encode("ascii"), hashlib.sha256).digest())
    if not hmac.compare_digest(signature, expected):
        raise HTTPException(status_code=401, detail="Invalid token")

    padded = body + ("=" * (-len(body) % 4))
    payload = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")))
    if payload.get("exp", 0) < int(time.time()):
        raise HTTPException(status_code=401, detail="Token expired")
    return payload


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


def validate_url(url: str) -> str:
    parsed = urlparse(url.strip())
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise HTTPException(status_code=400, detail="Enter a valid http(s) URL")
    host = parsed.netloc.lower().removeprefix("www.")
    allowed_hosts = {"youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"}
    if host not in allowed_hosts:
        raise HTTPException(status_code=400, detail="Only YouTube URLs are allowed for this private tool")
    query = parse_qs(parsed.query)
    if "list" in query or parsed.path.startswith("/playlist"):
        raise HTTPException(status_code=400, detail="Playlists are disabled. Paste a single video URL")
    return url.strip()


def _notify_sse(jobs: list[dict[str, Any]]) -> None:
    """Push updated job list to all connected SSE clients."""
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
    # Notify SSE clients after every job update
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
    """On restart: fail running jobs (can't resume), leave queued jobs alone."""
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
    cutoff = now_iso()
    with db_lock, open_db() as conn:
        rows = conn.execute(
            "SELECT id, file_path FROM jobs WHERE expires_at IS NOT NULL AND expires_at < ?",
            (cutoff,),
        ).fetchall()
        for row in rows:
            if row["file_path"]:
                path = Path(row["file_path"]).resolve()
                if path.exists() and (path == DOWNLOAD_DIR or DOWNLOAD_DIR in path.parents):
                    path.unlink(missing_ok=True)
            conn.execute(
                """
                UPDATE jobs
                SET file_path = NULL, file_name = NULL, message = 'Expired and deleted', updated_at = ?
                WHERE id = ?
                """,
                (cutoff, row["id"]),
            )
        conn.commit()

    for path in DOWNLOAD_DIR.glob("*"):
        if path.is_file() and path.stat().st_mtime < time.time() - FILE_TTL_SECONDS:
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


def run_metadata(url: str) -> dict[str, Any]:
    completed = subprocess.run(
        ["yt-dlp", "--dump-single-json", "--no-playlist", "--no-warnings", url],
        check=False,
        capture_output=True,
        text=True,
        timeout=45,
    )
    if completed.returncode != 0:
        raise HTTPException(status_code=400, detail=(completed.stderr or "Metadata lookup failed")[-500:])
    info = json.loads(completed.stdout)
    if info.get("_type") == "playlist" or info.get("entries"):
        raise HTTPException(status_code=400, detail="Playlists are disabled. Paste a single video URL")
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
    ]
    if selected_format == "best_video":
        return base + ["-f", "bv*+ba/b", "--merge-output-format", "mp4", url]
    if selected_format == "1080p":
        return base + ["-f", "bv*[height<=1080]+ba/b[height<=1080]/b", "--merge-output-format", "mp4", url]
    if selected_format == "720p":
        return base + ["-f", "bv*[height<=720]+ba/b[height<=720]/b", "--merge-output-format", "mp4", url]
    if selected_format == "audio":
        return base + ["-f", "ba/b", url]
    if selected_format == "mp3":
        return base + ["-x", "--audio-format", "mp3", "--audio-quality", "0", url]
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
            # Throttle: write only if progress changed ≥2% or ≥3s elapsed
            if abs(new_progress - last_written_progress) >= 2.0 or (now - last_write_time) >= 3.0:
                update_job(job_id, progress=new_progress, message=last_message)
                last_written_progress = new_progress
                last_write_time = now
        else:
            update_job(job_id, message=last_message)

    running_processes.pop(job_id, None)
    return_code = await process.wait()

    # Check if job was cancelled while running
    with db_lock, open_db() as conn:
        row = conn.execute("SELECT status FROM jobs WHERE id = ?", (job_id,)).fetchone()
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

    expires_at = datetime.now(timezone.utc) + timedelta(seconds=FILE_TTL_SECONDS)
    update_job(
        job_id,
        status="completed",
        progress=100,
        message="Ready",
        file_path=str(file_path),
        file_name=file_path.name.removeprefix(f"{job_id}."),
        expires_at=expires_at.isoformat(),
    )
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

@app.post("/api/auth/login")
def login(payload: LoginRequest) -> dict[str, str]:
    expected_pw = os.getenv("APP_PASSWORD", "dev")
    if not secrets.compare_digest(payload.password.strip(), expected_pw):
        raise HTTPException(status_code=401, detail="Wrong password")
    token = sign({"sub": "shared", "exp": int(time.time()) + TOKEN_TTL_SECONDS})
    return {"token": token}


@app.post("/api/session/login")
def session_login(payload: LoginRequest) -> dict[str, str]:
    return login(payload)


# ── Analyze ───────────────────────────────────────────────────────────────────

@app.post("/api/metadata", dependencies=[Depends(require_auth)])
@app.post("/api/analyze", dependencies=[Depends(require_auth)])
def metadata(payload: UrlRequest, auth: dict[str, Any] = Depends(require_auth)) -> dict[str, Any]:
    # Rate limit: 10 calls per minute per token subject
    subject = auth.get("sub", "unknown")
    now = time.time()
    calls = _analyze_calls.setdefault(subject, [])
    _analyze_calls[subject] = [t for t in calls if now - t < 60]
    if len(_analyze_calls[subject]) >= 10:
        raise HTTPException(status_code=429, detail="Rate limit: 10 analyzes per minute")
    _analyze_calls[subject].append(now)
    return run_metadata(validate_url(payload.url))


# ── Jobs ──────────────────────────────────────────────────────────────────────

@app.post("/api/jobs", dependencies=[Depends(require_auth)])
async def create_job(payload: JobRequest) -> dict[str, Any]:
    url = validate_url(payload.url)
    ensure_temp_capacity()
    with db_lock, open_db() as conn:
        queued_count = conn.execute("SELECT COUNT(*) FROM jobs WHERE status = 'queued'").fetchone()[0]
    if queued_count >= MAX_QUEUED_JOBS:
        raise HTTPException(status_code=429, detail=f"Queue is full. Try again after one of the {MAX_QUEUED_JOBS} queued jobs starts")

    # Use client-supplied title/thumbnail if provided; skip metadata fetch
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
            INSERT INTO jobs (id, url, title, thumbnail, format, status, progress, message, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'queued', 0, 'Queued', ?, ?)
            """,
            (job_id, url, title, thumbnail, payload.format, created_at, created_at),
        )
        conn.commit()
    await schedule_next_jobs()
    return get_job_or_404(job_id)


@app.get("/api/jobs", dependencies=[Depends(require_auth)])
def list_jobs() -> list[dict[str, Any]]:
    with db_lock, open_db() as conn:
        rows = conn.execute("SELECT * FROM jobs ORDER BY created_at DESC LIMIT 50").fetchall()
    return [row_to_job(row) for row in rows]


@app.delete("/api/jobs/{job_id}", dependencies=[Depends(require_auth)])
async def cancel_job(job_id: str) -> dict[str, str]:
    job = get_job_or_404(job_id)
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


@app.get("/api/admin/jobs", dependencies=[Depends(require_auth)])
def admin_jobs() -> dict[str, Any]:
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


@app.get("/api/jobs/stream")
async def jobs_stream(token: str | None = Query(default=None)) -> StreamingResponse:
    """SSE endpoint — streams job list updates to connected clients."""
    if not token:
        raise HTTPException(status_code=401, detail="Missing token")
    verify_token(token)

    queue: asyncio.Queue = asyncio.Queue(maxsize=20)
    _sse_clients.add(queue)

    async def event_generator():
        try:
            # Send current state immediately on connect
            with db_lock, open_db() as conn:
                rows = conn.execute("SELECT * FROM jobs ORDER BY created_at DESC LIMIT 50").fetchall()
            initial = json.dumps([row_to_job(row) for row in rows])
            yield f"data: {initial}\n\n"

            while True:
                try:
                    data = await asyncio.wait_for(queue.get(), timeout=25.0)
                    yield f"data: {data}\n\n"
                except asyncio.TimeoutError:
                    # Keepalive ping
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
            "X-Accel-Buffering": "no",  # disable nginx buffering
        },
    )


@app.get("/api/jobs/{job_id}", dependencies=[Depends(require_auth)])
def get_job(job_id: str) -> dict[str, Any]:
    return get_job_or_404(job_id)


# ── File download ─────────────────────────────────────────────────────────────

@app.get("/api/files/{job_id}")
@app.get("/api/jobs/{job_id}/download")
def download_file(job_id: str, _auth: dict[str, Any] = Depends(require_auth_or_query)) -> FileResponse:
    job = get_job_or_404(job_id)
    if job["status"] != "completed" or not job.get("file_path"):
        raise HTTPException(status_code=404, detail="File not ready")
    path = Path(job["file_path"]).resolve()
    if path != DOWNLOAD_DIR and DOWNLOAD_DIR not in path.parents:
        raise HTTPException(status_code=403, detail="Invalid file path")
    if not path.exists():
        raise HTTPException(status_code=404, detail="File missing")
    return FileResponse(path, filename=job.get("file_name") or path.name)
