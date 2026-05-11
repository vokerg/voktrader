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
PROFILE="${PROFILE:-strategy-v2-paper}"
ITERS="${MAX_ITERS:-20}"
MIN_TRADES="${MIN_TRADES:-5}"
SERVER_MODE="${SERVER_MODE:-manual}"
PORT="${APP_PORT:-8080}"
NUM_CTX="${NUM_CTX:-4096}"
NUM_PREDICT="${NUM_PREDICT:-160}"
TEMPERATURE="${TEMPERATURE:-0.0}"
TOP_K="${TOP_K:-20}"
TOP_P="${TOP_P:-0.8}"
REPEAT_PENALTY="${REPEAT_PENALTY:-1.0}"
SEED="${SEED:-42}"
OLLAMA_FORMAT="${OLLAMA_FORMAT:-simple_patch}"
OLLAMA_KEEP_ALIVE="${OLLAMA_KEEP_ALIVE:-30m}"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"
REPO_ROOT="${REPO:-$REPO_ROOT_DEFAULT}"
STRATEGY_FILE="${STRATEGY_FILE:-}"
MARKET_IDS="${MARKET_IDS:-2218797 2218853 2218896 2218965 2218999 2219045 2219080 2219122 2219153 2219257}"

EXTRA_ARGS=()

usage() {
  cat <<EOF
Usage:
  ./strategy-maker/run_optimizer_macos.sh [options]

Options:
  --profile NAME            Default: $PROFILE
  --model MODEL             Default: $MODEL
  --iters N                 Default: $ITERS
  --min-trades N            Default: $MIN_TRADES
  --server-mode MODE        manual|external. Default: $SERVER_MODE
  --port N                  Default: $PORT
  --num-ctx N               Default: $NUM_CTX
  --num-predict N           Default: $NUM_PREDICT
  --temperature X           Default: $TEMPERATURE
  --top-k N                 Default: $TOP_K
  --top-p X                 Default: $TOP_P
  --repeat-penalty X        Default: $REPEAT_PENALTY
  --seed N                  Default: $SEED
  --ollama-url URL          Default: $OLLAMA_URL
  --strategy-file PATH      Optional YAML override. Default comes from profile
  --market-ids "IDS"        Space/comma-separated IDs. Default comes from profile
  --repo PATH               Default: $REPO_ROOT
  --                         Pass remaining args directly to optimizer.py

Examples:
  ./strategy-maker/run_optimizer_macos.sh --model qwen3.6:27b --iters 20
  SERVER_MODE=manual MODEL=qwen3.6:27b ./strategy-maker/run_optimizer_macos.sh
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --model) MODEL="$2"; shift 2 ;;
    --iters) ITERS="$2"; shift 2 ;;
    --min-trades) MIN_TRADES="$2"; shift 2 ;;
    --server-mode) SERVER_MODE="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --num-ctx) NUM_CTX="$2"; shift 2 ;;
    --num-predict) NUM_PREDICT="$2"; shift 2 ;;
    --temperature) TEMPERATURE="$2"; shift 2 ;;
    --top-k) TOP_K="$2"; shift 2 ;;
    --top-p) TOP_P="$2"; shift 2 ;;
    --repeat-penalty) REPEAT_PENALTY="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    --ollama-url) OLLAMA_URL="$2"; shift 2 ;;
    --strategy-file) STRATEGY_FILE="$2"; shift 2 ;;
    --market-ids) MARKET_IDS="$2"; shift 2 ;;
    --repo) REPO_ROOT="$(cd "$2" && pwd)"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    --) shift; EXTRA_ARGS+=("$@"); break ;;
    *) EXTRA_ARGS+=("$1"); shift ;;
  esac
done

MARKET_ID_ARGS=()
if [[ -n "$MARKET_IDS" ]]; then
  MARKET_IDS="${MARKET_IDS//,/ }"
  read -r -a MARKET_ID_ARGS <<< "$MARKET_IDS"
fi

if [[ ! -f "$OPTIMIZER" ]]; then
  echo "Cannot find optimizer: $OPTIMIZER" >&2
  echo "Put this runner next to voktrader_optimizer.py." >&2
  exit 1
fi

if [[ ! -f "$REPO_ROOT/pom.xml" ]]; then
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
echo "Profile:     $PROFILE"
echo "Model:       $MODEL"
echo "Iters:       $ITERS"
if [[ "${#MARKET_ID_ARGS[@]}" -gt 0 ]]; then
  echo "Market IDs:  ${MARKET_ID_ARGS[*]}"
else
  echo "Market IDs:  <profile defaults>"
fi
echo "Num ctx:     $NUM_CTX"
echo "Num predict: $NUM_PREDICT"
echo "Temp/top-p:  $TEMPERATURE / $TOP_P"
echo "Top-k/seed:  $TOP_K / $SEED"
echo "Server mode: $SERVER_MODE"
echo "Ollama URL:  $OLLAMA_URL"
echo

if [[ "$SERVER_MODE" == "manual" ]]; then
  cat <<'EOF'
Manual server mode:
  The optimizer will pause once at the start of the optimizer session.
  In another terminal, start Spring Boot and keep it running:

    cd /path/to/voktrader
    ./mvnw spring-boot:run

This is intentional for now because the current optimizer's auto-start path is Windows-specific.
EOF
  echo
fi

cd "$REPO_ROOT"

CMD_ARGS=(
  --repo "$REPO_ROOT"
  --profile "$PROFILE"
  --model "$MODEL"
  --use-strategy-override
  --iters "$ITERS"
  --min-trades "$MIN_TRADES"
  --port "$PORT"
  --server-mode "$SERVER_MODE"
  --ollama-url "$OLLAMA_URL"
  --ollama-keep-alive "$OLLAMA_KEEP_ALIVE"
  --no-ollama-stream
  --no-ollama-think
  --ollama-format "$OLLAMA_FORMAT"
  --num-ctx "$NUM_CTX"
  --num-predict "$NUM_PREDICT"
  --temperature "$TEMPERATURE"
  --top-k "$TOP_K"
  --top-p "$TOP_P"
  --repeat-penalty "$REPEAT_PENALTY"
  --seed "$SEED"
)

if [[ -n "$STRATEGY_FILE" ]]; then
  CMD_ARGS+=(--strategy-file "$STRATEGY_FILE")
fi

if [[ "${#MARKET_ID_ARGS[@]}" -gt 0 ]]; then
  CMD_ARGS+=(--market-ids "${MARKET_ID_ARGS[@]}")
fi

CMD_ARGS+=("${EXTRA_ARGS[@]}")

python3 "$OPTIMIZER" "${CMD_ARGS[@]}"
