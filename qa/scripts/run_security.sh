#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$REPOSITORY_ROOT/qa/scripts/run_with_report.py"
BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
LOCAL_TOOLS="$REPOSITORY_ROOT/.runtime/tools"
UVX="$REPOSITORY_ROOT/services/ai-retrieval-service/.venv/bin/uvx"
export QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
export QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
REPORT_DIR=$("$REPOSITORY_ROOT/qa/scripts/prepare_test_results.sh")
JSON_DIR="$REPORT_DIR/json"
RUNTIME_DIR="$REPOSITORY_ROOT/.runtime"
mkdir -p "$REPORT_DIR" "$RUNTIME_DIR/semgrep-config" \
  "$RUNTIME_DIR/dependency-check-data"

export XDG_CONFIG_HOME=${XDG_CONFIG_HOME:-"$RUNTIME_DIR/semgrep-config"}
export SEMGREP_SETTINGS_FILE=${SEMGREP_SETTINGS_FILE:-"$RUNTIME_DIR/semgrep-config/settings.yaml"}
export SEMGREP_LOG_FILE=${SEMGREP_LOG_FILE:-"$RUNTIME_DIR/semgrep-config/semgrep.log"}
export SEMGREP_SEND_METRICS=off
export SEMGREP_ENABLE_VERSION_CHECK=0

run_container_tool() {
  suite=$1
  shift
  if ! docker info >/dev/null 2>&1; then
    printf '%s\n' "缺少 $suite，且 Docker 守护进程不可用。" >&2
    exit 2
  fi
  python3 "$RUNNER" --suite "$suite" -- "$@"
}

if command -v semgrep >/dev/null 2>&1; then
  python3 "$RUNNER" --suite semgrep -- \
    semgrep scan --config "$REPOSITORY_ROOT/qa/security/semgrep.yml" \
    --error --json-output "$JSON_DIR/semgrep-findings.json" \
    --exclude node_modules --exclude target --exclude .venv \
    --exclude .runtime --exclude qa/reports --exclude output/test-results \
    "$REPOSITORY_ROOT"
elif [ -x "$UVX" ]; then
  python3 "$RUNNER" --suite semgrep -- \
    "$UVX" --from semgrep==1.136.0 --with "setuptools<81" \
    semgrep scan --config "$REPOSITORY_ROOT/qa/security/semgrep.yml" \
    --error --json-output "$JSON_DIR/semgrep-findings.json" \
    --exclude node_modules --exclude target --exclude .venv \
    --exclude .runtime --exclude qa/reports --exclude output/test-results \
    "$REPOSITORY_ROOT"
else
  run_container_tool semgrep \
    docker run --rm \
    -v "$REPOSITORY_ROOT:/src" \
    semgrep/semgrep:1.136.0 semgrep scan \
    --config /src/qa/security/semgrep.yml \
    --error --exclude /src/output/test-results \
    --json-output /src/output/test-results/"$QA_RUN_ID"/json/semgrep-findings.json /src
fi

if command -v gitleaks >/dev/null 2>&1; then
  python3 "$RUNNER" --suite gitleaks -- \
    gitleaks dir "$REPOSITORY_ROOT" \
    --config "$REPOSITORY_ROOT/.gitleaks.toml" \
    --no-banner --redact \
    --report-format json --report-path "$JSON_DIR/gitleaks-findings.json"
elif [ -x "$LOCAL_TOOLS/gitleaks" ]; then
  python3 "$RUNNER" --suite gitleaks -- \
    "$LOCAL_TOOLS/gitleaks" dir "$REPOSITORY_ROOT" \
    --config "$REPOSITORY_ROOT/.gitleaks.toml" \
    --no-banner --redact \
    --report-format json --report-path "$JSON_DIR/gitleaks-findings.json"
