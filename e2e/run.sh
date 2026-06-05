#!/usr/bin/env bash
# Run the browser storyboards against a freshly-seeded dev server.
#
# Boots `clj -M:dev` on :3000 with a clean data store (so the demo forms — incl.
# project-report — reseed from forms/), runs the requested storyboards with a real
# headless Chromium, then tears the server down and restores the pre-run data/.
# The restore runs even on failure or Ctrl-C, so your dev data is never left wiped.
#
# Usage:
#   e2e/run.sh                      # all storyboards (storyboard, document-flow, access)
#   e2e/run.sh document-flow        # just one (by basename, no .mjs)
#   e2e/run.sh document-flow access # a subset
#   PORT=3001 e2e/run.sh            # alternate port
set -uo pipefail

cd "$(dirname "$0")/.."                       # repo root
PORT="${PORT:-3000}"
BASE="http://localhost:${PORT}"
DATA_FILES=(apps.db documents.db users.edn access.edn audit.edn form-versions.edn)

SCRIPTS=("$@")
[ ${#SCRIPTS[@]} -eq 0 ] && SCRIPTS=(storyboard document-flow access)

BAK="$(mktemp -d)/data"
SRV=""

cleanup() {
  [ -n "$SRV" ] && kill "$SRV" 2>/dev/null
  for _ in $(seq 1 20); do lsof -ti ":$PORT" >/dev/null 2>&1 || break; sleep 0.5; done
  lsof -ti ":$PORT" 2>/dev/null | xargs kill -9 2>/dev/null
  if [ -d "$BAK" ]; then rm -rf data && mv "$BAK" data && echo "↺ restored data/"; fi
}
trap 'exit 130' INT TERM
trap cleanup EXIT

# free the port, back up the data store, and reset the seeded files
lsof -ti ":$PORT" 2>/dev/null | xargs kill -9 2>/dev/null
[ -d data ] && cp -a data "$BAK"
for f in "${DATA_FILES[@]}"; do rm -f "data/$f"; done

echo "▶ booting dev server on :$PORT …"
clj -M:dev -e "(go)" -e "@(promise)" >/tmp/stepvine-e2e-dev.log 2>&1 &
SRV=$!
ready=""
for _ in $(seq 1 90); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/login" 2>/dev/null)" = "200" ]; then ready=1; break; fi
  sleep 1
done
[ -z "$ready" ] && { echo "✗ server did not come up — see /tmp/stepvine-e2e-dev.log"; exit 1; }
sleep 4   # let forms/options seed + the dev admin land

rc=0
for s in "${SCRIPTS[@]}"; do
  echo ""; echo "═══════════ ${s}.mjs ═══════════"
  if [ ! -f "e2e/${s}.mjs" ]; then echo "✗ no such storyboard: e2e/${s}.mjs"; rc=1; continue; fi
  BASE="$BASE" node "e2e/${s}.mjs" || rc=1
done

echo ""
[ "$rc" = 0 ] && echo "══ ✓ all storyboards passed ══" || echo "══ ✗ some storyboards failed ══"
exit "$rc"
