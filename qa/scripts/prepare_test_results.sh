#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
RUN_DIR="$QA_RESULTS_ROOT/$QA_RUN_ID"

mkdir -p \
  "$RUN_DIR/junit/java" \
  "$RUN_DIR/junit/python" \
  "$RUN_DIR/junit/frontend" \
  "$RUN_DIR/junit/e2e" \
  "$RUN_DIR/json" \
  "$RUN_DIR/html" \
  "$RUN_DIR/coverage/java" \
  "$RUN_DIR/coverage/python" \
  "$RUN_DIR/coverage/frontend" \
  "$RUN_DIR/performance" \
  "$RUN_DIR/logs" \
  "$RUN_DIR/tool-config"

printf '%s\n' "$RUN_DIR"
