#!/usr/bin/env sh
set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

export QA_RUN_ID=${QA_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}
export QA_RESULTS_ROOT=${QA_RESULTS_ROOT:-"$REPOSITORY_ROOT/output/test-results"}
export QA_E2E_CAPTURE_ALL=${QA_E2E_CAPTURE_ALL:-true}
export VITE_USER_AUTH_ENABLED=false

. "$REPOSITORY_ROOT/qa/scripts/test_env_defaults.sh"

RUN_DIR=$("$REPOSITORY_ROOT/qa/scripts/prepare_test_results.sh")
MATRIX_FILE="$RUN_DIR/tool-config/stage11-matrix.json"
ORIGINAL_HOME=${HOME:-}
QA_STAGE11_BROWSER_HOME=${QA_STAGE11_BROWSER_HOME:-"$REPOSITORY_ROOT/.runtime/stage11-playwright-home"}
QA_STAGE11_BROWSER_TMP=${QA_STAGE11_BROWSER_TMP:-"$REPOSITORY_ROOT/.runtime/stage11-playwright-tmp"}
mkdir -p "$QA_STAGE11_BROWSER_HOME" "$QA_STAGE11_BROWSER_TMP"

if [ -z "${PLAYWRIGHT_BROWSERS_PATH:-}" ] && [ -n "$ORIGINAL_HOME" ] && [ -d "$ORIGINAL_HOME/Library/Caches/ms-playwright" ]; then
  export PLAYWRIGHT_BROWSERS_PATH="$ORIGINAL_HOME/Library/Caches/ms-playwright"
fi

cat >"$MATRIX_FILE" <<'JSON'
{
  "browsers": ["chromium", "firefox", "webkit"],
  "viewports": [
    "375x667",
    "390x844",
    "768x1024",
    "1366x768",
    "1440x900",
    "1920x1080"
  ],
  "networkModes": [
    "normal",
    "slow-3g",
    "high-latency",
    "intermittent-offline",
    "sse-interruption",
    "duplicate-request",
    "request-timeout"
  ],
  "dataScale": {
    "singleDayItems": 30,
    "tripDays": 14,
    "conflicts": 50,
    "evidence": 100,
    "versions": 20,
    "longPlaceNames": true,
    "longRecommendationText": true
  },
  "mode": "MOCK_ROUTE_ISOLATION"
}
JSON

BUILD_EXIT=0
npm --prefix "$REPOSITORY_ROOT/apps/web" run build || BUILD_EXIT=$?

PLAYWRIGHT_EXIT=$BUILD_EXIT
if [ "$BUILD_EXIT" -eq 0 ]; then
  PLAYWRIGHT_EXIT=0
  (
    cd "$REPOSITORY_ROOT/apps/web"
    HOME="$QA_STAGE11_BROWSER_HOME" TMPDIR="$QA_STAGE11_BROWSER_TMP" \
      npx playwright test -c playwright.stage11.config.ts
  ) || PLAYWRIGHT_EXIT=$?
fi

python3 - "$RUN_DIR" "$BUILD_EXIT" "$PLAYWRIGHT_EXIT" <<'PY'
import json
import sys
from pathlib import Path

run_dir = Path(sys.argv[1])
build_exit = int(sys.argv[2])
playwright_exit = int(sys.argv[3])
result_file = run_dir / "json/stage11-playwright.json"

