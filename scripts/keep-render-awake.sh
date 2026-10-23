#!/usr/bin/env bash
# Run on an external machine that stays online, not inside the Render service.
set -u

health_url='https://adesa-attendance-system.onrender.com/actuator/health'

case "${1:-}" in
  '') mode=loop ;;
  --once) mode=once ;;
  *) printf 'Usage: bash %s [--once]\n' "$0" >&2; exit 2 ;;
esac

trap 'exit 0' INT TERM

check_health() {
  # Allow for a slow cold start; retain failures in the log and exit status.
  if curl --proto '=https' --fail --silent --show-error \
      --connect-timeout 10 --max-time 240 \
      --retry 2 --retry-delay 10 --retry-max-time 300 \
      --output /dev/null "$health_url"; then
    printf '%s ADESA backend health check passed\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
  else
    printf '%s ADESA backend health check failed\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" >&2
    return 1
  fi
}

if [[ "$mode" == once ]]; then
  check_health
  exit $?
fi

while true; do
  check_health || true
  sleep 600 &
  wait "$!" || exit 0
done
