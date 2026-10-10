#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
PROJECT="$REPOSITORY_ROOT/services/ai-retrieval-service"
PYTHON="$PROJECT/.venv/bin/python"
UV=${UV_BIN:-"$PROJECT/.venv/bin/uv"}
OUTPUT=${1:-"$REPOSITORY_ROOT/output/test-results/python-audit.json"}
TEMP=$(mktemp "${TMPDIR:-/tmp}/tripagent-requirements.XXXXXX")
trap 'rm -f "$TEMP"' EXIT

if [ ! -x "$UV" ]; then
  UV=$(command -v uv || true)
fi
if [ -z "$UV" ]; then
  printf '%s\n' "缺少 uv，无法从锁文件导出生产依赖。" >&2
  exit 2
fi

"$UV" export \
  --project "$PROJECT" \
  --format requirements.txt \
  --no-dev \
  --no-emit-project \
  --no-header \
  --output-file "$TEMP" \
  >/dev/null

"$PYTHON" -m pip_audit \
  --requirement "$TEMP" \
  --format json \
  --output "$OUTPUT"
