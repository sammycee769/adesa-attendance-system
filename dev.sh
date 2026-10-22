#!/usr/bin/env bash
#
# DEVELOPMENT ONLY - starts and stops the local ADESA HQ Attendance stack.
#
#   ./dev.sh up             start PostgreSQL, the backend and the frontend
#   ./dev.sh down           stop the backend and the frontend
#   ./dev.sh status         show what is running
#   ./dev.sh approve-device <device-id>   approve a pending device (admin action)
#   ./dev.sh pending-devices               list devices awaiting approval
#
# This script is for the developer's own machine. It never runs against a
# deployment and it must never be used to weaken the production configuration:
# the office-network restriction stays OFF here and the 'prod' profile still
# refuses to start without it.
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$PROJECT_DIR/backend"
FRONTEND_DIR="$PROJECT_DIR/frontend"
LOG_DIR="${TMPDIR:-/tmp}/adesa-dev-logs"
ENV_FILE="$PROJECT_DIR/.env"
BACKEND_PID_FILE="$LOG_DIR/backend.pid"
FRONTEND_PID_FILE="$LOG_DIR/frontend.pid"

mkdir -p "$LOG_DIR"
chmod 700 "$LOG_DIR" 2>/dev/null || true

# The Docker daemon on this machine is not the default socket.
export DOCKER_HOST="${DOCKER_HOST:-unix:///var/run/docker.sock}"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

# Reads KEY=VALUE lines without executing the file as shell code. This avoids
# `source` (which would run arbitrary commands) and exports only real
# assignments.
load_env_file() {
  local file="$1" line key value
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    value="${value%\"}"; value="${value#\"}"
    value="${value%\'}"; value="${value#\'}"
    printf -v "$key" '%s' "$value"
    export "$key"
  done < "$file"
}

if [[ -f "$ENV_FILE" ]]; then
  load_env_file "$ENV_FILE"
  # The file holds development passwords: keep it private to the developer.
  chmod 600 "$ENV_FILE" 2>/dev/null || true
fi

: "${DB_URL:=jdbc:postgresql://localhost:5433/adesa_attendance}"
: "${DB_USERNAME:=adesa_app}"
: "${DB_PASSWORD:=adesa_dev_password}"
: "${ATTENDANCE_TIMEZONE:=Africa/Lagos}"
: "${ATTENDANCE_ALLOWED_IPV4:=105.127.6.174/32}"
: "${ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED:=false}"
: "${ATTENDANCE_NETWORK_LOCAL_DEVELOPMENT_BYPASS:=true}"
: "${WEBAUTHN_RP_ID:=localhost}"
: "${WEBAUTHN_ALLOWED_ORIGINS:=http://localhost:5173}"
: "${SESSION_COOKIE_SECURE:=false}"
: "${DEV_ADMIN_EMAIL:=admin@adesa.test}"
# No password default: a committed credential is worse than a clear error.
: "${DEV_ADMIN_PASSWORD:?set DEV_ADMIN_PASSWORD in .env (never commit a real password)}"

