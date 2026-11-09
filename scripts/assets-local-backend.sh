#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../backend"
# Do not source the project's deployment .env files. Keep a minimal environment.
exec env -i PATH="$PATH" HOME="$HOME" LANG=C.UTF-8 \
  java -jar target/adesa-attendance-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=assets-local
