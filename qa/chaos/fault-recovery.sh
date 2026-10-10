#!/bin/sh
set -eu

BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
TOXIPROXY_URL=${TOXIPROXY_URL:-http://127.0.0.1:8474}
RECOVERY_TIMEOUT_SECONDS=${RECOVERY_TIMEOUT_SECONDS:-30}
TMP_DIR=${TMPDIR:-/tmp}/tripagent-chaos-$$
mkdir -p "$TMP_DIR"
cleanup() {
  curl --silent --max-time 5 -X DELETE \
    "$TOXIPROXY_URL/proxies/ai-retrieval/toxics/qa-ai-timeout" >/dev/null 2>&1 || true
  curl --silent --max-time 5 -X DELETE \
    "$TOXIPROXY_URL/proxies/postgres/toxics/qa-postgres-timeout" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

wait_for_status() {
  url=$1
  expected=$2
  timeout=$3
  started=$(date +%s)
  while :; do
    actual=$(curl --silent --output /dev/null --write-out "%{http_code}" \
      --max-time 5 "$url" || true)
    if [ "$actual" = "$expected" ]; then
      return 0
    fi
    if [ $(($(date +%s) - started)) -ge "$timeout" ]; then
      printf '%s\n' "等待 $url 返回 $expected 超时，最后状态为 $actual" >&2
      return 1
    fi
    sleep 1
  done
}

add_timeout() {
  proxy=$1
  name=$2
  timeout_ms=$3
  curl --fail --silent --max-time 5 \
    -X POST "$TOXIPROXY_URL/proxies/$proxy/toxics" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$name\",\"type\":\"timeout\",\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"timeout\":$timeout_ms}}" \
    >/dev/null
}

remove_toxic() {
  proxy=$1
  name=$2
  curl --fail --silent --max-time 5 \
    -X DELETE "$TOXIPROXY_URL/proxies/$proxy/toxics/$name" \
    >/dev/null
}

wait_for_status "$BASE_URL/actuator/health/readiness" 200 "$RECOVERY_TIMEOUT_SECONDS"

add_timeout ai-retrieval qa-ai-timeout 5000
wait_for_status "$BASE_URL/actuator/health/readiness" 503 "$RECOVERY_TIMEOUT_SECONDS"
remove_toxic ai-retrieval qa-ai-timeout
wait_for_status "$BASE_URL/actuator/health/readiness" 200 "$RECOVERY_TIMEOUT_SECONDS"

PAYLOAD='{"userId":"10000000-0000-4000-8000-000000009901","title":"合成故障恢复行程","timeZone":"Asia/Shanghai"}'
HEADERS="-H Content-Type:application/json -H Idempotency-Key:qa-chaos-recovery-0001"

add_timeout postgres qa-postgres-timeout 5000
# The failure response is intentionally ignored; recovery and replay are asserted below.
# shellcheck disable=SC2086
curl --silent --max-time 8 $HEADERS -d "$PAYLOAD" \
  "$BASE_URL/api/v1/trips" >"$TMP_DIR/during-fault.json" || true
remove_toxic postgres qa-postgres-timeout
wait_for_status "$BASE_URL/actuator/health/readiness" 200 "$RECOVERY_TIMEOUT_SECONDS"

# shellcheck disable=SC2086
curl --fail --silent $HEADERS -d "$PAYLOAD" \
  "$BASE_URL/api/v1/trips" >"$TMP_DIR/recovered.json"
# shellcheck disable=SC2086
curl --fail --silent $HEADERS -d "$PAYLOAD" \
  "$BASE_URL/api/v1/trips" >"$TMP_DIR/replayed.json"

python3 - "$TMP_DIR/recovered.json" "$TMP_DIR/replayed.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as recovered_file:
    recovered = json.load(recovered_file)
with open(sys.argv[2], encoding="utf-8") as replayed_file:
    replayed = json.load(replayed_file)

if not recovered.get("tripId") or recovered.get("tripId") != replayed.get("tripId"):
    raise SystemExit("故障恢复后的幂等重放产生了不一致资源")
PY

printf '%s\n' "故障降级、恢复和幂等重放验证完成。"
