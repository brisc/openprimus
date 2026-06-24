#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Fetch the WPM Primus firmware from the public OTA endpoint.
#
# The firmware is NOT redistributed in this repo (it is proprietary).
# Instead, this script pulls it from the same public, unauthenticated Azure
# endpoint that the device itself uses for OTA updates.
#
# Usage:  ./scripts/fetch_firmware.sh [REGION]
#   REGION defaults to DE. Available: DE, EU, US, HK, KR, RU, FR, SW, 3C, ...
# ---------------------------------------------------------------------------
set -euo pipefail

REGION="${1:-DE}"
ENDPOINT_PRIMARY="https://wpmfirmwareprod.blob.core.windows.net/firmware/"
ENDPOINT_FALLBACK="https://wpm-firmware-updates-g9hfehacbzaphxfj.a01.azurefd.net/firmware/"
LIST_PATH="KD360X/KD360X_FW_lists.txt"

OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/firmware_binary"
mkdir -p "$OUT_DIR"

echo ">> Fetching firmware list for region '$REGION'..."
LIST=$(curl -fsS "${ENDPOINT_PRIMARY}${LIST_PATH}" || curl -fsS "${ENDPOINT_FALLBACK}${LIST_PATH}")

# Parse the list: version,region,date,filename  -> pick the requested region
LINE=$(echo "$LIST" | grep -E ",${REGION}," | sort -t, -k1 -V | tail -1)
if [ -z "$LINE" ]; then
  echo "!! No firmware found for region '$REGION'." >&2
  echo "Available regions:" >&2
  echo "$LIST" | awk -F, '{print "   "$2}' | sort -u >&2
  exit 1
fi

VERSION=$(echo "$LINE" | cut -d, -f1)
FILENAME=$(echo "$LINE" | cut -d, -f4)
DEST="$OUT_DIR/$FILENAME"

echo ">> Latest $REGION firmware: v$VERSION ($FILENAME)"
if [ -f "$DEST" ]; then
  echo ">> Already present: $DEST"
else
  echo ">> Downloading..."
  curl -fsS -o "$DEST" "${ENDPOINT_PRIMARY}KD360X/${FILENAME}" \
     || curl -fsS -o "$DEST" "${ENDPOINT_FALLBACK}KD360X/${FILENAME}"
  echo ">> Saved: $DEST"
fi

echo ""
echo ">> Current firmware list:"
echo "$LIST"
