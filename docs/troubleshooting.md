# Troubleshooting

## A video cannot be read

Confirm the URL is a YouTube video or Short and update the backend image to pick up the latest compatible `yt-dlp` release:

```bash
docker compose build --no-cache backend
docker compose up -d
```

Some sources require an authenticated browser session. If you are permitted to use one, set `YTDLP_COOKIES_PATH` to a local Netscape-format cookies file. Never commit that file.

## The queue is full or slow

Wait for a running job to complete, remove completed files, or adjust `MAX_ACTIVE_JOBS`, `MAX_QUEUED_JOBS`, and `CACHE_LIMIT_GB` in `.env` to match the host's CPU, disk, and network capacity.

## The page does not open

Run `docker compose ps`, then inspect `docker compose logs --tail=100`. The frontend health depends on the backend `/api/health` endpoint.
