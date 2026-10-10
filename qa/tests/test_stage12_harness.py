import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

SPEC = importlib.util.spec_from_file_location(
    "stage12_runner", ROOT / "qa/scripts/run_stage12.py"
)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
CONFIG = json.loads(runner.CONFIG_PATH.read_text(encoding="utf-8"))


def test_trace_config_covers_every_requested_component():
    expected = {
        "frontend_request",
        "spring_boot",
        "kafka_producer",
        "kafka_consumer",
        "fastapi",
        "model_call",
        "rag",
        "neo4j",
        "lancedb",
        "mcp",
        "a2a",
        "rule_engine",
    }
    observed = {item["id"] for item in CONFIG["trace"]["requiredComponents"]}

    assert observed == expected
    assert runner.evaluate_trace(CONFIG)["status"] == "PASSED"


def test_alert_rules_have_required_annotations_and_exact_fault_matrix():
    alerts = runner.evaluate_alerts(CONFIG)
    configured = {item["id"] for item in alerts["alerts"]}

    assert alerts["status"] == "PASSED"
    assert configured == {
        "api_5xx_rise",
        "p95_latency_rise",
        "kafka_lag_growth",
        "db_pool_exhaustion",
        "model_429",
        "agent_failure_rate",
        "dead_letter_growth",
        "long_running_task",
        "disk_low",
    }
    for item in alerts["alerts"]:
        assert item["normalTrafficFalsePositive"] is False
        assert item["faultTriggers"] is True
        assert item["recoversAfterFault"] is True
        assert item["missingAnnotations"] == []


def test_metrics_report_separates_static_coverage_from_live_sources():
    metrics = runner.evaluate_metrics(CONFIG)
    partial = {
        item["id"]
        for item in metrics["signals"]
        if item["status"] == "PARTIAL_REAL_SOURCE_REQUIRED"
    }

    assert metrics["status"] == "PARTIAL_REAL_SOURCE_REQUIRED"
    assert partial >= {
        "python_cpu_memory_event_loop",
        "kafka_lag",
        "agent_success_rate",
        "model_latency_tokens_error_rate",
        "mcp_tool_success_rate",
        "task_backlog_long_running",
    }


def test_summary_blocks_real_validation_even_when_offline_checks_pass():
    summary = runner.build_summary(CONFIG)

    assert summary["traceValidation"]["status"] == "PASSED"
    assert summary["simulatedFaultInjection"]["status"] == "PASSED"
    assert summary["privacyValidation"]["status"] == "PASSED"
    assert summary["realFaultInjection"]["status"] == "NOT_EXECUTED"
    assert summary["gate"]["status"] == "BLOCKED_FOR_REAL_VALIDATION"
    assert {
        "real-fault-injection-not-executed",
        "developer-projection-neo4j",
        "developer-projection-lancedb",
        "developer-projection-rule_engine",
    } <= {item["id"] for item in summary["gaps"]}


def test_privacy_checks_scan_generated_stage12_assets():
    privacy = runner.evaluate_privacy(CONFIG)

    assert privacy["status"] == "PASSED"
    assert privacy["forbiddenPatternCount"] >= 5
    assert privacy["leakScan"]["leaks"] == []
