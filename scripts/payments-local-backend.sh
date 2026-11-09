#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../backend"
# No project .env files, cloud credentials, remote DB URLs or mail settings.
exec env -i PATH="$PATH" HOME="$HOME" LANG=C.UTF-8 \
 java -jar target/adesa-attendance-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=payments-local
