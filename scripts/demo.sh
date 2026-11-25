#!/usr/bin/env bash
# Starts the Pulse demo from a fresh clone with one command (task 23.5, docs/demo.md): creates the demo
# database if needed, builds the backend jar and installs the frontend packages when missing, then
# runs the backend with the demo seed on and "today" pinned to 28 Sep 2026, and the web app on top.
# Ctrl+C stops both.
#
# Usage: scripts/demo.sh [--reset] [--build]
#   --reset  drop and recreate the demo database first: every demo account and number back to the seed
#   --build  rebuild the backend jar even when one exists (after a pull)
#
# Needs Java 21, Maven, Node 20+ and a PostgreSQL 16 the DB_* settings reach (docker compose up -d
# postgres gives one). Settings (environment, defaults in brackets): DB_HOST [localhost], DB_PORT
# [5432], DB_USER [pulse], DB_PASSWORD [pulse_secret_dev], PULSE_DEMO_DB [pulse_demo], API_PORT [8088],
# WEB_PORT [5173], PULSE_TODAY [2026-09-28].
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"

reset=false
build=false

for arg in "$@"; do
  case "$arg" in
    --reset) reset=true ;;
    --build) build=true ;;
    -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (use --reset, --build or --help)" >&2; exit 2 ;;
  esac
done

export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_USER="${DB_USER:-pulse}"
export DB_PASSWORD="${DB_PASSWORD:-pulse_secret_dev}"
export DB_NAME="${PULSE_DEMO_DB:-pulse_demo}"

API_PORT="${API_PORT:-8088}"
WEB_PORT="${WEB_PORT:-5173}"

for tool in java mvn node npm psql; do
  command -v "$tool" >/dev/null || { echo "Missing '$tool': see docs/demo.md, Requirements" >&2; exit 1; }
done

psql_admin() {
  PGPASSWORD="$DB_PASSWORD" PGOPTIONS='--client-min-messages=warning' psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -qtA "$@"
}

if ! psql_admin -c "SELECT 1" >/dev/null 2>&1; then
  echo "Cannot reach PostgreSQL at $DB_HOST:$DB_PORT as $DB_USER: start it (docker compose up -d postgres) or set DB_*" >&2
  exit 1
fi

if $reset; then
  echo "Resetting database $DB_NAME"
  psql_admin -c "DROP DATABASE IF EXISTS \"$DB_NAME\" WITH (FORCE)"
fi

if [ -z "$(psql_admin -c "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'")" ]; then
  echo "Creating database $DB_NAME"
  psql_admin -c "CREATE DATABASE \"$DB_NAME\""
fi

jar="$root/backend/target/pulse-backend-1.0.0-SNAPSHOT-fat.jar"

if $build || [ ! -f "$jar" ]; then
  echo "Building the backend"
  mvn -q -f "$root/backend/pom.xml" package -DskipTests
fi

if [ ! -d "$root/frontend/node_modules" ]; then
  echo "Installing the frontend packages"
  (cd "$root/frontend" && npm ci --no-audit --no-fund)
fi

backend_pid=""

stop() {
  if [ -n "$backend_pid" ] && kill -0 "$backend_pid" 2>/dev/null; then
    kill "$backend_pid" 2>/dev/null || true
    wait "$backend_pid" 2>/dev/null || true
  fi
}

trap stop EXIT INT TERM

echo "Starting the backend on :$API_PORT (database $DB_NAME, log backend/target/demo-backend.log)"

PORT="$API_PORT" \
PULSE_PROFILE=development \
PULSE_DEMO_SEED=true \
PULSE_TODAY="${PULSE_TODAY:-2026-09-28}" \
PULSE_APP_URL="http://localhost:$WEB_PORT" \
  java -jar "$jar" > "$root/backend/target/demo-backend.log" 2>&1 &
backend_pid=$!

# the first start applies every migration and loads the demo seed: allow two minutes
for _ in $(seq 1 120); do
  if curl -fs "http://localhost:$API_PORT/health" >/dev/null 2>&1; then
    break
  fi

  if ! kill -0 "$backend_pid" 2>/dev/null; then
    echo "The backend stopped while starting; last lines of backend/target/demo-backend.log:" >&2
    tail -n 20 "$root/backend/target/demo-backend.log" >&2
    exit 1
  fi

  sleep 1
done

curl -fs "http://localhost:$API_PORT/health" >/dev/null || { echo "The backend did not answer /health in 2 minutes" >&2; exit 1; }

cat <<EOF

Pulse demo: http://localhost:$WEB_PORT   (today is pinned to ${PULSE_TODAY:-2026-09-28})
Password for every account: demo123
  hr@pulse.demo     Admin (HR)          rahul@pulse.demo  Manager (Platform)
  priya@pulse.demo  Employee            more accounts: docs/demo.md
Ctrl+C stops the demo.

EOF

cd "$root/frontend"

PULSE_API_TARGET="http://localhost:$API_PORT" npx vite --port "$WEB_PORT" --strictPort
