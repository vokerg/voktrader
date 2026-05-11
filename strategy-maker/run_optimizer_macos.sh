#!/usr/bin/env bash
set -euo pipefail

# run_optimizer_macos.sh
# macOS runner for strategy-maker/voktrader_optimizer.py
#
# Put this file next to:
#   strategy-maker/voktrader_optimizer.py
#
# Run:
#   cd /path/to/voktrader
#   ./strategy-maker/run_optimizer_macos.sh --model qwen3.6:27b --iters 20
#
# Important:
#   The current optimizer's automatic Spring Boot starter is Windows-specific.
#   This macOS runner defaults to --server-mode manual, which is correct but pauses
#   before every backtest so you can restart Spring Boot yourself.
#
# Later, when the optimizer gets native macOS auto-start support, change:
#   SERVER_MODE=manual
# to:
#   SERVER_MODE=auto

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPTIMIZER="$SCRIPT_DIR/voktrader_optimizer.py"
REPO_ROOT_DEFAULT="$(cd "$SCRIPT_DIR/.." && pwd)"

MODEL="${MODEL:-qwen3.6:27b}"
ITERS="${MAX_ITERS:-20}"
MIN_TRADES="${MIN_TRADES:-5}"
SERVER_MODE="${SERVER_MODE:-manual}"
PORT="${APP_PORT:-8080}"
NUM_CTX="${NUM_CTX:-16384}"
TEMPERATURE="${TEMPERATURE:-0.2}"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"
REPO_ROOT="${REPO:-$REPO_ROOT_DEFAULT}"

EXTRA_ARGS=()

usage() {
  cat <<EOF
Usage:
  ./strategy-maker/run_optimizer_macos.sh [options]

Options:
  --model MODEL             Default: $MODEL
  --iters N                 Default: $ITERS
  --min-trades N            Default: $MIN_TRADES
  --server-mode MODE        manual|external. Default: $SERVER_MODE
  --port N                  Default: $PORT
  --num-ctx N               Default: $NUM_CTX
  --temperature X           Default: $TEMPERATURE
  --ollama-url URL          Default: $OLLAMA_URL
  --repo PATH               Default: $REPO_ROOT
  --                         Pass remaining args directly to optimizer.py

Examples:
  ./strategy-maker/run_optimizer_macos.sh --model qwen3.6:27b --iters 20
  SERVER_MODE=manual MODEL=qwen3.6:27b ./strategy-maker/run_optimizer_macos.sh
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --iters) ITERS="$2"; shift 2 ;;
    --min-trades) MIN_TRADES="$2"; shift 2 ;;
    --server-mode) SERVER_MODE="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --num-ctx) NUM_CTX="$2"; shift 2 ;;
    --temperature) TEMPERATURE="$2"; shift 2 ;;
    --ollama-url) OLLAMA_URL="$2"; shift 2 ;;
    --repo) REPO_ROOT="$(cd "$2" && pwd)"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    --) shift; EXTRA_ARGS+=("$@"); break ;;
    *) EXTRA_ARGS+=("$1"); shift ;;
  esac
done

if [[ ! -f "$OPTIMIZER" ]]; then
  echo "Cannot find optimizer: $OPTIMIZER" >&2
  echo "Put this runner next to voktrader_optimizer.py." >&2
  exit 1
fi

if [[ ! -f "$REPO_ROOT/src/main/resources/strategy-v2.paper.yml" ]]; then
  echo "Repo path does not look like voktrader: $REPO_ROOT" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "Missing python3. Install Python 3 first." >&2
  exit 1
fi

if ! curl -fsS "$OLLAMA_URL" >/dev/null 2>&1; then
  echo "Ollama is not reachable at $OLLAMA_URL." >&2
  echo "Start it first, for example:" >&2
  echo "  ollama serve" >&2
  exit 1
fi

echo
echo "Voktrader optimizer macOS runner"
echo "Repo:        $REPO_ROOT"
echo "Optimizer:   $OPTIMIZER"
echo "Model:       $MODEL"
echo "Iters:       $ITERS"
echo "Server mode: $SERVER_MODE"
echo "Ollama URL:  $OLLAMA_URL"
echo

if [[ "$SERVER_MODE" == "manual" ]]; then
  cat <<'EOF'
Manual server mode:
  The optimizer will pause before each backtest.
  In another terminal, restart Spring Boot when prompted:

    cd /path/to/voktrader
    ./mvnw spring-boot:run

This is intentional for now because the current optimizer's auto-start path is Windows-specific.
EOF
  echo
fi

cd "$REPO_ROOT"

python3 "$OPTIMIZER" \
  --repo "$REPO_ROOT" \
  --model "$MODEL" \
  --iters "$ITERS" \
  --min-trades "$MIN_TRADES" \
  --port "$PORT" \
  --server-mode "$SERVER_MODE" \
  --ollama-url "$OLLAMA_URL" \
  --num-ctx "$NUM_CTX" \
  --temperature "$TEMPERATURE" \
  "${EXTRA_ARGS[@]}"
