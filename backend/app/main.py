from __future__ import annotations

import asyncio
import json
import os
import re
import sqlite3
import subprocess
import threading
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field


DOWNLOAD_DIR = Path(os.environ.get("DOWNLOAD_DIR", "./downloads")).resolve()
SQLITE_PATH = Path(os.environ.get("SQLITE_PATH", "./data/app.db")).resolve()
MAX_DURATION_SECONDS = int(os.environ.get("MAX_DURATION_SECONDS", "7200"))
MAX_ACTIVE_JOBS = max(1, int(os.environ.get("MAX_ACTIVE_JOBS", "2")))
MAX_QUEUED_JOBS = max(1, int(os.environ.get("MAX_QUEUED_JOBS", "20")))
CACHE_LIMIT_GB = max(1, float(os.environ.get("CACHE_LIMIT_GB", "20")))
CLEANUP_INTERVAL_SECONDS = max(60, int(os.environ.get("CLEANUP_INTERVAL_SECONDS", "900")))
ANALYZE_REQUESTS_PER_MINUTE = max(1, int(os.environ.get("ANALYZE_REQUESTS_PER_MINUTE", "8")))
JOB_REQUESTS_PER_HOUR = max(1, int(os.environ.get("JOB_REQUESTS_PER_HOUR", "12")))
YTDLP_COOKIES_FILE = os.environ.get("YTDLP_COOKIES_FILE", "")

DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
SQLITE_PATH.parent.mkdir(parents=True, exist_ok=True)

db_lock = threading.Lock()
scheduler_lock: asyncio.Lock | None = None
running_processes: dict[str, asyncio.subprocess.Process] = {}
request_log: dict[str, dict[str, list[float]]] = {"analyze": {}, "jobs": {}}


class UrlRequest(BaseModel):
    url: str = Field(min_length=8, max_length=4096)


class JobRequest(UrlRequest):
    format: str = Field(default="best", pattern="^(best|1080p|720p|audio|mp3)$")
    title: str | None = Field(default=None, max_length=512)
    thumbnail: str | None = Field(default=None, max_length=2048)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def open_db() -> sqlite3.Connection:
    connection = sqlite3.connect(SQLITE_PATH)
    connection.row_factory = sqlite3.Row
    return connection


def init_db() -> None:
    with db_lock, open_db() as connection:
        connection.execute(
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
                updated_at TEXT NOT NULL
            )
            """
        )
        connection.execute("CREATE INDEX IF NOT EXISTS jobs_status_created_idx ON jobs(status, created_at)")
        connection.commit()


init_db()


@asynccontextmanager
async def lifespan(_: FastAPI):
    global scheduler_lock
    scheduler_lock = asyncio.Lock()
    reset_interrupted_jobs()
    cleanup_cache()
    await schedule_next_jobs()
    cleanup_task = asyncio.create_task(cleanup_loop())
    try:
        yield
    finally:
        cleanup_task.cancel()
        try:
            await cleanup_task
        except asyncio.CancelledError:
            pass


app = FastAPI(title="Holen", version="0.1.0", lifespan=lifespan)


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    return response


def client_key(request: Request) -> str:
    """Use the proxy's first forwarded address; the API is not Docker-published."""
    forwarded = request.headers.get("x-forwarded-for", "").split(",")[0].strip()
    if forwarded:
        return forwarded
    return request.client.host if request.client else "unknown"


def enforce_rate_limit(bucket: str, key: str, limit: int, window_seconds: int) -> None:
    now = time.monotonic()
    calls = request_log[bucket].setdefault(key, [])
    calls[:] = [when for when in calls if now - when < window_seconds]
    if len(calls) >= limit:
        raise HTTPException(status_code=429, detail="Too many requests. Please try again later.")
    calls.append(now)


def validate_url(url: str) -> str:
    parsed = urlparse(url.strip())
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise HTTPException(status_code=400, detail="Enter a valid YouTube URL.")
    hostname = parsed.hostname.casefold().removeprefix("www.")
    allowed = {"youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"}
    if hostname not in allowed:
        raise HTTPException(status_code=400, detail="Only YouTube URLs are supported.")
    return url.strip()


