#!/usr/bin/env bash
# Starts Pulse on live Jira data (Phase 2, docs/demo.md section 8): its own database (pulse_live), with
# no demo company (the demo seed is off) and the real date as "today", so the Jira sprint running now is
# the live sprint. On the first start it asks for your Admin email and password and creates that Admin
# (PULSE_ADMIN_EMAIL, task 24.9). Import the real team with Users → CSV import (work emails as in Jira),
# then connect Jira in Integrations. Ctrl+C stops both servers.
#
# Usage: scripts/live.sh [--reset] [--build]
#   --reset  drop and recreate pulse_live first (asks to confirm: it deletes imported users and Jira rows)
#   --build  rebuild the backend jar even when one exists (after a pull)
#
# Runs beside scripts/demo.sh: other ports (API 8090, web 5175) and another key file. Settings
# (environment, defaults in brackets): DB_HOST [localhost], DB_PORT [5432], DB_USER [pulse], DB_PASSWORD
# [pulse_secret_dev], PULSE_LIVE_DB [pulse_live], API_PORT [8090], WEB_PORT [5175], PULSE_SECRET_KEY
# [generated once into .pulse/live-secret-key, which git ignores; it encrypts the Jira token].
# This database holds real employee data: keep it on this machine and share no dump of it.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"

reset=false
build=false

for arg in "$@"; do
  case "$arg" in
    --reset) reset=true ;;
    --build) build=true ;;
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (use --reset, --build or --help)" >&2; exit 2 ;;
  esac
done

export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_USER="${DB_USER:-pulse}"
export DB_PASSWORD="${DB_PASSWORD:-pulse_secret_dev}"
export DB_NAME="${PULSE_LIVE_DB:-pulse_live}"

API_PORT="${API_PORT:-8090}"
WEB_PORT="${WEB_PORT:-5175}"

# the key that encrypts the Jira token: kept across runs (and --reset), so the saved token stays readable
if [ -z "${PULSE_SECRET_KEY:-}" ]; then
  key_file="$root/.pulse/live-secret-key"
  if [ ! -s "$key_file" ]; then
    mkdir -p "$(dirname "$key_file")"
    (umask 077 && head -c 32 /dev/urandom | base64 > "$key_file")
  fi
  PULSE_SECRET_KEY="$(cat "$key_file")"
fi
export PULSE_SECRET_KEY

for tool in java mvn node npm psql curl; do
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
  read -r -p "Drop database $DB_NAME with every imported user and Jira row? Type the name to confirm: " answer
  if [ "$answer" != "$DB_NAME" ]; then
    echo "Not reset." >&2
    exit 1
  fi
  psql_admin -c "DROP DATABASE IF EXISTS \"$DB_NAME\" WITH (FORCE)"
fi

if [ -z "$(psql_admin -c "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'")" ]; then
  echo "Creating database $DB_NAME"
  psql_admin -c "CREATE DATABASE \"$DB_NAME\""
fi

psql_live() {
  PGPASSWORD="$DB_PASSWORD" PGOPTIONS='--client-min-messages=warning' psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -qtA "$@"
}

# a pulse_live started by an earlier version of this script holds the demo company: start it again clean
if [ -n "$(psql_live -c "SELECT 1 FROM information_schema.tables WHERE table_name = 'users'")" ] \
   && [ "$(psql_live -c "SELECT count(*) FROM users WHERE lower(email) LIKE '%@pulse.demo'")" != "0" ]; then
  echo "$DB_NAME holds the demo accounts (@pulse.demo) from an earlier run. Run scripts/live.sh --reset once." >&2
  exit 1
fi

# the first Admin: asked for while the database has none, and handed to the backend only
admin_exists=""
if [ -n "$(psql_live -c "SELECT 1 FROM information_schema.tables WHERE table_name = 'users'")" ]; then
  admin_exists="$(psql_live -c "SELECT 1 FROM users u JOIN roles r ON r.id = u.role_id WHERE r.name = 'Admin' AND u.status = 'Active' AND u.deleted_at IS NULL LIMIT 1")"
fi

if [ -z "$admin_exists" ] && [ -z "${PULSE_ADMIN_EMAIL:-}" ]; then
  echo "No Admin in $DB_NAME yet: create yours."
  read -r -p "  Your work email: " PULSE_ADMIN_EMAIL
  read -r -p "  Your name: " PULSE_ADMIN_NAME
  while true; do
    read -r -s -p "  Password (8+ characters, not your email or name): " PULSE_ADMIN_PASSWORD
    echo
    if [ "${#PULSE_ADMIN_PASSWORD}" -lt 8 ]; then
      echo "  Too short: use at least 8 characters."
      continue
    fi
    read -r -s -p "  Password again: " again
    echo
    if [ "$PULSE_ADMIN_PASSWORD" != "$again" ]; then
      echo "  The passwords differ: try again."
      continue
    fi
    break
  done
  unset again
  export PULSE_ADMIN_EMAIL PULSE_ADMIN_NAME PULSE_ADMIN_PASSWORD
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
log="$root/backend/target/live-backend.log"

stop() {
  if [ -n "$backend_pid" ] && kill -0 "$backend_pid" 2>/dev/null; then
    kill "$backend_pid" 2>/dev/null || true
    wait "$backend_pid" 2>/dev/null || true
  fi
}

trap stop EXIT INT TERM

echo "Starting the backend on :$API_PORT (database $DB_NAME, log backend/target/live-backend.log)"

# no PULSE_TODAY: the real date, even when the shell has one exported for the demo
env -u PULSE_TODAY -u PULSE_LIVE_ALLOW_LOOPBACK \
  PORT="$API_PORT" \
  PULSE_PROFILE=development \
  PULSE_DEMO_SEED=false \
  PULSE_APP_URL="http://localhost:$WEB_PORT" \
  java -jar "$jar" > "$log" 2>&1 &
backend_pid=$!

# the backend no longer needs the password: drop it from this shell
unset PULSE_ADMIN_PASSWORD

# the first start applies every migration: allow two minutes
for _ in $(seq 1 120); do
  if curl -fs "http://localhost:$API_PORT/health" >/dev/null 2>&1; then
    break
  fi

  if ! kill -0 "$backend_pid" 2>/dev/null; then
    echo "The backend stopped while starting:" >&2
    grep -m 1 "failed to start" "$log" | sed 's/^.* - //' >&2 || tail -n 20 "$log" >&2
    echo "Full log: backend/target/live-backend.log" >&2
    exit 1
  fi

  sleep 1
done

curl -fs "http://localhost:$API_PORT/health" >/dev/null || { echo "The backend did not answer /health in 2 minutes" >&2; exit 1; }

cat <<EOF

Pulse on live Jira: http://localhost:$WEB_PORT   (today is the real date: $(date +%F))
Sign in with your Admin account, then:
  1. Imports → Users: the real team, work emails exactly as in Jira
  2. Integrations → Jira Software → Connect:
     Base URL http://172.16.10.6:8080 · Personal access token · Project keys · Test · Save · Sync now
  3. Integrations → Unmatched accounts: link anyone whose Jira email is hidden or different
Ctrl+C stops both servers.

EOF

cd "$root/frontend"

PULSE_API_TARGET="http://localhost:$API_PORT" npx vite --port "$WEB_PORT" --strictPort
