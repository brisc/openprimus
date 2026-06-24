#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Run the full Ghidra decompilation pipeline in Docker (no local Ghidra needed).
#
# Produces, in ghidra_out/:
#   functions.txt       - all identified functions (addr, name, size)
#   strings_xrefs.txt   - strings with the code addresses that reference them
#   data_xrefs.txt      - non-string data constants with referencing code
#   function_names.txt  - functions named by string cross-referencing
#   decompiled.c        - C decompilation of key functions (edit ADDRS below)
#
# Prereq: run scripts/extract_segments.py first (creates analysis/extracted/).
# Usage:  ./scripts/run_ghidra.sh
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ ! -d analysis/extracted ]; then
  echo "!! analysis/extracted/ not found. Run scripts/extract_segments.py first." >&2
  exit 1
fi

IMAGE="blacktop/ghidra:latest"
GHIDRA_BIN="/ghidra/support/analyzeHeadless"

# Functions to decompile (hex, no 0x, comma-separated). Edit as needed.
ADDRS="4200b9d4,4200891c,420063d8,42016f00,4201cc2c,42009ec8,42006c7c"

echo ">> (1/3) Import: build unified multi-segment program"
docker run --rm -u "$(id -u):$(id -g)" \
  -v "$ROOT/analysis/extracted:/data:ro" \
  -v "$ROOT/ghidra_project:/project" \
  -v "$ROOT/ghidra_scripts:/scripts:ro" \
  -e SEG_DIR=/data -w /tmp \
  --entrypoint sh "$IMAGE" -c "
    printf '\\0' > /tmp/_seed.bin
    $GHIDRA_BIN /project PrimusProject \
      -import /tmp/_seed.bin \
      -processor Xtensa:LE:32:default -loader BinaryLoader -loader-baseAddr 0 \
      -overwrite 2>&1 | grep -iE 'succeeded|error report' | tail -2
    $GHIDRA_BIN /project PrimusProject -process _seed.bin -noanalysis \
      -scriptPath /scripts -postScript ImportPrimus.java 2>&1 \
      | grep -iE 'Added block|error report' | tail -8
  "

echo ">> (2/3) Analyze + dump cross-references"
docker run --rm -u "$(id -u):$(id -g)" \
  -v "$ROOT/ghidra_project:/project" \
  -v "$ROOT/ghidra_scripts:/scripts:ro" \
  -v "$ROOT/ghidra_out:/out" -e OUT_DIR=/out -w /tmp \
  --entrypoint sh "$IMAGE" -c "
    $GHIDRA_BIN /project PrimusProject -process _seed.bin \
      -scriptPath /scripts -postScript DumpPrimus.java 2>&1 \
      | grep -iE 'dumped|complete' | tail -5
  "

echo ">> (3/3) Name functions + decompile key ones"
python3 scripts/map_funcs.py 2>/dev/null || echo "   (map_funcs.py skipped)"
docker run --rm -u "$(id -u):$(id -g)" \
  -v "$ROOT/ghidra_project:/project" \
  -v "$ROOT/ghidra_scripts:/scripts:ro" \
  -v "$ROOT/ghidra_out:/out" -e OUT_DIR=/out -e ADDRS="$ADDRS" -w /tmp \
  --entrypoint sh "$IMAGE" -c "
    $GHIDRA_BIN /project PrimusProject -process _seed.bin -noanalysis \
      -scriptPath /scripts -postScript DecompileFuncs.java 2>&1 \
      | grep -iE 'Decompiled|error report' | tail -8
  "

echo ""
echo ">> Done. Outputs in ghidra_out/:"
ls -la "$ROOT/ghidra_out/"