def cookies_args() -> list[str]:
    path = Path(YTDLP_COOKIES_FILE)
    return ["--cookies", str(path)] if YTDLP_COOKIES_FILE and path.is_file() and path.stat().st_size else []


def metadata_command(url: str) -> list[str]:
    return ["yt-dlp", "--dump-single-json", "--no-playlist", "--no-warnings", *cookies_args(), url]


def read_metadata(url: str) -> dict[str, Any]:
    try:
        result = subprocess.run(
            metadata_command(url), capture_output=True, text=True, timeout=50, check=False
        )
    except subprocess.TimeoutExpired as error:
        raise HTTPException(status_code=504, detail="The video host took too long to respond.") from error
    if result.returncode != 0:
        detail = (result.stderr or "Could not read video information.").strip().splitlines()[-1]
        raise HTTPException(status_code=400, detail=detail[:400])
    try:
        info = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise HTTPException(status_code=400, detail="The video host returned invalid metadata.") from error
    duration = info.get("duration")
    if isinstance(duration, (int, float)) and duration > MAX_DURATION_SECONDS:
        raise HTTPException(
            status_code=400,
            detail=f"Videos must be {MAX_DURATION_SECONDS // 60} minutes or shorter.",
        )
    formats = [
        {
            "format_id": item.get("format_id"),
            "ext": item.get("ext"),
            "height": item.get("height"),
            "filesize": item.get("filesize") or item.get("filesize_approx"),
            "vcodec": item.get("vcodec"),
            "acodec": item.get("acodec"),
        }
        for item in info.get("formats", [])
        if item.get("format_id")
    ]
    return {
        "title": info.get("title"),
        "thumbnail": info.get("thumbnail"),
        "duration": duration,
        "uploader": info.get("uploader") or info.get("channel"),
        "webpage_url": info.get("webpage_url") or url,
        "formats": formats[-20:],
    }


def row_to_job(row: sqlite3.Row) -> dict[str, Any]:
    job = dict(row)
    if job.get("status") == "completed" and job.get("file_path"):
        job["download_url"] = f"/api/jobs/{job['id']}/download"
    return job


def get_job_or_404(job_id: str) -> dict[str, Any]:
    with db_lock, open_db() as connection:
        row = connection.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="Job not found.")
    return row_to_job(row)


def jobs_snapshot() -> list[dict[str, Any]]:
    with db_lock, open_db() as connection:
        rows = connection.execute("SELECT * FROM jobs ORDER BY created_at DESC LIMIT 50").fetchall()
    return [row_to_job(row) for row in rows]


def update_job(job_id: str, **fields: Any) -> None:
    fields["updated_at"] = now_iso()
    assignments = ", ".join(f"{column} = ?" for column in fields)
    with db_lock, open_db() as connection:
        connection.execute(f"UPDATE jobs SET {assignments} WHERE id = ?", (*fields.values(), job_id))
        connection.commit()


def is_download_file(path: Path) -> bool:
    resolved = path.resolve()
    return resolved != DOWNLOAD_DIR and DOWNLOAD_DIR in resolved.parents


def directory_size_bytes(path: Path) -> int:
    return sum(item.stat().st_size for item in path.rglob("*") if item.is_file())


def cleanup_cache() -> None:
    """Keep current jobs, then evict oldest completed files only when needed."""
    limit = int(CACHE_LIMIT_GB * 1024**3)
    if directory_size_bytes(DOWNLOAD_DIR) < limit:
        return
    with db_lock, open_db() as connection:
        rows = connection.execute(
            "SELECT id, file_path FROM jobs WHERE status = 'completed' AND file_path IS NOT NULL ORDER BY created_at ASC"
        ).fetchall()
        for row in rows:
            if directory_size_bytes(DOWNLOAD_DIR) < limit:
                break
            path = Path(row["file_path"])
            if path.exists() and is_download_file(path):
                path.unlink(missing_ok=True)
            connection.execute(
                "UPDATE jobs SET file_path = NULL, file_name = NULL, message = ?, updated_at = ? WHERE id = ?",
                ("Removed to make room in the download cache.", now_iso(), row["id"]),
            )
        connection.commit()


