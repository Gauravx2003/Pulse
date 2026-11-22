#!/usr/bin/env bash
# Starts the backend for the e2e run: its own port and database (created on first use), demo seed on
# and "today" pinned to 28 Sep 2026 so Sprint 43 is active and Sprint 42 is closed, as in the prototype.
# Usage: start-backend.sh <port> <app origin>
set -euo pipefail

PORT="${1:-8089}"
APP_URL="${2:-http://localhost:5174}"

export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_USER="${DB_USER:-pulse}"
export DB_PASSWORD="${DB_PASSWORD:-pulse_secret_dev}"
export DB_NAME="${PULSE_E2E_DB:-pulse_e2e}"

if ! PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -tAc \
    "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" | grep -q 1; then
  PGPASSWORD="$DB_PASSWORD" createdb -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" "$DB_NAME"
fi

JAR="$(dirname "$0")/../../backend/target/pulse-backend-1.0.0-SNAPSHOT-fat.jar"

if [ ! -f "$JAR" ]; then
  echo "Backend jar not found: run 'mvn -f backend package -DskipTests' first" >&2
  exit 1
fi

export PORT
export PULSE_PROFILE=development
export PULSE_DEMO_SEED=true
export PULSE_TODAY=2026-09-28
export PULSE_APP_URL="$APP_URL"
# Each test context sends its own X-Real-IP (e2e/helpers.js), so the per-IP sign-in throttle does
# not trip across repeated runs; the Vite proxy forwards the header.
export PULSE_TRUST_PROXY=true

exec java -jar "$JAR"
