#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$REPOSITORY_ROOT/qa/scripts/run_with_report.py"
PYTHON="$REPOSITORY_ROOT/services/ai-retrieval-service/.venv/bin/python"

export QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
export QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
. "$REPOSITORY_ROOT/qa/scripts/test_env_defaults.sh"
REPORT_DIR=$("$REPOSITORY_ROOT/qa/scripts/prepare_test_results.sh")

python3 "$RUNNER" --suite test-profile-isolation -- \
  python3 "$REPOSITORY_ROOT/qa/scripts/validate_test_environment.py" \
  --report "$REPORT_DIR/json/test-profile-isolation-details.json"

python3 "$RUNNER" --suite fixture-initialization -- \
  python3 "$REPOSITORY_ROOT/qa/scripts/init_test_data.py" \
  --report "$REPORT_DIR/json/fixture-index.json"

python3 "$RUNNER" --suite qa-harness -- \
  python3 -m unittest discover \
  -s "$REPOSITORY_ROOT/qa/tests" \
  -p "test_*.py"

JAVA_INSTRUCTION_COVERAGE=$(
  python3 -c \
    'import json,sys; print(json.load(open(sys.argv[1]))["coverage"]["java"]["instruction"])' \
    "$REPOSITORY_ROOT/qa/config/thresholds.json"
)
JAVA_BRANCH_COVERAGE=$(
  python3 -c \
    'import json,sys; print(json.load(open(sys.argv[1]))["coverage"]["java"]["branch"])' \
    "$REPOSITORY_ROOT/qa/config/thresholds.json"
)
PYTHON_LINE_COVERAGE=$(
  python3 -c \
    'import json,sys; print(json.load(open(sys.argv[1]))["coverage"]["python"]["line"])' \
    "$REPOSITORY_ROOT/qa/config/thresholds.json"
)

python3 "$RUNNER" --suite java -- \
  mvn -B -ntp clean verify \
  -Dsurefire.reportsDirectory="$REPORT_DIR/junit/java" \
  -Djacoco.report.directory="$REPORT_DIR/coverage/java" \
  -Djacoco.instruction.minimum="$JAVA_INSTRUCTION_COVERAGE" \
  -Djacoco.branch.minimum="$JAVA_BRANCH_COVERAGE"

python3 "$RUNNER" --suite python -- \
  "$PYTHON" -m pytest \
  --cov=trip_agent_ai \
  --cov-branch \
  --cov-fail-under="$PYTHON_LINE_COVERAGE" \
  --cov-report=term-missing \
  --junitxml="$REPORT_DIR/junit/python/pytest.xml" \
  --cov-report="xml:$REPORT_DIR/coverage/python/coverage.xml" \
  --cov-report="json:$REPORT_DIR/coverage/python/coverage.json" \
  --cov-report="html:$REPORT_DIR/coverage/python/html" \
  "$REPOSITORY_ROOT/services/ai-retrieval-service"

python3 "$RUNNER" --suite python-coverage-gate -- \
  python3 "$REPOSITORY_ROOT/qa/scripts/check_python_coverage.py" \
  --report "$REPORT_DIR/coverage/python/coverage.json"

python3 "$RUNNER" --suite frontend-unit -- \
  npm --prefix "$REPOSITORY_ROOT/apps/web" run test:coverage

python3 "$RUNNER" --suite frontend-typecheck -- \
  npm --prefix "$REPOSITORY_ROOT/apps/web" run typecheck

python3 "$RUNNER" --suite frontend-build -- \
  npm --prefix "$REPOSITORY_ROOT/apps/web" run build
