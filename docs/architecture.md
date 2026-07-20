# Architecture

The native launcher builds the Vite frontend, then starts FastAPI as the single local server. FastAPI serves the built assets and handles `/api` requests on the same origin.

The backend stores lightweight queue metadata in SQLite and keeps completed files under `downloads/`. A small scheduler starts up to `MAX_ACTIVE_JOBS` `yt-dlp` processes at a time. Completed files remain available until a user removes them or cache pressure evicts the oldest completed files.

The server binds to `127.0.0.1`, which keeps the browser and API on the same origin and avoids a separate browser CORS configuration.
