#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$REPOSITORY_ROOT/qa/scripts/run_with_report.py"
PYTHON="$REPOSITORY_ROOT/services/ai-retrieval-service/.venv/bin/python"
THRESHOLDS="$REPOSITORY_ROOT/qa/config/thresholds.json"

export QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
export QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
. "$REPOSITORY_ROOT/qa/scripts/test_env_defaults.sh"
REPORT_DIR=$("$REPOSITORY_ROOT/qa/scripts/prepare_test_results.sh")
export ASYNCAPI_METRICS_CONFIG_PATH="$REPORT_DIR/tool-config/asyncapi-analytics"
export CI=${CI:-true}
export REDOCLY_TELEMETRY=off

python3 "$RUNNER" --suite test-profile-isolation -- \
  python3 "$REPOSITORY_ROOT/qa/scripts/validate_test_environment.py" \
  --report "$REPORT_DIR/json/test-profile-isolation-details.json"

python3 "$RUNNER" --suite controller-openapi-parity -- \
  mvn -B -ntp -pl apps/trip-agent-api -am test \
  -Dtest=OpenApiContractTest,ControllerOpenApiParityTest,ErrorCodeDocumentationTest,RuntimeOpenApiContractTest,ProtocolJsonSchemaContractTest,AsyncResilienceTest \
  -Dsurefire.reportsDirectory="$REPORT_DIR/junit/java-contract"

python3 "$RUNNER" --suite openapi-breaking-changes -- \
  "$PYTHON" "$REPOSITORY_ROOT/qa/scripts/check_openapi_compatibility.py" \
  --baseline "$REPOSITORY_ROOT/contracts/openapi/tripagent-v1.yaml" \
  --candidate "$REPOSITORY_ROOT/packages/api-contract/openapi.yaml" \
  --output "$REPORT_DIR/json/openapi-compatibility-details.json"

python3 "$RUNNER" --suite openapi-compatibility-regression -- \
  "$PYTHON" -m pytest -q \
  --junitxml="$REPORT_DIR/junit/openapi-compatibility.xml" \
  "$REPOSITORY_ROOT/qa/tests/test_openapi_compatibility.py"

python3 "$RUNNER" --suite openapi-public -- \
  npx --yes @redocly/cli@1.34.5 lint \
  "$REPOSITORY_ROOT/contracts/openapi/tripagent-v1.yaml"

python3 "$RUNNER" --suite openapi-current -- \
  npx --yes @redocly/cli@1.34.5 lint \
  "$REPOSITORY_ROOT/packages/api-contract/openapi.yaml"

python3 "$RUNNER" --suite openapi-internal -- \
  npx --yes @redocly/cli@1.34.5 lint \
  "$REPOSITORY_ROOT/packages/api-contract/ai-internal-openapi.yaml"

python3 "$RUNNER" --suite asyncapi -- \
  npx --yes @asyncapi/cli@3.4.2 validate \
  "$REPOSITORY_ROOT/packages/api-contract/asyncapi.yaml" \
  --fail-severity error \
  --suppressWarnings asyncapi2-message-messageId

python3 "$RUNNER" --suite fastapi-contract -- \
  "$PYTHON" -m pytest -q \
  --junitxml="$REPORT_DIR/junit/fastapi-contract.xml" \
  "$REPOSITORY_ROOT/services/ai-retrieval-service/tests/test_openapi_runtime_contract.py" \
  "$REPOSITORY_ROOT/services/ai-retrieval-service/tests/test_protocol_contracts.py"

python3 "$RUNNER" --suite typescript-sdk-drift -- \
  "$REPOSITORY_ROOT/qa/scripts/check_sdk_drift.sh"

if [ "${RUN_CONTRACT_FUZZ:-false}" != "true" ]; then
  printf '%s\n' "动态契约测试未启用；设置 RUN_CONTRACT_FUZZ=true 并启动本地服务后执行。"
  exit 0
fi

BASE_URL=${CONTRACT_BASE_URL:-http://127.0.0.1:8080}
INTERNAL_BASE_URL=${CONTRACT_INTERNAL_BASE_URL:-http://127.0.0.1:8000}
python3 "$REPOSITORY_ROOT/qa/scripts/guard_environment.py" \
  --kind security \
  --base-url "$BASE_URL"
python3 "$REPOSITORY_ROOT/qa/scripts/guard_environment.py" \
  --kind security \
  --base-url "$INTERNAL_BASE_URL"

python3 "$RUNNER" --suite runtime-http-contract -- \
  "$PYTHON" -m pytest -q \
  --junitxml="$REPORT_DIR/junit/runtime-http-contract.xml" \
  "$REPOSITORY_ROOT/qa/tests/test_runtime_http_contract.py"

MAX_EXAMPLES=$(
  "$PYTHON" -c \
    'import json,sys; print(json.load(open(sys.argv[1]))["contract"]["maxExamples"])' \
    "$THRESHOLDS"
)

python3 "$RUNNER" --suite schemathesis-public -- \
  "$REPOSITORY_ROOT/services/ai-retrieval-service/.venv/bin/schemathesis" run \
  "$REPOSITORY_ROOT/packages/api-contract/openapi.yaml" \
  --url "$BASE_URL" \
  --checks not_a_server_error,status_code_conformance,content_type_conformance,response_schema_conformance,negative_data_rejection,missing_required_header \
  --exclude-operation-id streamTaskEvents \
  --phases coverage,fuzzing \
  --max-examples "$MAX_EXAMPLES" \
  --workers 1 \
  --seed "$QA_RANDOM_SEED" \
  --generation-deterministic \
  --output-sanitize true \
  --report junit \
  --report-junit-path \
  "$REPORT_DIR/junit/schemathesis.xml"

python3 "$RUNNER" --suite schemathesis-public-sse -- \
  "$REPOSITORY_ROOT/services/ai-retrieval-service/.venv/bin/schemathesis" run \
  "$REPOSITORY_ROOT/packages/api-contract/openapi.yaml" \
  --url "$BASE_URL" \
  --checks not_a_server_error,status_code_conformance,content_type_conformance,negative_data_rejection,missing_required_header \
  --include-operation-id streamTaskEvents \
  --phases coverage,fuzzing \
  --max-examples "$MAX_EXAMPLES" \
  --workers 1 \
  --seed "$QA_RANDOM_SEED" \
  --generation-deterministic \
  --output-sanitize true \
  --report junit \
  --report-junit-path \
  "$REPORT_DIR/junit/schemathesis-sse.xml"

python3 "$RUNNER" --suite schemathesis-internal -- \
  "$REPOSITORY_ROOT/services/ai-retrieval-service/.venv/bin/schemathesis" run \
  "$REPOSITORY_ROOT/packages/api-contract/ai-internal-openapi.yaml" \
  --url "$INTERNAL_BASE_URL" \
  --header "Authorization: Bearer test-service-token" \
  --checks not_a_server_error,status_code_conformance,content_type_conformance,response_schema_conformance,negative_data_rejection,missing_required_header \
  --phases coverage,fuzzing \
  --max-examples "$MAX_EXAMPLES" \
  --workers 1 \
  --seed "$QA_RANDOM_SEED" \
  --generation-deterministic \
  --output-sanitize true \
  --report junit \
  --report-junit-path \
  "$REPORT_DIR/junit/schemathesis-internal.xml"
