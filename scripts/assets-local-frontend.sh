#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../frontend"
exec env -i PATH="$PATH" HOME="$HOME" LANG=C.UTF-8 \
  VITE_API_BASE_URL= BACKEND_URL=http://127.0.0.1:8081 \
  npm run dev -- --host 127.0.0.1 --port 5174 --strictPort
