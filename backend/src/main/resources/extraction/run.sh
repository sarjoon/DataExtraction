#!/usr/bin/env bash
set -euo pipefail
umask 077
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export ROOT
source "$ROOT/extraction.config"
export SQLCL PYTHON EDAG_CONNECT BUSINESS_DATE
case "$MAX_PARALLEL_JOBS" in ''|*[!0-9]*) echo 'Invalid parallel job count' >&2; exit 1;; esac
(( MAX_PARALLEL_JOBS >= 1 && MAX_PARALLEL_JOBS <= 32 )) || exit 1
[[ "$BUSINESS_DATE" =~ ^[0-9]{8}$ ]] || { echo 'Set BUSINESS_DATE=YYYYMMDD before running' >&2; exit 1; }
command -v "$SQLCL" >/dev/null
command -v "$PYTHON" >/dev/null
RUN_DIR=$(mktemp -d "${TMPDIR:-/tmp}/edag.XXXXXXXX")
trap 'rm -rf -- "$RUN_DIR"' EXIT
trap 'for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done; exit 130' INT TERM
mkdir -p -- "$OUTPUT_DIR"
DEST=$(mktemp -d "$OUTPUT_DIR/$(date +%Y%m%d_%H%M%S).XXXXXXXX")
mkdir -p -- "$DEST/logs"
"$PYTHON" "$ROOT/format.py" list "$ROOT/extraction.json" > "$RUN_DIR/entities"
pids=(); failed=0
wait_batch() {
  for pid in "${pids[@]}"; do if ! wait "$pid"; then failed=1; fi; done
  pids=()
}
while IFS= read -r entity; do
  entity=${entity%$'\r'}
  bash "$ROOT/extract.sh" "$entity" "$RUN_DIR" "$DEST" "$TRANSFER_DIR" > "$DEST/logs/$entity.log" 2>&1 &
  pids+=("$!")
  if (( ${#pids[@]} >= MAX_PARALLEL_JOBS )); then wait_batch; fi
done < "$RUN_DIR/entities"
wait_batch
echo "Output and logs: $DEST"
if (( failed )); then echo 'One or more entities failed; inspect the per-entity logs.' >&2; exit 1; fi
echo 'All entities extracted successfully.'
