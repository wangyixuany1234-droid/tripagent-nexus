#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$REPOSITORY_ROOT/qa/scripts/run_with_report.py"
CONCURRENCY=${REAL_MODEL_MAX_CONCURRENCY:-1}
MAX_CALLS=${REAL_MODEL_MAX_CALLS:-1}

python3 "$REPOSITORY_ROOT/qa/scripts/guard_environment.py" \
  --kind real-model \
  --base-url "${REAL_MODEL_TEST_TARGET:-http://127.0.0.1:8080}" \
  --concurrency "$CONCURRENCY" \
  --max-calls "$MAX_CALLS"

if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  printf '%s\n' "真实模型测试要求通过环境变量注入 DEEPSEEK_API_KEY。" >&2
  exit 2
fi
if [ "$CONCURRENCY" -ne 1 ] || [ "$MAX_CALLS" -ne 1 ]; then
  printf '%s\n' "当前真实模型冒烟测试固定为单并发、单调用。" >&2
  exit 2
fi

export QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
export QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
REPORT_DIR=$("$REPOSITORY_ROOT/qa/scripts/prepare_test_results.sh")
export MODEL_GATEWAY_PROVIDER=deepseek
export MODEL_MAX_CONCURRENCY=1
export MODEL_MAX_ATTEMPTS=1
export MODEL_ALLOW_MOCK_FALLBACK=false
export QA_REQUEST_SUMMARY="单次合成 JSON 响应验证"

python3 "$RUNNER" --suite real-model-smoke -- \
  mvn -B -ntp -pl apps/trip-agent-api \
  -Dtest=RealModelSmokeTest \
  -Dsurefire.reportsDirectory="$REPORT_DIR/junit/real-model" \
  -Djacoco.skip=true \
  test
