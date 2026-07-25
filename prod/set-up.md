# Holen setup

The current setup is Clerk-only; Convex and the legacy shared app password are
not used.

1. Create a Clerk application and register your local or production origin.
2. Copy `.env.example` to `.env` and set `CLERK_FRONTEND_API_URL`,
   `CLERK_SECRET_KEY`, `VITE_CLERK_PUBLISHABLE_KEY`,
   `OWNER_GITHUB_USERNAME`, and `PUBLIC_ORIGIN`.
3. For local Vite development only, create `frontend/.env.local` with:

   ```env
   VITE_CLERK_PUBLISHABLE_KEY=pk_...
   ```

4. Build and run the containers:

   ```bash
   docker compose up --build -d
   ```

5. Verify the service:

   ```bash
   docker compose ps
   curl http://localhost:8888/api/health
   ```

For development, run FastAPI from `backend/` and `npm run dev` from
`frontend/`. The backend needs `CLERK_FRONTEND_API_URL` and
`CLERK_SECRET_KEY`; the Vite app needs `VITE_CLERK_PUBLISHABLE_KEY`. Docker
injects the public key from the root `.env` and excludes frontend `.env` files
from its build context.

See [README.md](README.md) for configuration, security, and deployment notes.