else
  run_container_tool gitleaks \
    docker run --rm \
    -v "$REPOSITORY_ROOT:/repo" \
    zricethezav/gitleaks:v8.28.0 dir /repo \
    --config /repo/.gitleaks.toml \
    --no-banner --redact \
    --report-format json \
    --report-path /repo/output/test-results/"$QA_RUN_ID"/json/gitleaks-findings.json
fi

if command -v trivy >/dev/null 2>&1; then
  python3 "$RUNNER" --suite trivy -- \
    trivy fs --exit-code 1 --severity HIGH,CRITICAL \
    --scanners vuln,secret,misconfig \
    --skip-dirs node_modules --skip-dirs target --skip-dirs .venv \
    --skip-dirs .runtime --skip-dirs qa/reports --skip-dirs output/test-results \
    --format json --output "$JSON_DIR/trivy-findings.json" "$REPOSITORY_ROOT"
elif [ -x "$LOCAL_TOOLS/trivy" ]; then
  python3 "$RUNNER" --suite trivy -- \
    "$LOCAL_TOOLS/trivy" fs --exit-code 1 --severity HIGH,CRITICAL \
    --scanners vuln,secret,misconfig \
    --skip-dirs node_modules --skip-dirs target --skip-dirs .venv \
    --skip-dirs .runtime --skip-dirs qa/reports --skip-dirs output/test-results \
    --format json --output "$JSON_DIR/trivy-findings.json" "$REPOSITORY_ROOT"
else
  run_container_tool trivy \
    docker run --rm \
    -v "$REPOSITORY_ROOT:/repo" \
    -v tripagent-trivy-cache:/root/.cache/trivy \
    aquasec/trivy:0.74.0 fs \
    --exit-code 1 --severity HIGH,CRITICAL \
    --scanners vuln,secret,misconfig \
    --skip-dirs node_modules --skip-dirs target --skip-dirs .venv \
    --skip-dirs .runtime --skip-dirs qa/reports --skip-dirs output/test-results \
    --format json \
    --output /repo/output/test-results/"$QA_RUN_ID"/json/trivy-findings.json /repo
fi

python3 "$RUNNER" --suite npm-audit -- \
  npm --prefix "$REPOSITORY_ROOT/apps/web" audit --audit-level=high

python3 "$RUNNER" --suite pip-audit -- \
  "$REPOSITORY_ROOT/qa/scripts/audit_python_dependencies.sh" \
  "$JSON_DIR/pip-audit-findings.json"

if [ "${RUN_DEPENDENCY_CHECK:-false}" = "true" ]; then
  python3 "$RUNNER" --suite dependency-check -- \
    mvn -B -ntp org.owasp:dependency-check-maven:12.1.3:check \
    -DfailBuildOnCVSS=7 \
    -Dformat=JSON \
    -DoutputDirectory="$JSON_DIR/dependency-check" \
    -DdataDirectory="$RUNTIME_DIR/dependency-check-data" \
    -DnvdApiKeyEnvironmentVariable=NVD_API_KEY
else
  printf '%s\n' \
    "Dependency-Check 未启用；Trivy 已执行等价依赖门禁。设置 RUN_DEPENDENCY_CHECK=true 后执行补充扫描。"
fi

if [ "${RUN_ZAP:-false}" != "true" ]; then
  printf '%s\n' "ZAP 动态扫描未启用；启动本地服务并设置 RUN_ZAP=true 后执行。"
  exit 0
fi

python3 "$REPOSITORY_ROOT/qa/scripts/guard_environment.py" \
  --kind security \
  --base-url "$BASE_URL"

run_container_tool zap \
  docker run --rm --network host \
  -v "$REPOSITORY_ROOT/qa/security:/zap/wrk/config:ro" \
  -v "$REPORT_DIR:/zap/wrk/reports" \
  zaproxy/zap-stable:2.16.1 zap-baseline.py \
  -t "$BASE_URL" \
  -c /zap/wrk/config/zap-rules.tsv \
  -J /zap/wrk/reports/json/zap-findings.json \
  -r /zap/wrk/reports/html/zap.html
