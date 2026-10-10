#!/bin/sh
set -eu

API=${TOXIPROXY_URL:-http://toxiproxy:8474}
STARTUP_TIMEOUT_SECONDS=${TOXIPROXY_STARTUP_TIMEOUT_SECONDS:-30}
started=$(date +%s)

until curl --fail --silent --max-time 2 "$API/version" >/dev/null; do
  if [ $(($(date +%s) - started)) -ge "$STARTUP_TIMEOUT_SECONDS" ]; then
    printf '%s\n' "Toxiproxy 启动等待超时" >&2
    exit 1
  fi
  sleep 1
done

create_proxy() {
  name=$1
  listen=$2
  upstream=$3
  curl --fail --silent --max-time 5 \
    -X DELETE "$API/proxies/$name" \
    >/dev/null 2>&1 || true
  curl --fail --silent --max-time 5 \
    -X POST "$API/proxies" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$name\",\"listen\":\"0.0.0.0:$listen\",\"upstream\":\"$upstream\"}" \
    >/dev/null
}

create_proxy postgres 15432 postgres:5432
create_proxy redis 16379 redis:6379
create_proxy ai-retrieval 18000 ai-retrieval-service:8000
