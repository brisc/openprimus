#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Launch the openPrimus UI simulator (SDL2 window on your PC, no ESP32 needed).
#
# Usage:  ./scripts/run_sim.sh
#
# What it does:
#   1. Checks SDL2 is installed (prints install hint if not)
#   2. Creates a Python venv and installs ESPHome (if not already done)
#   3. Creates firmware/secrets.yaml from the example (if missing)
#   4. Runs `esphome run firmware/sim.yaml` → a window opens
#
# First run takes a couple of minutes (venv + ESPHome install + compile).
# Later runs are fast (incremental compile, ~10s).
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# --- 1. SDL2 check ---
if ! command -v sdl2-config >/dev/null 2>&1; then
  echo "!! SDL2 is not installed. The simulator needs it to open a window." >&2
  echo "   Install it, then re-run this script:" >&2
  echo "     Linux:   sudo apt install libsdl2-dev" >&2
  echo "     macOS:   brew install sdl2" >&2
  echo "     Windows: use WSL2, then: sudo apt install libsdl2-dev" >&2
  exit 1
fi

# --- 2. venv + ESPHome ---
VENV="$ROOT/.venv"
if [ ! -d "$VENV" ]; then
  echo ">> Creating Python venv (.venv)..."
  python3 -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"

if ! python3 -c "import esphome" >/dev/null 2>&1; then
  echo ">> Installing ESPHome into the venv (one-time, ~1 min)..."
  pip install --quiet --upgrade pip
  pip install --quiet -r firmware/requirements.txt
fi

# --- 3. secrets ---
if [ ! -f firmware/secrets.yaml ]; then
  echo ">> Creating firmware/secrets.yaml from example (placeholder WiFi)..."
  cp firmware/secrets.yaml.example firmware/secrets.yaml
fi

# --- 4. launch ---
echo ">> Launching simulator... a window will open on your screen."
echo "   (Click 'Brew' to simulate an extraction; Ctrl-C in this terminal to stop.)"
echo ""
esphome run firmware/sim.yaml
