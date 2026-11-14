#!/usr/bin/env bash
# Installed on the M5 MacBook as ~/Projects/local_llm/podcast-bench/remote.sh: the only command the
# Ubuntu laptop's SSH key may run there (a forced command in ~/.ssh/authorized_keys, from the
# laptop's addresses only, no forwarding, no terminal). tools/podcasts/m5.py calls it.
#   ping          -> "ok". Connecting is what wakes this Mac from sleep.
#   run <model>   -> stdin: an OpenAI chat-completions request; stdout: the response.
#                    Loads the model if needed (mlx_lm.server on 127.0.0.1:8089) and keeps the Mac
#                    awake (caffeinate -i -s: idle and, on AC, system sleep, which a dark wake from the
#                    network needs) only while it stays loaded; it unloads after 10 idle minutes.
#   transcribe    -> stdin: audio (MP3); stdout: whisper.py's JSON (MLX Whisper large-v3-turbo).
#   stop          -> unload now.
# Models: Qwen3.6-35B-A3B-MLX-8bit (fast) and Qwen3.8-27B-MLX-8bit (better), in ~/Projects/local_llm/models.
set -euo pipefail
export PATH="/opt/homebrew/bin:$PATH"     # ffmpeg (Whisper decodes with it); SSH sessions don't load the shell profile
ROOT="$HOME/Projects/local_llm"
DIR="$ROOT/podcast-bench"
PORT=8089
URL="http://127.0.0.1:$PORT/v1"
IDLE=600
# shellcheck disable=SC2086
set -- ${SSH_ORIGINAL_COMMAND:-$*}
log() { echo "$(date '+%F %T') $*" >> "$DIR/remote.log"; }
stop() { pkill -f "mlx_lm.server --model $ROOT/models/" 2>/dev/null || true; }

case "${1:-}" in
  ping) echo ok ;;
  stop) stop; log stop; echo stopped ;;
  transcribe)
    tmp=$(mktemp -t podcast).mp3
    trap 'rm -f "$tmp"' EXIT
    cat > "$tmp"
    t0=$(date +%s)
    caffeinate -i -s "$ROOT/.venv/bin/python" "$DIR/whisper.py" "$tmp"
    log "transcribe $(( $(stat -f %z "$tmp") / 1000000 ))MB $(( $(date +%s) - t0 ))s"
    ;;
  run)
    M="${2:-}"
    case "$M" in Qwen3.6-35B-A3B-MLX-8bit|Qwen3.8-27B-MLX-8bit) ;; *) echo "unknown model" >&2; exit 2 ;; esac
    MP="$ROOT/models/$M"
    running=$(curl -sf "$URL/models" 2>/dev/null || true)
    if [[ -n "$running" && "$running" != *"$MP"* ]]; then stop; sleep 2; running=""; fi
    touch "$DIR/last-used"
    if [[ -z "$running" ]]; then
      t0=$(date +%s)
      nohup caffeinate -i -s "$ROOT/.venv/bin/mlx_lm.server" --model "$MP" --host 127.0.0.1 --port $PORT \
        > "$DIR/server.log" 2>&1 < /dev/null &
      until curl -sf "$URL/models" >/dev/null; do
        sleep 1
        if (( $(date +%s) - t0 > 180 )); then echo "server did not start" >&2; exit 3; fi
      done
      log "started $M in $(( $(date +%s) - t0 ))s"
      env -u SSH_ORIGINAL_COMMAND nohup "$0" idle-watch > /dev/null 2>&1 < /dev/null &
    fi
    t0=$(date +%s)
    # The request names the model; the server wants its folder (a bare name means a Hugging Face download).
    "$ROOT/.venv/bin/python" -c 'import json, sys; b = json.load(sys.stdin); b["model"] = sys.argv[1]; print(json.dumps(b))' "$MP" \
      | curl -sf -X POST "$URL/chat/completions" -H "Content-Type: application/json" --data-binary @- --max-time 1800
    touch "$DIR/last-used"
    log "run $M $(( $(date +%s) - t0 ))s"
    ;;
  idle-watch)
    # Started by "run" after loading: unload once nothing has asked for $IDLE seconds.
    while sleep 30; do
      curl -sf "$URL/models" >/dev/null || exit 0
      if (( $(date +%s) - $(stat -f %m "$DIR/last-used") > IDLE )); then stop; log "idle unload"; exit 0; fi
    done
    ;;
  *) echo "usage: ping | run <model> | transcribe | stop" >&2; exit 2 ;;
esac
