#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
MANAGE_ENVIRONMENT=${CONTRACT_MANAGE_ENV:-true}
STARTED_ENVIRONMENT=false

cleanup() {
  if [ "$STARTED_ENVIRONMENT" = "true" ]; then
    "$REPOSITORY_ROOT/qa/scripts/test_environment.sh" down
  fi
}
trap cleanup EXIT INT TERM

if [ "$MANAGE_ENVIRONMENT" = "true" ]; then
  "$REPOSITORY_ROOT/qa/scripts/test_environment.sh" up-contract
  STARTED_ENVIRONMENT=true
fi

export RUN_CONTRACT_FUZZ=true
export CONTRACT_BASE_URL=${CONTRACT_BASE_URL:-http://127.0.0.1:8080}
export CONTRACT_INTERNAL_BASE_URL=${CONTRACT_INTERNAL_BASE_URL:-http://127.0.0.1:8000}

"$REPOSITORY_ROOT/qa/scripts/run_contract.sh"
