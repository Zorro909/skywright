#!/usr/bin/env bash
# PROTOTYPE — THROWAWAY. Spike for issue #18 (map #1). Does not merge.
#
# One command to run the spike. Needs: a JDK 25 (single-file source launch, no build tool)
# and a reachable SkyPilot API server.
#
#   ./run.sh rest    # the preferred arm: raw HTTP against the API server
#   ./run.sh cli     # the comparison arm: `sky` as a subprocess
#
# SKY_API defaults to the local server SkyPilot starts on its own.
# SKY_BIN  must point at a `sky` on PATH for the cli arm.

set -uo pipefail
cd "$(dirname "$0")"

export SKY_API="${SKY_API:-http://127.0.0.1:46580}"
export SKY_BIN="${SKY_BIN:-sky}"

case "${1:-rest}" in
  rest) exec java SkyRest.java ;;
  cli)  exec java SkyCli.java ;;
  *)    echo "usage: $0 [rest|cli]" >&2; exit 2 ;;
esac
