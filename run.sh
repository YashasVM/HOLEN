#!/usr/bin/env sh
set -eu

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required. Install Docker Engine and Docker Compose v2 first." >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required (the 'docker compose' command was not found)." >&2
  exit 1
fi

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from safe defaults."
fi

docker compose up --build -d

app_port=$(sed -n 's/^APP_PORT=//p' .env | tail -n 1)
app_port=${app_port:-8080}

echo
echo "Holen is starting at http://localhost:${app_port}"
echo "Check status with: docker compose ps"
