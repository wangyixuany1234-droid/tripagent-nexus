"""Shared limits and fail-closed scope for the local stage-eight harness."""

from __future__ import annotations

import json
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]


def load_config() -> dict:
    config = json.loads((ROOT / "qa/config/security-stage8.json").read_text())
    for key, value in config.items():
        if not isinstance(value, int):
            continue
        name = "QA_SECURITY_" + re.sub(r"(?<!^)(?=[A-Z])", "_", key).upper()
        config[key] = int(os.getenv(name, str(value)))
        if config[key] <= 0:
            raise ValueError(f"{name} must be positive")
    # Bounded safety ceilings cannot be disabled by environment overrides.
    if config["boundedAbuseRequests"] > 50 or config["concurrentTaskRequests"] > 8:
        raise ValueError("Security probes are not load tests")
    if (
        config["deepJsonLevels"] > 128
        or config["maxUploadProbeBytes"] > 12 * 1024 * 1024
    ):
        raise ValueError("Probe payload exceeds the non-destructive safety ceiling")
    if config["maxActiveScanSeconds"] > 180 or config["maxPassiveScanSeconds"] > 180:
        raise ValueError("ZAP scans must remain within the bounded local safety budget")
    return config


def require_loopback(url: str) -> None:
    parsed = urlsplit(url)
    if (
        parsed.scheme != "http"
        or parsed.hostname not in {"127.0.0.1", "::1"}
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
        or parsed.port is None
    ):
        raise ValueError("Stage 8 target must be an explicit loopback HTTP origin")


def write_records(output: Path, name: str, records: list[dict]) -> None:
    output.mkdir(parents=True, exist_ok=True)
    (output / f"{name}.json").write_text(
        json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    suite = ET.Element(
        "testsuite",
        name=name,
        tests=str(len(records)),
        failures=str(sum(r["status"] == "FAILED" for r in records)),
        errors=str(sum(r["status"] == "ERROR" for r in records)),
        skipped=str(
            sum(r["status"] in {"NOT_APPLICABLE", "NOT_EXECUTED"} for r in records)
        ),
    )
    for record in records:
        case = ET.SubElement(
            suite,
            "testcase",
            name=record["id"],
            time=str(record.get("durationMs", 0) / 1000),
        )
        status = record["status"]
        if status in {"NOT_APPLICABLE", "NOT_EXECUTED"}:
            ET.SubElement(case, "skipped", message=record.get("reason", status))
        elif status in {"FAILED", "ERROR"}:
            ET.SubElement(
                case,
                "failure" if status == "FAILED" else "error",
                message=record.get("expected", record.get("reason", status)),
            )
    ET.ElementTree(suite).write(
        output / f"{name}.xml", encoding="utf-8", xml_declaration=True
    )
