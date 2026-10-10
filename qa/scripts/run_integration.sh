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

python3 "$RUNNER" --suite java-integration -- \
  mvn -B -ntp test \
  -Dtest="*IntegrationTest,!InfrastructureTestcontainersIntegrationTest,!ToxiproxyTestcontainersIntegrationTest" \
  -Dsurefire.reportsDirectory="$REPORT_DIR/junit/java-integration"

python3 "$RUNNER" --suite python-integration -- \
  "$PYTHON" -m pytest \
  --junitxml="$REPORT_DIR/junit/python/integration.xml" \
  "$REPOSITORY_ROOT/services/ai-retrieval-service/tests/test_document_parser.py" \
  "$REPOSITORY_ROOT/services/ai-retrieval-service/tests/test_retrieval_api.py" \
  "$REPOSITORY_ROOT/services/ai-retrieval-service/tests/test_security_concurrency.py"

if [ "${SKIP_TESTCONTAINERS:-false}" = "true" ]; then
  printf '%s\n' "Testcontainers 已按 SKIP_TESTCONTAINERS=true 跳过。"
  exit 0
fi

. "$REPOSITORY_ROOT/qa/scripts/configure_testcontainers_docker.sh"

if ! docker info >/dev/null 2>&1; then
  printf '%s\n' "Docker 守护进程不可用，Testcontainers 集成测试未执行。" >&2
  exit 2
fi

python3 "$RUNNER" --suite testcontainers -- \
  mvn -B -ntp -Ptestcontainers verify \
  -Dfailsafe.reportsDirectory="$REPORT_DIR/junit/testcontainers" \
  -Djacoco.report.directory="$REPORT_DIR/coverage/java-testcontainers" \
  -Djacoco.instruction.minimum=0 \
  -Djacoco.branch.minimum=0
