#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
CONTRACT="$REPOSITORY_ROOT/packages/api-contract/openapi.yaml"
COMMITTED="$REPOSITORY_ROOT/apps/web/src/api/generated/src"
TSC="$REPOSITORY_ROOT/apps/web/node_modules/.bin/tsc"
TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/tripagent-sdk-check.XXXXXX")
trap 'rm -rf "$TEMP_ROOT"' EXIT

npx --yes @openapitools/openapi-generator-cli@2.25.2 generate \
  -i "$CONTRACT" \
  -g typescript-fetch \
  -o "$TEMP_ROOT/generated" \
  --additional-properties='npmName=@tripagent/tripagent-v1-sdk,npmVersion=1.0.0,supportsES6=true,typescriptThreePlus=true,useSingleRequestParameter=true,withInterfaces=true' \
  --global-property='apis,models,supportingFiles' \
  >/dev/null

if [ ! -x "$TSC" ]; then
  printf '%s\n' "TypeScript 编译器不存在，请先在 apps/web 执行 npm ci。" >&2
  exit 2
fi

"$TSC" -p "$TEMP_ROOT/generated/tsconfig.json"
"$TSC" -p "$TEMP_ROOT/generated/tsconfig.esm.json"

if ! diff -ru "$COMMITTED" "$TEMP_ROOT/generated/src"; then
  printf '%s\n' "生成的 TypeScript SDK 与当前 OpenAPI 契约不一致。" >&2
  exit 1
fi
