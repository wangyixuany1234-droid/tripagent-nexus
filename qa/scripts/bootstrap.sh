#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
PYTHON="$REPOSITORY_ROOT/services/ai-retrieval-service/.venv/bin/python"

if [ ! -x "$PYTHON" ]; then
  python3.12 -m venv "$REPOSITORY_ROOT/services/ai-retrieval-service/.venv"
fi

"$PYTHON" -m ensurepip --upgrade
"$PYTHON" -m pip install --disable-pip-version-check \
  "pip==26.2.1" \
  "uv==0.11.15"
"$PYTHON" -m pip install --disable-pip-version-check \
  -e "$REPOSITORY_ROOT/services/ai-retrieval-service[dev]"
npm --prefix "$REPOSITORY_ROOT/apps/web" ci
npx --prefix "$REPOSITORY_ROOT/apps/web" playwright install chromium

printf '%s\n' "本地测试依赖已安装。安全扫描器可由 qa/scripts/run_security.sh 通过 Docker 固定版本运行。"
