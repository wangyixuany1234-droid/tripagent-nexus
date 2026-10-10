#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
CONTAINER=${SOAK_TARGET_CONTAINER:-tripagent-nexus-qa-trip-agent-api-1}
export QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
export QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
REPORT_DIR=$("$REPOSITORY_ROOT/qa/scripts/prepare_test_results.sh")
mkdir -p "$REPORT_DIR"

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  printf '%s\n' "长稳测试要求可观测容器：$CONTAINER" >&2
  exit 2
fi

memory_bytes() {
  docker exec "$CONTAINER" sh -c \
    'cat /sys/fs/cgroup/memory.current 2>/dev/null || cat /sys/fs/cgroup/memory/memory.usage_in_bytes'
}

BEFORE=$(memory_bytes)
"$REPOSITORY_ROOT/qa/scripts/run_performance.sh" soak
AFTER=$(memory_bytes)
MAX_GROWTH=$(
  python3 -c \
    'import json,sys; print(json.load(open(sys.argv[1]))["performance"]["soak"]["maxRssGrowthPercent"])' \
    "$REPOSITORY_ROOT/qa/config/thresholds.json"
)

python3 - "$BEFORE" "$AFTER" "$MAX_GROWTH" "$REPORT_DIR/performance/soak-memory.json" <<'PY'
import json
import sys

before, after = int(sys.argv[1]), int(sys.argv[2])
maximum = float(sys.argv[3])
growth = 0.0 if before == 0 else (after - before) * 100.0 / before
report = {
    "beforeBytes": before,
    "afterBytes": after,
    "growthPercent": round(growth, 3),
    "maximumGrowthPercent": maximum,
    "status": "passed" if growth <= maximum else "failed",
}
with open(sys.argv[4], "w", encoding="utf-8") as output:
    json.dump(report, output, ensure_ascii=False, indent=2)
    output.write("\n")
if growth > maximum:
    raise SystemExit(
        f"长稳测试内存增长 {growth:.2f}% 超过阈值 {maximum:.2f}%"
    )
PY