# This script runs Flyway against DB_URL. Refuse anything that is not a local
# database so a checkout cannot migrate a shared or production instance.
[[ "$DB_URL" =~ ^jdbc:postgresql://(localhost|127\.0\.0\.1)(:[0-9]+)?/ ]] \
  || die "refusing to start: DB_URL must point at localhost/127.0.0.1 (got: $DB_URL)"

BACKEND_URL="http://localhost:${SERVER_PORT:-8080}"
FRONTEND_URL="http://localhost:${FRONTEND_PORT:-5173}"

start_postgres() {
  log "starting PostgreSQL (docker compose)"
  (cd "$PROJECT_DIR" && docker compose up -d postgres)
}

start_backend() {
  if curl -fsS -o /dev/null "$BACKEND_URL/actuator/health" 2>/dev/null; then
    log "backend already running on $BACKEND_URL"
    return
  fi
  log "starting the backend on $BACKEND_URL (log: $LOG_DIR/backend.log)"
  (
    cd "$BACKEND_DIR" || exit 1
    exec nohup env \
      SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}" \
      DB_URL="$DB_URL" DB_USERNAME="$DB_USERNAME" DB_PASSWORD="$DB_PASSWORD" \
      ATTENDANCE_TIMEZONE="$ATTENDANCE_TIMEZONE" \
      ATTENDANCE_ALLOWED_IPV4="$ATTENDANCE_ALLOWED_IPV4" \
      ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED="$ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED" \
      ATTENDANCE_NETWORK_LOCAL_DEVELOPMENT_BYPASS="$ATTENDANCE_NETWORK_LOCAL_DEVELOPMENT_BYPASS" \
      WEBAUTHN_RP_ID="$WEBAUTHN_RP_ID" WEBAUTHN_ALLOWED_ORIGINS="$WEBAUTHN_ALLOWED_ORIGINS" \
      SESSION_COOKIE_SECURE="$SESSION_COOKIE_SECURE" \
      SECURITY_LOG_FILE="$PROJECT_DIR/logs/security.log" \
      mvn -o -B -ntp spring-boot:run > "$LOG_DIR/backend.log" 2>&1
  ) &
  echo "$!" > "$BACKEND_PID_FILE"
  log "waiting for the backend to become healthy (max 90s)"
  for _ in $(seq 1 90); do
    if curl -fsS -o /dev/null "$BACKEND_URL/actuator/health" 2>/dev/null; then
      log "backend is up"
      # The security log carries client addresses: keep it out of other users'
      # reach on a shared developer machine.
      [[ -f "$PROJECT_DIR/logs/security.log" ]] && chmod 640 "$PROJECT_DIR/logs/security.log" 2>/dev/null || true
      return
    fi
    sleep 1
  done
  die "backend did not become healthy - see $LOG_DIR/backend.log"
}

start_frontend() {
  if curl -fsS -o /dev/null "$FRONTEND_URL" 2>/dev/null; then
    log "frontend already running on $FRONTEND_URL"
    return
  fi
  log "starting the frontend on $FRONTEND_URL (log: $LOG_DIR/frontend.log)"
  (
    cd "$FRONTEND_DIR" || exit 1
    exec nohup env BACKEND_URL="$BACKEND_URL" \
      npm run dev > "$LOG_DIR/frontend.log" 2>&1
  ) &
  echo "$!" > "$FRONTEND_PID_FILE"
  log "waiting for the frontend (max 60s)"
  for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null "$FRONTEND_URL" 2>/dev/null; then
      log "frontend is up"
      return
    fi
    sleep 1
  done
  die "frontend did not start - see $LOG_DIR/frontend.log"
}

stop_recorded_process() {
  local pid_file="$1"
  [[ -f "$pid_file" ]] || return 0
  local pid
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    # Stop the recorded process and its direct children first…
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
  fi
  rm -f "$pid_file"
}

stop_all() {
  log "stopping the backend and the frontend"
  stop_recorded_process "$BACKEND_PID_FILE"
  stop_recorded_process "$FRONTEND_PID_FILE"
  # The Spring Boot Maven plugin forks the application JVM. Stop only processes
  # whose command line points at THIS project's classes or tools directory, so
  # an unrelated (or production) instance is never killed.
  pkill -f "$BACKEND_DIR/target/classes" 2>/dev/null || true
  pkill -f "$FRONTEND_DIR/node_modules/.bin/vite" 2>/dev/null || true
  log "done (PostgreSQL is left running; use 'docker compose stop postgres' to stop it)"
}

status() {
  printf 'PostgreSQL : %s\n' "$(docker compose -f "$PROJECT_DIR/docker-compose.yml" ps --format '{{.Status}}' 2>/dev/null | head -1 || echo 'not running')"
  printf 'Backend    : %s\n' "$(curl -fsS -o /dev/null -w '%{http_code}' "$BACKEND_URL/actuator/health" 2>/dev/null || echo 'down')"
  printf 'Frontend   : %s\n' "$(curl -fsS -o /dev/null -w '%{http_code}' "$FRONTEND_URL" 2>/dev/null || echo 'down')"
}

# Signs in as the dev administrator and echoes the session cookie jar path.
admin_session() {
  local jar
  jar="$(mktemp)"
  ADMIN_JAR="$jar"
  # A live admin session cookie must never be left behind on a failure path.
  trap 'rm -f "${ADMIN_JAR:-}"' EXIT
  local csrf
  csrf="$(curl -fsS -c "$jar" "$BACKEND_URL/api/v1/auth/csrf" >/dev/null; awk '/XSRF-TOKEN/ {print $7}' "$jar")"
  local code
  code="$(curl -sS -b "$jar" -c "$jar" -o /dev/null -w '%{http_code}' \
    -H "X-XSRF-TOKEN: $csrf" -H 'Content-Type: application/json' \
    -X POST "$BACKEND_URL/api/v1/auth/login" \
    -d "{\"email\":\"$DEV_ADMIN_EMAIL\",\"password\":\"$DEV_ADMIN_PASSWORD\"}")"
  [[ "$code" == "200" ]] || die "admin login failed (HTTP $code)"
  ADMIN_CSRF="$(awk '/XSRF-TOKEN/ {print $7}' "$jar")"
}

pending_devices() {
  admin_session
  curl -fsS -b "$ADMIN_JAR" "$BACKEND_URL/api/v1/admin/devices?status=PENDING_APPROVAL"
  echo
  rm -f "$ADMIN_JAR"
}

approve_device() {
  local device_id="${1:-}"
  [[ -n "$device_id" ]] || die "usage: $0 approve-device <device-id>"
  [[ "$device_id" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
    || die "device id must be a UUID"
  admin_session
  curl -fsS -b "$ADMIN_JAR" -H "X-XSRF-TOKEN: $ADMIN_CSRF" \
    -X POST "$BACKEND_URL/api/v1/admin/devices/$device_id/approve"
  echo
  rm -f "$ADMIN_JAR"
}

case "${1:-up}" in
  up)
    start_postgres
    start_backend
    start_frontend
    echo
    log "stack is running"
    printf '  frontend : %s\n  backend  : %s\n  health   : %s/actuator/health\n' \
      "$FRONTEND_URL" "$BACKEND_URL" "$BACKEND_URL"
    printf '  admin    : %s (password: DEV_ADMIN_PASSWORD in .env)\n' "$DEV_ADMIN_EMAIL"
    ;;
  down)      stop_all ;;
  status)    status ;;
  approve-device) approve_device "${2:-}" ;;
  pending-devices) pending_devices ;;
  *)         die "usage: $0 {up|down|status|approve-device <id>|pending-devices}" ;;
esac
