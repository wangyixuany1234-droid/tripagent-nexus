#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ACTION=${1:-}
COMPOSE_PROJECT=${QA_COMPOSE_PROJECT:-tripagent-nexus-test}
RUNTIME_ROOT="$REPOSITORY_ROOT/.runtime/test-env"

export POSTGRES_DATABASE=tripagent_test
export POSTGRES_USERNAME=tripagent_test
export POSTGRES_PASSWORD=test-postgres-password
export REDIS_PASSWORD=test-redis-password
export NEO4J_USERNAME=neo4j
export NEO4J_PASSWORD=test-neo4j-password
export AI_SERVICE_TOKEN=test-service-token
export MCP_SERVICE_TOKEN=test-service-token
export DEEPSEEK_API_KEY=
export MODEL_GATEWAY_PROVIDER=mock
export DOCUMENT_PARSER_MODE=mock
export RETRIEVAL_PROVIDER_MODE=mock
export GRAPH_STORE_MODE=memory
export MCP_PROVIDER_MODE=MOCK
export PLANNING_PROVIDER_MODE=MOCK
export REAL_MODEL_TEST_ENABLED=false
export OTEL_ENABLED=false
export QA_RANDOM_SEED=${QA_RANDOM_SEED:-20260908}
export QA_LANCEDB_DIR="$RUNTIME_ROOT/lancedb"

compose() {
  docker compose \
    -p "$COMPOSE_PROJECT" \
    -f "$REPOSITORY_ROOT/compose.yaml" \
    -f "$REPOSITORY_ROOT/qa/compose.qa.yaml" \
    "$@"
}

require_docker() {
  if ! docker info >/dev/null 2>&1; then
    printf '%s\n' "Docker 守护进程不可用，无法管理统一测试环境。" >&2
    exit 2
  fi
}

up() {
  require_docker
  [ "$RUNTIME_ROOT" = "$REPOSITORY_ROOT/.runtime/test-env" ] || exit 2
  compose down --volumes --remove-orphans
  rm -rf "$RUNTIME_ROOT"
  mkdir -p "$QA_LANCEDB_DIR"
  python3 "$REPOSITORY_ROOT/qa/scripts/init_test_data.py"
  compose up --detach --build --wait
}

up_contract() {
  require_docker
  [ "$RUNTIME_ROOT" = "$REPOSITORY_ROOT/.runtime/test-env" ] || exit 2
  compose down --volumes --remove-orphans
  rm -rf "$RUNTIME_ROOT"
  mkdir -p "$QA_LANCEDB_DIR"
  python3 "$REPOSITORY_ROOT/qa/scripts/init_test_data.py"
  compose up --detach --build --wait trip-agent-api
}

down() {
  require_docker
  compose down --volumes --remove-orphans
  [ "$RUNTIME_ROOT" = "$REPOSITORY_ROOT/.runtime/test-env" ] || exit 2
  rm -rf "$RUNTIME_ROOT"
}

case "$ACTION" in
  up)
    up
    ;;
  up-contract)
    up_contract
    ;;
  down)
    down
    ;;
  reset)
    down
    up
    ;;
  *)
    printf '%s\n' "用法: $0 {up|up-contract|down|reset}" >&2
    exit 2
    ;;
esac
