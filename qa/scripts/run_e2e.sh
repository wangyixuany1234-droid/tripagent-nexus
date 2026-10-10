#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$REPOSITORY_ROOT/qa/scripts/run_with_report.py"

export QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
export QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
. "$REPOSITORY_ROOT/qa/scripts/test_env_defaults.sh"

"$REPOSITORY_ROOT/qa/scripts/prepare_test_results.sh" >/dev/null

python3 "$RUNNER" --suite frontend-e2e -- \
  npm --prefix "$REPOSITORY_ROOT/apps/web" run test:e2e
