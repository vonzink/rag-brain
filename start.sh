#!/usr/bin/env bash
# rag-brain — one command: database + backend API + dashboard, then open the browser.
#
#   ./start.sh
#
# Idempotent: skips anything already running. Coexists with msfg-rag (which uses
# :8090 for its API and :5173 for its dashboard) — rag-brain uses :8091 (API),
# :5174 (dashboard), and its own Postgres on :5435 (container rag-brain-postgres).
# Stop everything later:  lsof -ti:8091 -ti:5174 | xargs kill
set -uo pipefail
cd "$(dirname "$0")"

API_PORT=8091
DASH_PORT=5174

# 1. Database (Postgres :5435)
docker compose up -d >/dev/null 2>&1 && echo "✓ database up (rag-brain-postgres :5435)" \
  || { echo "✗ could not start the database — is Docker Desktop running?"; exit 1; }

# 2. Backend API (:8091)
if curl -sf -m2 "http://localhost:${API_PORT}/actuator/health" >/dev/null 2>&1; then
  echo "✓ API already running on :${API_PORT}"
else
  echo "→ starting API on :${API_PORT} (first boot ~15s)…"
  ( set -a; [ -f .env ] && source .env; set +a
    nohup ./gradlew bootRun --args="--server.port=${API_PORT}" >/tmp/rag-brain-api.log 2>&1 & )
  printf "→ waiting for API"
  ok=""
  for _ in $(seq 1 60); do
    curl -sf -m2 "http://localhost:${API_PORT}/actuator/health" >/dev/null 2>&1 && { ok=1; break; }
    printf "."; sleep 2
  done
  echo
  if [ -z "$ok" ]; then
    echo "✗ API failed to start — last log lines:"; tail -n 15 /tmp/rag-brain-api.log; exit 1
  fi
  echo "✓ API healthy on :${API_PORT}"
fi

# 3. Dashboard (Vite :5174 → proxies /api to :8091)
if lsof -ti:"${DASH_PORT}" >/dev/null 2>&1; then
  echo "✓ dashboard already running on :${DASH_PORT}"
else
  echo "→ starting dashboard on :${DASH_PORT}…"
  ( cd dashboard
    [ -d node_modules ] || npm install
    nohup npm run dev -- --port "${DASH_PORT}" >/tmp/rag-brain-dash.log 2>&1 & )
  sleep 4
fi

# 4. Open + admin-key hint
open "http://localhost:${DASH_PORT}" 2>/dev/null || true
echo
echo "✓ rag-brain open at http://localhost:${DASH_PORT}"
key="$(grep -E '^ADMIN_API_KEY=' .env 2>/dev/null | cut -d= -f2-)"
echo "  unlock with the admin key:  ${key:-（set ADMIN_API_KEY in .env）}"
echo "  stop everything:            lsof -ti:${API_PORT} -ti:${DASH_PORT} | xargs kill"
