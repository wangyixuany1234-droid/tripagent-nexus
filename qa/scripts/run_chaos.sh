#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$REPOSITORY_ROOT/qa/scripts/run_with_report.py"
BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
COMPOSE_PROJECT=${QA_COMPOSE_PROJECT:-tripagent-nexus-qa}
COMPOSE_FILES="-f $REPOSITORY_ROOT/compose.yaml -f $REPOSITORY_ROOT/qa/compose.qa.yaml"

python3 "$REPOSITORY_ROOT/qa/scripts/guard_environment.py" \
  --kind chaos \
  --base-url "$BASE_URL"

if ! docker info >/dev/null 2>&1; then
  printf '%s\n' "Docker 守护进程不可用，无法执行 Toxiproxy 故障测试。" >&2
  exit 2
fi

cleanup() {
  if [ "${KEEP_QA_STACK:-false}" != "true" ]; then
    # shellcheck disable=SC2086
    docker compose -p "$COMPOSE_PROJECT" $COMPOSE_FILES down --volumes \
      --remove-orphans >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

# shellcheck disable=SC2086
docker compose -p "$COMPOSE_PROJECT" $COMPOSE_FILES up \
  --detach --build --wait

export QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
export QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
"$REPOSITORY_ROOT/qa/scripts/prepare_test_results.sh" >/dev/null
export BASE_URL
export TOXIPROXY_URL=${TOXIPROXY_URL:-http://127.0.0.1:8474}
export RECOVERY_TIMEOUT_SECONDS=$(
  python3 -c \
    'import json,sys; print(json.load(open(sys.argv[1]))["resilience"]["recoveryTimeoutSeconds"])' \
    "$REPOSITORY_ROOT/qa/config/thresholds.json"
)
export QA_REQUEST_SUMMARY="本地代理注入 AI 服务与 PostgreSQL 超时，验证降级、恢复及幂等一致性"

python3 "$RUNNER" --suite toxiproxy-recovery -- \
  "$REPOSITORY_ROOT/qa/chaos/fault-recovery.sh"
