#!/usr/bin/env python3
"""Stage 12 offline observability and alerting acceptance runner."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
CONFIG_PATH = ROOT / "qa/config/stage12-observability.json"
ALERT_RULE_PATH = ROOT / "infrastructure/prometheus/rules/tripagent-stage12-alerts.yml"
OTEL_COLLECTOR_PATH = ROOT / "infrastructure/otel-collector/config.yaml"
COMPOSE_PATH = ROOT / "compose.yaml"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    args = parser.parse_args()
    _validate_run_id(args.run, parser)

    output = ROOT / "output/test-results" / args.run
    if output.exists():
        parser.error("Evidence directory exists; use a new run ID")
    for folder in ("json", "logs", "artifacts"):
        (output / folder).mkdir(parents=True)

    config = load_config()
    summary = build_summary(config)
    summary["runId"] = args.run
    summary["generatedAt"] = datetime.now(timezone.utc).isoformat()
    summary["sourceSha256"] = source_fingerprint(config)

    write_json(output / "json/stage12-summary.json", summary)
    write_json(output / "json/stage12-alert-simulation.json", summary["simulatedFaultInjection"])
    (output / "logs/stage12-offline.log").write_text(
        "Stage 12 offline observability validation completed.\n",
        encoding="utf-8",
    )
    print(json.dumps(summary["gate"], ensure_ascii=False), flush=True)
    return 0 if summary["gate"]["status"] == "PASSED" else 1


def load_config(path: Path = CONFIG_PATH) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def build_summary(config: dict[str, Any]) -> dict[str, Any]:
    trace = evaluate_trace(config)
    metrics = evaluate_metrics(config)
    alerts = evaluate_alerts(config)
    privacy = evaluate_privacy(config)
    otel = evaluate_otel_collector()
    gaps = build_gaps(config, trace, metrics, alerts, privacy, otel)
    static_failed = any(
        section["status"] == "FAILED" for section in (trace, alerts, privacy, otel)
    )
    metric_failed = any(item["status"] == "FAILED" for item in metrics["signals"])
    if static_failed or metric_failed:
        gate = {
            "status": "FAILED",
            "reason": "Offline observability acceptance found missing required configuration or evidence.",
            "gapCount": len(gaps),
        }
    elif gaps:
        gate = {
            "status": "BLOCKED_FOR_REAL_VALIDATION",
            "reason": "Offline evidence and simulated alerts passed, but live traffic, exporter, and Alertmanager validation are not complete.",
            "gapCount": len(gaps),
        }
    else:
        gate = {
            "status": "PASSED",
            "reason": "Offline and live observability validation completed.",
            "gapCount": 0,
        }
    return {
        "schemaVersion": 1,
        "stage": 12,
        "configVersion": config["version"],
        "mode": config["mode"],
        "classification": config["classification"],
        "otelCollector": otel,
        "traceValidation": trace,
        "metricsValidation": metrics,
        "simulatedFaultInjection": alerts,
        "realFaultInjection": {
            "status": config["realValidation"]["status"],
            "qualityClaim": "NOT_REPLACED_BY_SIMULATION",
            "requiredControls": config["realValidation"]["requiredControls"],
            "requiredScenarios": config["realValidation"]["requiredScenarios"],
        },
        "privacyValidation": privacy,
        "gaps": gaps,
        "gate": gate,
    }


def evaluate_trace(config: dict[str, Any]) -> dict[str, Any]:
    component_results = []
    projection_gaps = []
    for component in config["trace"]["requiredComponents"]:
        result = evidence_group(component["evidence"])
        projection = component["developerTraceProjection"]
        component_result = {
            "id": component["id"],
            "displayName": component["displayName"],
            "status": result["status"],
            "developerTraceProjection": projection,
            "evidence": result["evidence"],
        }
        if projection == "RAW_SPAN_ONLY":
            projection_gaps.append(
                {
                    "id": f"developer-projection-{component['id']}",
                    "status": "RAW_SPAN_ONLY",
                    "reason": "Jaeger raw spans exist, but DeveloperTraceService does not expose a dedicated category for this component.",
                }
            )
        component_results.append(component_result)

    propagation = [
        {
            "id": item["id"],
            "status": evidence_group(item["evidence"])["status"],
            "mode": item.get("statusIfCovered", "DIRECT"),
            "evidence": evidence_group(item["evidence"])["evidence"],
        }
        for item in config["trace"]["propagation"]
    ]
    error_spans = [
        {
            "id": item["id"],
            "status": evidence_group(item["evidence"])["status"],
            "evidence": evidence_group(item["evidence"])["evidence"],
        }
        for item in config["trace"]["errorSpan"]
    ]
    status = (
        "PASSED"
        if all(item["status"] == "PASSED" for item in component_results)
        and all(item["status"] == "PASSED" for item in propagation)
        and all(item["status"] == "PASSED" for item in error_spans)
        else "FAILED"
    )
    return {
        "status": status,
        "components": component_results,
        "propagation": propagation,
        "errorSpans": error_spans,
        "developerProjectionGaps": projection_gaps,
    }


def evaluate_metrics(config: dict[str, Any]) -> dict[str, Any]:
    signals = []
    for signal in config["metrics"]["requiredSignals"]:
        evidence = evidence_group(signal["evidence"])
        if evidence["status"] != "PASSED":
            status = "FAILED"
        elif signal.get("knownGap"):
            status = "PARTIAL_REAL_SOURCE_REQUIRED"
        else:
            status = "STATIC_COVERED"
        signals.append(
            {
                "id": signal["id"],
                "displayName": signal["displayName"],
                "status": status,
                "metricNames": signal["metricNames"],
                "knownGap": signal.get("knownGap"),
                "evidence": evidence["evidence"],
            }
        )
    return {
        "status": "FAILED"
        if any(item["status"] == "FAILED" for item in signals)
        else "PARTIAL_REAL_SOURCE_REQUIRED"
        if any(item["status"] == "PARTIAL_REAL_SOURCE_REQUIRED" for item in signals)
        else "PASSED",
        "signals": signals,
    }


def evaluate_alerts(config: dict[str, Any]) -> dict[str, Any]:
    rule_path = ROOT / config["alerts"]["ruleFile"]
    rule_text = read_text(rule_path)
    blocks = parse_alert_blocks(rule_text)
    results = []
    for fault in config["alerts"]["faults"]:
        block = blocks.get(fault["alertName"], "")
        annotation_missing = [
            key
            for key in config["alerts"]["requiredAnnotationKeys"]
            if not re.search(rf"(?m)^          {re.escape(key)}:", block)
        ]
        normal_triggers = triggers(
            float(fault["normalValue"]), fault["operator"], float(fault["threshold"])
        )
        fault_triggers = triggers(
            float(fault["faultValue"]), fault["operator"], float(fault["threshold"])
        )
        for_minutes = alert_for_minutes(block)
        status = (
            "SIMULATED_PASS"
            if block
            and "expr:" in block
            and not annotation_missing
            and not normal_triggers
            and fault_triggers
            and for_minutes is not None
            and for_minutes <= int(fault["targetMinutes"])
            else "FAILED"
        )
        results.append(
            {
                "id": fault["id"],
                "alertName": fault["alertName"],
                "status": status,
                "normalTrafficFalsePositive": normal_triggers,
                "faultTriggers": fault_triggers,
                "recoversAfterFault": not normal_triggers,
                "targetMinutes": fault["targetMinutes"],
                "configuredForMinutes": for_minutes,
                "missingAnnotations": annotation_missing,
                "rulePresent": bool(block),
            }
        )
    return {
        "status": "PASSED"
        if all(item["status"] == "SIMULATED_PASS" for item in results)
        else "FAILED",
        "mode": "SIMULATED_THRESHOLD_AND_RULE_SHAPE_ONLY",
        "ruleFile": config["alerts"]["ruleFile"],
        "alerts": results,
    }


def evaluate_privacy(config: dict[str, Any]) -> dict[str, Any]:
    evidence = evidence_group(config["privacy"]["evidence"])
    leak_scan_paths = [
        CONFIG_PATH,
        ALERT_RULE_PATH,
        ROOT / "qa/scripts/run_stage12.py",
    ]
    leaks = []
    for path in leak_scan_paths:
        text = read_text(path)
        for pattern in config["privacy"]["forbiddenPatterns"]:
            if re.search(pattern, text):
                leaks.append({"path": relative(path), "pattern": pattern})
    status = "PASSED" if evidence["status"] == "PASSED" and not leaks else "FAILED"
    return {
        "status": status,
        "evidence": evidence["evidence"],
        "leakScan": {
            "paths": [relative(path) for path in leak_scan_paths],
            "leaks": leaks,
        },
        "forbiddenPatternCount": len(config["privacy"]["forbiddenPatterns"]),
    }


def evaluate_otel_collector() -> dict[str, Any]:
    collector = read_text(OTEL_COLLECTOR_PATH)
    compose = read_text(COMPOSE_PATH)
    checks = [
        {
            "id": "otlp_receiver",
            "status": contains_all(collector, ["receivers:", "otlp:", "grpc:", "http:"]),
        },
        {
            "id": "trace_pipeline",
            "status": contains_all(collector, ["traces:", "otlp/jaeger"]),
        },
        {
            "id": "metrics_pipeline",
            "status": contains_all(collector, ["metrics:", "exporters: [prometheus, debug]"]),
        },
        {
            "id": "prometheus_exporter_port",
            "status": contains_all(collector, ["prometheus:", "0.0.0.0:9464"])
            and "9464}:9464" in compose,
        },
    ]
    return {
        "status": "PASSED" if all(item["status"] for item in checks) else "FAILED",
        "checks": checks,
    }


def evidence_group(entries: list[dict[str, Any]]) -> dict[str, Any]:
    results = []
    for entry in entries:
        path = ROOT / entry["path"]
        text = read_text(path)
        missing = [needle for needle in entry["needles"] if needle not in text]
        results.append(
            {
                "path": entry["path"],
                "status": "PASSED" if not missing and path.is_file() else "FAILED",
                "missingNeedles": missing,
            }
        )
    return {
        "status": "PASSED" if all(item["status"] == "PASSED" for item in results) else "FAILED",
        "evidence": results,
    }


def parse_alert_blocks(rule_text: str) -> dict[str, str]:
    blocks: dict[str, str] = {}
    pattern = re.compile(r"(?ms)^      - alert:\s*([A-Za-z0-9_]+)\n(.*?)(?=^      - alert:|\Z)")
    for match in pattern.finditer(rule_text):
        blocks[match.group(1)] = match.group(0)
    return blocks


def alert_for_minutes(block: str) -> int | None:
    match = re.search(r"(?m)^        for:\s*(\d+)m\s*$", block)
    return int(match.group(1)) if match else None


def triggers(value: float, operator: str, threshold: float) -> bool:
    if operator == ">":
        return value > threshold
    if operator == "<":
        return value < threshold
    raise ValueError(f"unsupported operator: {operator}")


def build_gaps(
    config: dict[str, Any],
    trace: dict[str, Any],
    metrics: dict[str, Any],
    alerts: dict[str, Any],
    privacy: dict[str, Any],
    otel: dict[str, Any],
) -> list[dict[str, str]]:
    gaps: list[dict[str, str]] = []
    if otel["status"] != "PASSED":
        gaps.append(
            {
                "id": "otel-collector-metrics-or-traces-incomplete",
                "status": "FAILED",
                "reason": "OTel collector trace or metrics pipeline is incomplete.",
            }
        )
    if trace["status"] != "PASSED":
        gaps.append(
            {
                "id": "trace-static-coverage-incomplete",
                "status": "FAILED",
                "reason": "At least one required trace component or propagation check is missing.",
            }
        )
    for gap in trace["developerProjectionGaps"]:
        gaps.append(gap)
    for signal in metrics["signals"]:
        if signal["status"] == "FAILED":
            gaps.append(
                {
                    "id": f"metric-{signal['id']}",
                    "status": "FAILED",
                    "reason": "Required metric evidence is missing.",
                }
            )
        elif signal["status"] == "PARTIAL_REAL_SOURCE_REQUIRED":
            gaps.append(
                {
                    "id": f"metric-{signal['id']}",
                    "status": "PENDING_REAL_SOURCE",
                    "reason": signal["knownGap"] or "Live metric source must be verified.",
                }
            )
    if alerts["status"] != "PASSED":
        gaps.append(
            {
                "id": "alert-rule-simulation-incomplete",
                "status": "FAILED",
                "reason": "At least one alert rule failed rule-shape or synthetic threshold validation.",
            }
        )
    if privacy["status"] != "PASSED":
        gaps.append(
            {
                "id": "observability-privacy-incomplete",
                "status": "FAILED",
                "reason": "Privacy redaction evidence is missing or generated assets contain forbidden canaries.",
            }
        )
    if config["realValidation"]["status"] != "PASSED":
        gaps.append(
            {
                "id": "real-fault-injection-not-executed",
                "status": config["realValidation"]["status"],
                "reason": "Live Prometheus/Alertmanager fault injection and recovery validation must run in a separate controlled window.",
            }
        )
    return gaps


def source_fingerprint(config: dict[str, Any]) -> str:
    paths = {
        CONFIG_PATH,
        ALERT_RULE_PATH,
        OTEL_COLLECTOR_PATH,
        COMPOSE_PATH,
        ROOT / "qa/config/service-inventory.json",
        Path(__file__),
    }
    for section in (
        config["trace"]["requiredComponents"],
        config["trace"]["propagation"],
        config["trace"]["errorSpan"],
        config["metrics"]["requiredSignals"],
        [{"evidence": config["privacy"]["evidence"]}],
    ):
        for item in section:
            for evidence in item["evidence"]:
                paths.add(ROOT / evidence["path"])
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(relative(path).encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""


def contains_all(text: str, needles: list[str]) -> bool:
    return all(needle in text for needle in needles)


def relative(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _validate_run_id(value: str, parser: argparse.ArgumentParser) -> None:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,100}", value) or value in {
        ".",
        "..",
    }:
        parser.error("--run must be a safe directory name")


if __name__ == "__main__":
    raise SystemExit(main())
