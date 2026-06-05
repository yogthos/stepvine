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
[ ${#SCRIPTS[@]} -eq 0 ] && SCRIPTS=(storyboard document-flow access auth-access)

BAK="$(mktemp -d)/data"
SRV=""

stop_server() {
  [ -n "$SRV" ] && kill "$SRV" 2>/dev/null
  for _ in $(seq 1 20); do lsof -ti ":$PORT" >/dev/null 2>&1 || break; sleep 0.3; done
  lsof -ti ":$PORT" 2>/dev/null | xargs kill -9 2>/dev/null
  SRV=""
}

cleanup() {
  stop_server
  if [ -d "$BAK" ]; then rm -rf data && mv "$BAK" data && echo "↺ restored data/"; fi
}
trap 'exit 130' INT TERM
trap cleanup EXIT

# (re)boot the dev server on a freshly-seeded store. Each storyboard gets its own
# clean server so they can't interfere (a storyboard may restrict a form's access,
# create users, etc.) — order-independent, repeatable.
boot() {
  stop_server
  for f in "${DATA_FILES[@]}"; do rm -f "data/$f"; done
  clj -M:dev -e "(go)" -e "@(promise)" >/tmp/stepvine-e2e-dev.log 2>&1 &
  SRV=$!
  for _ in $(seq 1 90); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/login" 2>/dev/null)" = "200" ] && { sleep 4; return 0; }
    sleep 1
  done
  echo "✗ server did not come up — see /tmp/stepvine-e2e-dev.log"; return 1
}

# back up the data store once; each boot resets only the seeded files
lsof -ti ":$PORT" 2>/dev/null | xargs kill -9 2>/dev/null
[ -d data ] && cp -a data "$BAK"

rc=0
for s in "${SCRIPTS[@]}"; do
  echo ""; echo "═══════════ ${s}.mjs ═══════════"
  if [ ! -f "e2e/${s}.mjs" ]; then echo "✗ no such storyboard: e2e/${s}.mjs"; rc=1; continue; fi
  boot || { rc=1; continue; }
  BASE="$BASE" node "e2e/${s}.mjs" || rc=1
done
stop_server

echo ""
[ "$rc" = 0 ] && echo "══ ✓ all storyboards passed ══" || echo "══ ✗ some storyboards failed ══"
exit "$rc"
