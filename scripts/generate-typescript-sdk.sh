#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CONTRACT="$REPOSITORY_ROOT/packages/api-contract/openapi.yaml"
OUTPUT="$REPOSITORY_ROOT/apps/web/src/api/generated"

exec npx --yes @openapitools/openapi-generator-cli@2.25.2 generate \
  -i "$CONTRACT" \
  -g typescript-fetch \
  -o "$OUTPUT" \
  --additional-properties='npmName=@tripagent/tripagent-v1-sdk,npmVersion=1.0.0,supportsES6=true,typescriptThreePlus=true,useSingleRequestParameter=true,withInterfaces=true' \
  --global-property='apis,models,supportingFiles'
