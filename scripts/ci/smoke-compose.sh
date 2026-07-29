#!/usr/bin/env bash
set -euo pipefail

compose=(docker compose -f compose.smoke.yml)

cleanup() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}

show_logs() {
  "${compose[@]}" ps || true
  "${compose[@]}" logs --no-color || true
}

wait_for_url() {
  local name="$1"
  local url="$2"
  local attempts="${3:-60}"

  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if curl --fail --silent --show-error --max-time 3 "$url" >/dev/null; then
      echo "$name is ready: $url"
      return 0
    fi
    sleep 2
  done

  echo "$name did not become ready: $url" >&2
  return 1
}

trap cleanup EXIT

"${compose[@]}" up --build --detach

if ! wait_for_url "executor" "http://127.0.0.1:18099/health"; then
  show_logs
  exit 1
fi

if ! curl --fail --silent --show-error --max-time 3 \
  -H "Authorization: Bearer ci-smoke-token" \
  "http://127.0.0.1:18099/v1/capabilities" >/dev/null; then
  show_logs
  exit 1
fi

echo "executor authenticated capability probe passed"

if ! wait_for_url "Java app" "http://127.0.0.1:18080/api/runtime/status"; then
  show_logs
  exit 1
fi

echo "compose smoke passed"
