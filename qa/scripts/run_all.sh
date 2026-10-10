#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$REPOSITORY_ROOT/qa/scripts/run_with_report.py"
export QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
export QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
REPORT_DIR=$("$REPOSITORY_ROOT/qa/scripts/prepare_test_results.sh")

"$REPOSITORY_ROOT/qa/scripts/run_fast.sh"
"$REPOSITORY_ROOT/qa/scripts/run_contract.sh"

python3 "$RUNNER" --suite frontend-e2e -- \
  npm --prefix "$REPOSITORY_ROOT/apps/web" run test:e2e

if [ "${RUN_TESTCONTAINERS:-false}" = "true" ]; then
  . "$REPOSITORY_ROOT/qa/scripts/configure_testcontainers_docker.sh"

  python3 "$RUNNER" --suite testcontainers -- \
    mvn -B -ntp -Ptestcontainers verify \
    -Dfailsafe.reportsDirectory="$REPORT_DIR/junit/testcontainers" \
    -Djacoco.report.directory="$REPORT_DIR/coverage/java-testcontainers" \
    -Djacoco.instruction.minimum=0 \
    -Djacoco.branch.minimum=0
fi

if [ "${RUN_SECURITY:-false}" = "true" ]; then
  "$REPOSITORY_ROOT/qa/scripts/run_security.sh"
fi

if [ "${RUN_CHAOS:-false}" = "true" ]; then
  "$REPOSITORY_ROOT/qa/scripts/run_chaos.sh"
fi

if [ "${RUN_PERFORMANCE:-false}" = "true" ]; then
  "$REPOSITORY_ROOT/qa/scripts/run_performance.sh" "${QA_PROFILE:-smoke}"
fi
