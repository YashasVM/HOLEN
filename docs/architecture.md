# Architecture

The browser talks only to the Nginx frontend. Nginx serves the Vite build and proxies `/api` requests to the FastAPI backend.

The backend stores lightweight queue metadata in SQLite and keeps completed files under `downloads/`. A small scheduler starts up to `MAX_ACTIVE_JOBS` `yt-dlp` processes at a time. Completed files remain available until a user removes them or cache pressure evicts the oldest completed files.

The backend container is not exposed on a host port. This keeps the browser and API on the same origin and avoids a separate browser CORS configuration.