def ensure_capacity() -> None:
    cleanup_cache()
    if directory_size_bytes(DOWNLOAD_DIR) >= int(CACHE_LIMIT_GB * 1024**3):
        raise HTTPException(status_code=507, detail="The download cache is full. Clear completed downloads and try again.")


def reset_interrupted_jobs() -> None:
    with db_lock, open_db() as connection:
        connection.execute(
            "UPDATE jobs SET status = 'failed', message = 'Server restarted during this download.', updated_at = ? WHERE status = 'running'",
            (now_iso(),),
        )
        connection.commit()


async def cleanup_loop() -> None:
    while True:
        await asyncio.sleep(CLEANUP_INTERVAL_SECONDS)
        await asyncio.to_thread(cleanup_cache)


def output_template(job_id: str) -> str:
    return str(DOWNLOAD_DIR / f"{job_id}.%(title).180B.%(ext)s")


def command_for(job_id: str, url: str, selected_format: str) -> list[str]:
    base = [
        "yt-dlp", "--newline", "--no-playlist", "--restrict-filenames", "--continue",
        "--concurrent-fragments", "4", "--retries", "3", "--fragment-retries", "3",
        "--file-access-retries", "3", "--socket-timeout", "20", "-o", output_template(job_id),
        *cookies_args(),
    ]
    if selected_format == "1080p":
        return [*base, "-f", "bv*[height<=1080]+ba/b[height<=1080]/b", "--merge-output-format", "mp4", url]
    if selected_format == "720p":
        return [*base, "-f", "bv*[height<=720]+ba/b[height<=720]/b", "--merge-output-format", "mp4", url]
    if selected_format == "audio":
        return [*base, "-f", "ba/b", "-x", "--audio-format", "m4a", "--embed-metadata", url]
    if selected_format == "mp3":
        return [*base, "-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0", "--embed-metadata", url]
    return [*base, "-f", "bv*+ba/b", "--merge-output-format", "mp4", url]


def detect_output_file(job_id: str) -> Path | None:
    candidates = [
        path for path in DOWNLOAD_DIR.glob(f"{job_id}.*")
        if path.is_file() and not path.name.endswith((".part", ".ytdl"))
    ]
    return max(candidates, key=lambda path: path.stat().st_mtime) if candidates else None


