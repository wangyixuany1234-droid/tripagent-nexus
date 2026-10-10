#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$REPOSITORY_ROOT/qa/scripts/run_with_report.py"
PYTHON="$REPOSITORY_ROOT/services/ai-retrieval-service/.venv/bin/python"

export QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
export QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
. "$REPOSITORY_ROOT/qa/scripts/test_env_defaults.sh"

REPORT_DIR=$("$REPOSITORY_ROOT/qa/scripts/prepare_test_results.sh")
JAVA_COVERAGE_DIR="$REPORT_DIR/coverage/java"
PYTHON_COVERAGE_DIR="$REPORT_DIR/coverage/python"

python3 "$RUNNER" --suite java-backend-functional -- \
  mvn -B -ntp -pl apps/trip-agent-api clean test \
  -Dsurefire.reportsDirectory="$REPORT_DIR/junit/java" \
  -Djacoco.instruction.minimum=0 \
  -Djacoco.branch.minimum=0

python3 "$RUNNER" --suite java-backend-coverage-report -- \
  mvn -B -ntp -pl apps/trip-agent-api jacoco:report@report \
  -Djacoco.report.directory="$JAVA_COVERAGE_DIR"

. "$REPOSITORY_ROOT/qa/scripts/configure_testcontainers_docker.sh"
if ! docker info >/dev/null 2>&1; then
  printf '%s\n' "Docker 守护进程不可用，阶段二 Testcontainers 测试未执行。" >&2
  exit 2
fi

python3 "$RUNNER" --suite java-phase2-testcontainers -- \
  mvn -B -ntp -Ptestcontainers verify \
  -Dit.test=Phase2ApiFunctionalTestcontainersIntegrationTest \
  -Dfailsafe.reportsDirectory="$REPORT_DIR/junit/testcontainers" \
  -Djacoco.skip=true

python3 "$RUNNER" --suite python-backend-lint -- \
  "$PYTHON" -m ruff check "$REPOSITORY_ROOT/services/ai-retrieval-service"

python3 "$RUNNER" --suite python-backend-format -- \
  "$PYTHON" -m ruff format --check "$REPOSITORY_ROOT/services/ai-retrieval-service"

python3 "$RUNNER" --suite python-backend-functional -- \
  "$PYTHON" -m pytest \
  --cov=trip_agent_ai \
  --cov-branch \
  --junitxml="$REPORT_DIR/junit/python/pytest.xml" \
  --cov-report=term-missing \
  --cov-report="xml:$PYTHON_COVERAGE_DIR/coverage.xml" \
  --cov-report="json:$PYTHON_COVERAGE_DIR/coverage.json" \
  --cov-report="html:$PYTHON_COVERAGE_DIR/html" \
  "$REPOSITORY_ROOT/services/ai-retrieval-service"

python3 "$RUNNER" --suite backend-layered-coverage -- \
  python3 "$REPOSITORY_ROOT/qa/scripts/check_backend_functional_coverage.py" \
  --jacoco "$JAVA_COVERAGE_DIR/jacoco.xml" \
  --python "$PYTHON_COVERAGE_DIR/coverage.json" \
  --output "$REPORT_DIR/json/backend-functional-coverage.json"

printf '%s\n' "$REPORT_DIR"
