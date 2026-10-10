#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$REPOSITORY_ROOT/qa/scripts/run_with_report.py"
PROFILE=${1:-smoke}
BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
AI_BASE_URL=${AI_BASE_URL:-http://127.0.0.1:8000}
K6_BIN=${K6_BIN:-}

case "$PROFILE" in
  smoke|load|stress|spike|soak|sse|idempotency|race|canary) ;;
  *)
    printf '%s\n' "未知性能测试类型：$PROFILE" >&2
    exit 2
    ;;
esac

if [ "$PROFILE" = "canary" ]; then
  if [ "${QA_REAL_MODEL_CANARY_APPROVED:-false}" != "true" ]; then
    printf '%s\n' "真实模型 Canary 必须显式设置 QA_REAL_MODEL_CANARY_APPROVED=true。" >&2
    exit 2
  fi
  if [ "${QA_MOCK_EXTERNAL_TOOLS_CONFIRMED:-false}" != "true" ]; then
    printf '%s\n' "真实模型 Canary 必须确认 OCR 和外部工具均为 Mock。" >&2
    exit 2
  fi
  if [ "${QA_CANARY_BACKEND_LIMITS_CONFIRMED:-false}" != "true" ]; then
    printf '%s\n' "真实模型 Canary 必须确认后端 MODEL_MAX_CONCURRENCY 和 MODEL_MAX_TOTAL_CALLS 已设置。" >&2
    exit 2
  fi
  python3 "$REPOSITORY_ROOT/qa/scripts/guard_environment.py" \
    --kind real-model \
    --base-url "$BASE_URL" \
    --concurrency "${QA_REAL_MODEL_MAX_CONCURRENCY:-2}" \
    --max-calls "${QA_REAL_MODEL_MAX_CALLS:-5}"
  EXPECTED_PROVIDER=LIVE
else
  python3 "$REPOSITORY_ROOT/qa/scripts/guard_environment.py" \
    --kind performance \
    --base-url "$BASE_URL"
  . "$REPOSITORY_ROOT/qa/scripts/test_env_defaults.sh"
  EXPECTED_PROVIDER=MOCK
fi

case "$PROFILE" in
  load|stress|spike|soak|sse)
    if [ "${QA_HIGH_LOAD_APPROVED:-false}" != "true" ]; then
      printf '%s\n' "高负载场景必须显式设置 QA_HIGH_LOAD_APPROVED=true。" >&2
      exit 2
    fi
    if [ "${QA_MOCK_EXTERNAL_TOOLS_CONFIRMED:-false}" != "true" ]; then
      printf '%s\n' "高负载场景必须确认 OCR 和外部工具均为 Mock。" >&2
      exit 2
    fi
    ;;
esac

python3 "$REPOSITORY_ROOT/qa/scripts/guard_environment.py" \
  --kind performance \
  --base-url "$AI_BASE_URL"

if [ -n "$K6_BIN" ]; then
  if [ ! -x "$K6_BIN" ]; then
    printf '%s\n' "K6_BIN 必须指向可执行文件。" >&2
    exit 2
  fi
  K6_ENGINE=native
elif command -v k6 >/dev/null 2>&1; then
  K6_BIN=$(command -v k6)
  K6_ENGINE=native
elif docker info >/dev/null 2>&1; then
  K6_ENGINE=docker
else
  printf '%s\n' "未安装 k6，且 Docker 守护进程不可用。" >&2
  exit 2
fi

export QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
export QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
export QA_PROFILE="$PROFILE"
export BASE_URL
export QA_REQUEST_SUMMARY="阶段五合成性能场景：$PROFILE"
export QA_AI_SERVICE_TOKEN=${QA_AI_SERVICE_TOKEN:-test-service-token}
export POSTGRES_DATABASE=${POSTGRES_DATABASE:-tripagent_test}
export POSTGRES_USERNAME=${POSTGRES_USERNAME:-tripagent_test}
export REDIS_PASSWORD=${REDIS_PASSWORD:-test-redis-password}

RUN_DIR=$("$REPOSITORY_ROOT/qa/scripts/prepare_test_results.sh")
TARGET_REPORT="$RUN_DIR/json/performance-target-$PROFILE.json"
python3 "$REPOSITORY_ROOT/qa/scripts/check_performance_target.py" \
  --base-url "$BASE_URL" \
  --expected-provider "$EXPECTED_PROVIDER" \
  --report "$TARGET_REPORT"