async def run_job(job_id: str, url: str, selected_format: str) -> None:
    update_job(job_id, progress=1, message="Starting download…")
    process = await asyncio.create_subprocess_exec(
        *command_for(job_id, url, selected_format),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    running_processes[job_id] = process
    assert process.stdout is not None
    progress_pattern = re.compile(r"\[download\]\s+(\d+(?:\.\d+)?)%")
    last_message = "Downloading…"
    last_saved_progress = 1.0
    last_saved_at = time.monotonic()

    async for raw_line in process.stdout:
        line = raw_line.decode("utf-8", errors="replace").strip()
        if not line:
            continue
        last_message = line[-300:]
        match = progress_pattern.search(line)
        if match:
            progress = float(match.group(1))
            now = time.monotonic()
            if progress - last_saved_progress >= 2 or now - last_saved_at >= 3:
                update_job(job_id, progress=progress, message=last_message)
                last_saved_progress, last_saved_at = progress, now

    running_processes.pop(job_id, None)
    return_code = await process.wait()
    job = get_job_or_404(job_id)
    if job["status"] == "cancelled":
        await schedule_next_jobs()
        return
    if return_code != 0:
        update_job(job_id, status="failed", progress=0, message=last_message)
        await schedule_next_jobs()
        return

    file_path = detect_output_file(job_id)
    if not file_path:
        update_job(job_id, status="failed", message="Download finished but no file was created.")
    else:
        update_job(
            job_id,
            status="completed",
            progress=100,
            message="Ready to save.",
            file_path=str(file_path),
            file_name=file_path.name.removeprefix(f"{job_id}."),
        )
        await asyncio.to_thread(cleanup_cache)
    await schedule_next_jobs()


async def schedule_next_jobs() -> None:
    if scheduler_lock is None:
        return
    async with scheduler_lock:
        with db_lock, open_db() as connection:
            running = connection.execute("SELECT COUNT(*) FROM jobs WHERE status = 'running'").fetchone()[0]
            slots = max(0, MAX_ACTIVE_JOBS - running)
            if not slots:
                return
            rows = connection.execute(
                "SELECT id, url, format FROM jobs WHERE status = 'queued' ORDER BY created_at ASC LIMIT ?", (slots,)
            ).fetchall()
            for row in rows:
                connection.execute(
                    "UPDATE jobs SET status = 'running', progress = 1, message = 'Starting download…', updated_at = ? WHERE id = ?",
                    (now_iso(), row["id"]),
                )
            connection.commit()
        for row in rows:
            asyncio.create_task(run_job(row["id"], row["url"], row["format"]))


@app.get("/api/health")
def health() -> dict[str, str]:
    try:
        result = subprocess.run(["yt-dlp", "--version"], capture_output=True, text=True, timeout=5, check=False)
        version = result.stdout.strip() if result.returncode == 0 else "unavailable"
    except OSError:
        version = "unavailable"
    return {"status": "ok", "yt_dlp_version": version}


@app.post("/api/analyze")
async def analyze(payload: UrlRequest, request: Request) -> dict[str, Any]:
    enforce_rate_limit("analyze", client_key(request), ANALYZE_REQUESTS_PER_MINUTE, 60)
    return await asyncio.to_thread(read_metadata, validate_url(payload.url))


@app.post("/api/jobs")
async def create_job(payload: JobRequest, request: Request) -> dict[str, Any]:
    enforce_rate_limit("jobs", client_key(request), JOB_REQUESTS_PER_HOUR, 3600)
    url = validate_url(payload.url)
    await asyncio.to_thread(ensure_capacity)
    with db_lock, open_db() as connection:
        queued = connection.execute("SELECT COUNT(*) FROM jobs WHERE status = 'queued'").fetchone()[0]
        if queued >= MAX_QUEUED_JOBS:
            raise HTTPException(status_code=429, detail="The download queue is full. Try again shortly.")

    metadata = await asyncio.to_thread(read_metadata, url)
    job_id = os.urandom(9).hex()
    timestamp = now_iso()
    with db_lock, open_db() as connection:
        connection.execute(
            """
            INSERT INTO jobs (id, url, title, thumbnail, format, status, progress, message, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'queued', 0, 'Queued', ?, ?)
            """,
            (job_id, metadata.get("webpage_url", url), payload.title or metadata.get("title"), payload.thumbnail or metadata.get("thumbnail"), payload.format, timestamp, timestamp),
        )
        connection.commit()
    await schedule_next_jobs()
    return get_job_or_404(job_id)


@app.get("/api/jobs")
def list_jobs() -> list[dict[str, Any]]:
    return jobs_snapshot()


@app.delete("/api/jobs/{job_id}")
async def delete_or_cancel_job(job_id: str) -> dict[str, str]:
    job = get_job_or_404(job_id)
    if job["status"] in {"queued", "running"}:
        process = running_processes.get(job_id)
        if process:
            process.terminate()
        update_job(job_id, status="cancelled", progress=0, message="Cancelled.")
        return {"detail": "Cancelled"}

    path = Path(job["file_path"]) if job.get("file_path") else None
    if path and path.exists() and is_download_file(path):
        path.unlink(missing_ok=True)
    with db_lock, open_db() as connection:
        connection.execute("DELETE FROM jobs WHERE id = ?", (job_id,))
        connection.commit()
    return {"detail": "Removed"}


@app.get("/api/jobs/{job_id}/download")
def download_file(job_id: str) -> FileResponse:
    job = get_job_or_404(job_id)
    if job["status"] != "completed" or not job.get("file_path"):
        raise HTTPException(status_code=404, detail="This file is not ready.")
    path = Path(job["file_path"])
    if not path.exists() or not is_download_file(path):
        raise HTTPException(status_code=404, detail="This file is no longer available.")
    return FileResponse(path, filename=job.get("file_name") or path.name)
