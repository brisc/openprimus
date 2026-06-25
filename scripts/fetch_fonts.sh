#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Fetch the icon font(s) used by the openPrimus firmware UI.
#
# Material Symbols Rounded is 15MB (full icon set), so it's not committed —
# ESPHome subsets it to only the declared glyphs at compile time. This script
# downloads it if missing.
#
# Called automatically by scripts/run_sim.sh and scripts/test_firmware.sh.
# ---------------------------------------------------------------------------
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FONT_DIR="$ROOT/firmware/fonts"
FONT="$FONT_DIR/material-symbols-rounded.ttf"
URL="https://github.com/google/material-design-icons/raw/master/variablefont/MaterialSymbolsRounded%5BFILL%2CGRAD%2Copsz%2Cwght%5D.ttf"

mkdir -p "$FONT_DIR"
if [ -f "$FONT" ]; then
  echo ">> Icon font present: $FONT"
else
  echo ">> Downloading Material Symbols Rounded (~15MB, one-time)..."
  curl -fsSL -o "$FONT" "$URL"
  echo ">> Saved: $FONT"
fi