if [ "$PROFILE" != "canary" ]; then
  python3 "$REPOSITORY_ROOT/qa/scripts/validate_test_environment.py" \
    --report "$RUN_DIR/json/performance-isolation-$PROFILE.json"
fi

case "$PROFILE" in
  smoke|load|stress|spike|soak)
    SCRIPT="$REPOSITORY_ROOT/qa/performance/trip-workload.js"
    ;;
  sse)
    SCRIPT="$REPOSITORY_ROOT/qa/performance/sse-workload.js"
    ;;
  idempotency)
    SCRIPT="$REPOSITORY_ROOT/qa/performance/idempotency-concurrency.js"
    ;;
  race)
    SCRIPT="$REPOSITORY_ROOT/qa/performance/same-resource-race.js"
    ;;
  canary)
    SCRIPT="$REPOSITORY_ROOT/qa/performance/real-model-canary.js"
    ;;
esac

K6_SUMMARY="$RUN_DIR/performance/k6-$PROFILE-summary.json"
K6_HTML="$RUN_DIR/html/k6-$PROFILE-report.html"
RESOURCE_SAMPLES="$RUN_DIR/performance/resources-$PROFILE.ndjson"
RESOURCE_SUMMARY="$RUN_DIR/json/resources-$PROFILE-summary.json"
STOP_FILE="$RUN_DIR/performance/metrics-$PROFILE.stop"
COMBINED_JSON="$RUN_DIR/json/stage5-$PROFILE-summary.json"
COMBINED_HTML="$RUN_DIR/html/stage5-$PROFILE-report.html"
COLLECTOR_PID=
stop_collector() {
  if [ -n "$COLLECTOR_PID" ]; then
    touch "$STOP_FILE"
    wait "$COLLECTOR_PID" 2>/dev/null || true
    COLLECTOR_PID=
  fi
}
trap stop_collector EXIT INT TERM

python3 "$REPOSITORY_ROOT/qa/scripts/collect_performance_metrics.py" \
  --base-url "$BASE_URL" \
  --ai-base-url "$AI_BASE_URL" \
  --output "$RESOURCE_SAMPLES" \
  --summary "$RESOURCE_SUMMARY" \
  --stop-file "$STOP_FILE" &
COLLECTOR_PID=$!

set +e
if [ "$K6_ENGINE" = "native" ]; then
  QA_K6_JSON_PATH="$K6_SUMMARY" \
  QA_K6_HTML_PATH="$K6_HTML" \
  python3 "$RUNNER" --suite "k6-$PROFILE" -- \
    "$K6_BIN" run "$SCRIPT"
  K6_EXIT=$?
