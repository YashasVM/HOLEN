# Troubleshooting

## A video cannot be read

Confirm the URL is a YouTube video or Short, then reinstall the launcher environment to pick up the latest compatible `yt-dlp` release:

```bash
rm -rf backend/.venv
./run.sh
```

Some sources require an authenticated browser session. If you are permitted to use one, set `YTDLP_COOKIES_PATH` to a local Netscape-format cookies file. Never commit that file.

## The queue is full or slow

Wait for a running job to complete, remove completed files, or adjust `MAX_ACTIVE_JOBS`, `MAX_QUEUED_JOBS`, and `CACHE_LIMIT_GB` in `.env` to match the host's CPU, disk, and network capacity.

## The page does not open

Inspect `holen.log`, then visit `http://localhost:8080/api/health`. The launcher installs Python 3.10+ and `ffmpeg` when they are absent. Confirm Node.js 18+ is installed, and if the launcher just installed a runtime package, open a new terminal so its updated `PATH` is available before running it again.