summary = {
    "schemaVersion": 1,
    "stage": 11,
    "mode": "MOCK_ROUTE_ISOLATION",
    "separateRealIntegrationRequired": True,
    "commands": [
        "npm --prefix apps/web run build",
        "npm --prefix apps/web exec -- playwright test -c playwright.stage11.config.ts",
    ],
    "results": {
        "buildExitCode": build_exit,
        "playwrightExitCode": playwright_exit,
        "passed": 0,
        "failed": 0,
        "skipped": 0,
        "flaky": 0,
        "interrupted": result_file.is_file() is False,
    },
    "coverage": {
        "browsers": ["chromium", "firefox", "webkit"],
        "viewports": [
            "375x667",
            "390x844",
            "768x1024",
            "1366x768",
            "1440x900",
            "1920x1080",
        ],
        "networkModes": [
            "normal",
            "slow-3g",
            "high-latency",
            "intermittent-offline",
            "sse-interruption",
            "duplicate-request",
            "request-timeout",
        ],
        "dataScale": {
            "singleDayItems": 30,
            "tripDays": 14,
            "conflicts": 50,
            "evidence": 100,
            "versions": 20,
            "longPlaceNames": True,
            "longRecommendationText": True,
        },
    },
    "gate": {
        "status": "PASSED" if build_exit == 0 and playwright_exit == 0 else "FAILED",
        "reason": "阶段十一 Mock 路由隔离矩阵完成。"
        if build_exit == 0 and playwright_exit == 0
        else "构建或 Playwright 阶段十一矩阵存在失败。",
    },
}

if result_file.is_file():
    data = json.loads(result_file.read_text(encoding="utf-8"))
    counts = {"passed": 0, "failed": 0, "skipped": 0, "flaky": 0}
    failed_tests = []
    environment_blocks = []

    def classify_test(test):
        status = test.get("status")
        if status in ("expected", "passed"):
            return "passed"
        if status == "flaky":
            return "flaky"
        if status in ("unexpected", "failed", "timedOut", "interrupted"):
            return "failed"
        if status == "skipped" or test.get("expectedStatus") == "skipped":
            return "skipped"

        result_statuses = [
            result.get("status") for result in test.get("results", [])
        ]
        if any(item in ("failed", "timedOut", "interrupted") for item in result_statuses):
            return "failed"
        if "passed" in result_statuses:
            return "passed"
        if "skipped" in result_statuses:
            return "skipped"
        return "skipped"

    def error_messages(test):
        messages = []
        for result in test.get("results", []):
            for error in result.get("errors", []):
                message = error.get("message") or error.get("value") or ""
                if message:
                    messages.append(message)
        return "\n".join(messages)

    def is_firefox_profile_block(test, message):
        project = test.get("projectName", "")
        return (
            project.startswith("firefox-")
            and "browserType.launch" in message
            and "Could not find profile folder" in message
        )

    def walk_suite(suite):
        for spec in suite.get("specs", []):
            for test in spec.get("tests", []):
                outcome = classify_test(test)
                counts[outcome] += 1
                if outcome == "failed":
                    message = error_messages(test)
                    failed_tests.append(
                        {
                            "project": test.get("projectName", "unknown"),
                            "title": spec.get("title", "unknown"),
                            "errorKind": message.splitlines()[0] if message else "unknown",
                        }
                    )
                    if is_firefox_profile_block(test, message):
                        environment_blocks.append(
                            {
                                "browser": "firefox",
                                "project": test.get("projectName", "unknown"),
                                "reason": "Playwright Firefox 无法访问或识别 profile 目录。",
                            }
                        )
        for child in suite.get("suites", []):
            walk_suite(child)

    for suite in data.get("suites", []):
        walk_suite(suite)
    summary["results"].update(counts)
    summary["results"]["interrupted"] = False
    summary["results"]["environmentBlocked"] = len(environment_blocks)
    summary["environmentBlocks"] = list(
        {item["project"]: item for item in environment_blocks}.values()
    )
    if failed_tests:
        summary["failedTests"] = failed_tests[:20]
    if (
        build_exit == 0
        and playwright_exit != 0
        and counts["failed"] > 0
        and counts["failed"] == len(environment_blocks)
    ):
        summary["gate"] = {
            "status": "BLOCKED_ENVIRONMENT",
            "reason": "Chromium/WebKit 阶段十一矩阵通过；Firefox 在当前 macOS/TRAE 沙箱中启动失败，未进入应用页面。",
        }

(run_dir / "json/stage11-summary.json").write_text(
    json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print(json.dumps(summary["gate"], ensure_ascii=False), flush=True)
PY

exit "$PLAYWRIGHT_EXIT"