else
  K6_BASE_URL=$BASE_URL
  case "$BASE_URL" in
    http://127.0.0.1:*|http://localhost:*)
      K6_BASE_URL=$(printf '%s' "$BASE_URL" | sed -E 's#http://(127\.0\.0\.1|localhost)#http://host.docker.internal#')
      ;;
  esac
  python3 "$RUNNER" --suite "k6-$PROFILE" -- \
    docker run --rm \
    --user "$(id -u):$(id -g)" \
    --add-host host.docker.internal:host-gateway \
    -e "BASE_URL=$K6_BASE_URL" \
    -e "QA_PROFILE=$QA_PROFILE" \
    -e "QA_RUN_ID=$QA_RUN_ID" \
    -e "QA_DURATION=${QA_DURATION:-}" \
    -e "QA_VUS=${QA_VUS:-}" \
    -e "QA_STAGES_JSON=${QA_STAGES_JSON:-}" \
    -e "QA_THINK_TIME_SECONDS=${QA_THINK_TIME_SECONDS:-}" \
    -e "QA_ASYNC_TIMEOUT_SECONDS=${QA_ASYNC_TIMEOUT_SECONDS:-}" \
    -e "QA_TASK_POLL_INTERVAL_SECONDS=${QA_TASK_POLL_INTERVAL_SECONDS:-}" \
    -e "QA_SETUP_TIMEOUT=${QA_SETUP_TIMEOUT:-}" \
    -e "QA_SYNC_WEIGHT=${QA_SYNC_WEIGHT:-}" \
    -e "QA_WRITE_WEIGHT=${QA_WRITE_WEIGHT:-}" \
    -e "QA_ASYNC_WEIGHT=${QA_ASYNC_WEIGHT:-}" \
    -e "QA_SYNC_P95_MS=${QA_SYNC_P95_MS:-}" \
    -e "QA_SYNC_P99_MS=${QA_SYNC_P99_MS:-}" \
    -e "QA_SYNC_ERROR_RATE=${QA_SYNC_ERROR_RATE:-}" \
    -e "QA_HTTP_ERROR_RATE=${QA_HTTP_ERROR_RATE:-}" \
    -e "QA_BUSINESS_ERROR_RATE=${QA_BUSINESS_ERROR_RATE:-}" \
    -e "QA_WRITE_P95_MS=${QA_WRITE_P95_MS:-}" \
    -e "QA_WRITE_P99_MS=${QA_WRITE_P99_MS:-}" \
    -e "QA_WRITE_ERROR_RATE=${QA_WRITE_ERROR_RATE:-}" \
    -e "QA_ASYNC_SUBMIT_P95_MS=${QA_ASYNC_SUBMIT_P95_MS:-}" \
    -e "QA_ASYNC_SUBMIT_P99_MS=${QA_ASYNC_SUBMIT_P99_MS:-}" \
    -e "QA_ASYNC_COMPLETION_P95_MS=${QA_ASYNC_COMPLETION_P95_MS:-}" \
    -e "QA_ASYNC_ERROR_RATE=${QA_ASYNC_ERROR_RATE:-}" \
    -e "QA_MAX_TASK_STUCK_RATE=${QA_MAX_TASK_STUCK_RATE:-}" \
    -e "QA_MAX_DUPLICATE_RESOURCES=${QA_MAX_DUPLICATE_RESOURCES:-}" \
    -e "QA_SSE_MIN_CONNECT_SUCCESS_RATE=${QA_SSE_MIN_CONNECT_SUCCESS_RATE:-}" \
    -e "QA_SSE_MIN_RECONNECT_SUCCESS_RATE=${QA_SSE_MIN_RECONNECT_SUCCESS_RATE:-}" \
    -e "QA_SSE_MAX_DUPLICATE_EVENTS=${QA_SSE_MAX_DUPLICATE_EVENTS:-}" \
    -e "QA_SSE_MAX_LOST_EVENTS=${QA_SSE_MAX_LOST_EVENTS:-}" \
    -e "QA_SSE_REQUEST_TIMEOUT=${QA_SSE_REQUEST_TIMEOUT:-}" \
    -e "QA_REAL_MODEL_MAX_CALLS=${QA_REAL_MODEL_MAX_CALLS:-}" \
    -e "QA_REAL_MODEL_MAX_CONCURRENCY=${QA_REAL_MODEL_MAX_CONCURRENCY:-}" \
    -e "QA_K6_JSON_PATH=/reports/k6-$PROFILE-summary.json" \
    -e "QA_K6_HTML_PATH=/reports/k6-$PROFILE-report.html" \
    -v "$REPOSITORY_ROOT:/workspace:ro" \
    -v "$RUN_DIR/performance:/reports" \
    -v "$RUN_DIR/html:/html-reports" \
    grafana/k6:0.54.0 run \
    -e "QA_K6_HTML_PATH=/html-reports/k6-$PROFILE-report.html" \
    "/workspace/${SCRIPT#"$REPOSITORY_ROOT/"}"
  K6_EXIT=$?
fi
set -e

sleep "${QA_RECOVERY_OBSERVE_SECONDS:-60}"
stop_collector
trap - EXIT INT TERM

SUMMARY_EXIT=1
if [ -f "$K6_SUMMARY" ] && [ -f "$RESOURCE_SUMMARY" ]; then
  set +e
  python3 "$REPOSITORY_ROOT/qa/scripts/summarize_performance.py" \
    --profile "$PROFILE" \
    --k6-summary "$K6_SUMMARY" \
    --resource-summary "$RESOURCE_SUMMARY" \
    --output-json "$COMBINED_JSON" \
    --output-html "$COMBINED_HTML" \
    --k6-exit-code "$K6_EXIT"
  SUMMARY_EXIT=$?
  set -e
fi

if [ "$K6_EXIT" -ne 0 ]; then
  exit "$K6_EXIT"
fi
exit "$SUMMARY_EXIT"
