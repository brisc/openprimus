#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Validate and compile-test the openPrimus firmware config(s).
#
# Catches YAML schema errors, invalid widget options, and C++ compile errors
# WITHOUT launching a window — fast feedback for changes to the config.
#
# Usage:  ./scripts/test_firmware.sh [config]
#   config defaults to firmware/sim.yaml
# Exit 0 = all checks passed; non-zero = failure (details printed).
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
CONFIG="${1:-firmware/sim.yaml}"

VENV="$ROOT/.venv"
if [ ! -d "$VENV" ]; then
  echo "!! venv not found. Run ./scripts/run_sim.sh first (sets up ESPHome)." >&2
  exit 1
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"

PASS=0
FAIL=0
ok()   { echo "  ✓ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ✗ $1" >&2; FAIL=$((FAIL+1)); }

echo "=== Test: $CONFIG ==="
echo ""

# --- 1. Config validation (YAML schema + ESPHome component checks) ---
echo "[1/3] Validating config (esphome config)..."
if esphome config "$CONFIG" >/tmp/primus_config.log 2>&1; then
  ok "config validates"
else
  bad "config validation FAILED:"
  tail -25 /tmp/primus_config.log >&2
  exit 1
fi

# --- 2. C++ compile (catches bad lambdas / widget API misuse) ---
echo "[2/3] Compiling (esphome compile)..."
if esphome compile "$CONFIG" >/tmp/primus_compile.log 2>&1; then
  ok "compiles cleanly"
else
  bad "compile FAILED:"
  tail -30 /tmp/primus_compile.log >&2
  exit 1
fi

# --- 3. Structural checks on the modular packages ---
echo "[3/3] Checking package structure..."
EXPECTED_PKGS=(common home_menu brew_page)
for pkg in "${EXPECTED_PKGS[@]}"; do
  if [ -f "firmware/packages/${pkg}.yaml" ]; then
    ok "package ${pkg}.yaml present"
  else
    bad "missing package firmware/packages/${pkg}.yaml"
  fi
done

# Sanity: sim.yaml must include the packages (not inline them)
if grep -q "!include packages/" "$CONFIG"; then
  ok "$CONFIG uses package includes"
else
  bad "$CONFIG does not include packages/ (modularity lost)"
fi

echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
exit $FAIL
