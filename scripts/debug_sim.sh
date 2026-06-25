#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Launch the simulator in DEBUG mode, capturing ALL output to a log file.
#
# Use this (instead of run_sim.sh) when the sim crashes — the full crash trace
# (backtrace, signal, last log lines) is saved to /tmp/openprimus_sim.log.
#
# Usage:  ./scripts/debug_sim.sh
#         ...reproduce the crash...
#         Ctrl-C, then: the log is at /tmp/openprimus_sim.log
# ---------------------------------------------------------------------------
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VENV="$ROOT/.venv"
if [ ! -d "$VENV" ]; then
  echo "!! venv not found. Run ./scripts/run_sim.sh first." >&2
  exit 1
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"

LOG=/tmp/openprimus_sim.log
echo ">> Launching simulator in debug mode."
echo ">> ALL output is being captured to: $LOG"
echo ">> Reproduce the crash, then Ctrl-C. Inspect the log for the backtrace."
echo ""

# Run with logs streaming to both the terminal and the log file.
esphome run firmware/sim.yaml 2>&1 | tee "$LOG"
