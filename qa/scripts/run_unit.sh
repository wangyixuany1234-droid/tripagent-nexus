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

python3 "$RUNNER" --suite java-unit -- \
  mvn -B -ntp clean spotless:check verify \
  -Dtest="!*IntegrationTest,!ControllerOpenApiParityTest" \
  -Dsurefire.reportsDirectory="$REPORT_DIR/junit/java" \
  -Djacoco.report.directory="$REPORT_DIR/coverage/java" \
  -Djacoco.instruction.minimum=0 \
  -Djacoco.branch.minimum=0

python3 "$RUNNER" --suite python-lint -- \
  "$PYTHON" -m ruff check "$REPOSITORY_ROOT/services/ai-retrieval-service"

python3 "$RUNNER" --suite python-format -- \
  "$PYTHON" -m ruff format --check "$REPOSITORY_ROOT/services/ai-retrieval-service"

python3 "$RUNNER" --suite python -- \
  "$PYTHON" -m pytest \
  --cov=trip_agent_ai \
  --cov-branch \
  --junitxml="$REPORT_DIR/junit/python/pytest.xml" \
  --cov-report=term-missing \
  --cov-report="xml:$REPORT_DIR/coverage/python/coverage.xml" \
  --cov-report="json:$REPORT_DIR/coverage/python/coverage.json" \
  --cov-report="html:$REPORT_DIR/coverage/python/html" \
  "$REPOSITORY_ROOT/services/ai-retrieval-service"

python3 "$RUNNER" --suite frontend-unit -- \
  npm --prefix "$REPOSITORY_ROOT/apps/web" run test:coverage

python3 "$RUNNER" --suite frontend-typecheck -- \
  npm --prefix "$REPOSITORY_ROOT/apps/web" run typecheck
