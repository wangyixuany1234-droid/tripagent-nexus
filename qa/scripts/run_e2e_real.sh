#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$REPOSITORY_ROOT/qa/scripts/run_with_report.py"

export QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
export QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
export QA_WEB_BASE_URL=${QA_WEB_BASE_URL:-http://127.0.0.1:5173}
export QA_REAL_E2E=true
export QA_E2E_CAPTURE_ALL=${QA_E2E_CAPTURE_ALL:-true}
export QA_REQUEST_SUMMARY="脱敏北京三日游真实前后端联调"
. "$REPOSITORY_ROOT/qa/scripts/test_env_defaults.sh"

case "$QA_WEB_BASE_URL" in
  http://127.0.0.1:* | http://localhost:*) ;;
  *)
    printf '%s\n' "真实联调只允许访问本机测试环境。" >&2
    exit 2
    ;;
esac

"$REPOSITORY_ROOT/qa/scripts/prepare_test_results.sh" >/dev/null

if ! curl --fail --silent --show-error "$QA_WEB_BASE_URL/healthz" >/dev/null; then
  printf '%s\n' "QA Web 未就绪，请先执行 make test-env-up。" >&2
  exit 2
fi

HEALTH_REPORT="$QA_RESULTS_ROOT/$QA_RUN_ID/json/real-e2e-health.json"
curl --fail --silent --show-error \
  "$QA_WEB_BASE_URL/api/v1/health" \
  >"$HEALTH_REPORT"
python3 - "$HEALTH_REPORT" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as health_file:
    health = json.load(health_file)
if health.get("providerMode") != "MOCK":
    raise SystemExit("真实联调必须连接 providerMode=MOCK 的本地后端。")
PY

python3 "$RUNNER" --suite frontend-e2e-real -- \
  npm --prefix "$REPOSITORY_ROOT/apps/web" run test:e2e:real
