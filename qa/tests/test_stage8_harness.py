import json
import sys
import xml.etree.ElementTree as ET

import pytest

from qa.scripts.run_stage8_suite import run_batch
from qa.scripts.summarize_stage8 import evaluate_release
from qa.security.stage8_support import load_config, require_loopback, write_records


@pytest.mark.parametrize(
    "url",
    [
        "https://example.test:443",
        "http://localhost:8080",
        "http://127.0.0.1",
        "http://127.0.0.1:8080@remote.test:8080",
        "http://2130706433:8080",
        "http://127.0.0.1:8080/path",
        "file:///tmp/input",
    ],
)
def test_scan_target_rejects_non_loopback_origins(url):
    with pytest.raises(ValueError):
        require_loopback(url)


def test_scan_target_accepts_explicit_loopback():
    require_loopback("http://127.0.0.1:8080")
    require_loopback("http://[::1]:8080")


def test_threshold_overrides_are_bounded(monkeypatch):
    monkeypatch.setenv("QA_SECURITY_BOUNDED_ABUSE_REQUESTS", "7")
    assert load_config()["boundedAbuseRequests"] == 7
    monkeypatch.setenv("QA_SECURITY_BOUNDED_ABUSE_REQUESTS", "999")
    with pytest.raises(ValueError):
        load_config()


def test_junit_counts_failures_errors_and_unexecuted(tmp_path):
    records = [
        {"id": status, "status": status}
        for status in ("PASSED", "FAILED", "ERROR", "NOT_EXECUTED", "NOT_APPLICABLE")
    ]
    write_records(tmp_path, "test", records)
    root = ET.parse(tmp_path / "test.xml").getroot()
    assert root.attrib["tests"] == "5"
    assert root.attrib["failures"] == "1"
    assert root.attrib["errors"] == "1"
    assert root.attrib["skipped"] == "2"
    assert json.loads((tmp_path / "test.json").read_text()) == records


def test_missing_history_and_image_evidence_blocks_even_without_findings():
    gate = evaluate_release(
        [], {"history": False, "images": False}, [], load_config()["releaseGate"]
    )
    assert gate["status"] == "BLOCKED"
    assert len(gate["reasons"]) == 2


def test_medium_plan_is_not_an_approved_acceptance():
    finding = {
        "id": "synthetic",
        "severity": "Medium",
        "status": "OPEN",
        "disposition": "FIX_REQUIRED",
    }
    assert (
        evaluate_release([finding], {}, [], load_config()["releaseGate"])["status"]
        == "BLOCKED"
    )
    finding.update(
        disposition="ACCEPTED",
        acceptanceRecord="SYNTHETIC-RECORD",
        approvedBy="test-only",
    )
    assert (
        evaluate_release([finding], {}, [], load_config()["releaseGate"])["status"]
        == "PASSED"
    )


def test_dependency_critical_blocks_without_claiming_exploitation():
    gate = evaluate_release(
        [], {}, [{"severity": "CRITICAL"}], load_config()["releaseGate"]
    )
    assert gate["status"] == "BLOCKED"
    assert gate["applicationOpenCounts"] == {}


def test_latest_scanner_failure_cannot_be_hidden_by_older_batch_success(
    tmp_path, monkeypatch
):
    from qa.scripts import summarize_stage8

    monkeypatch.setattr(summarize_stage8, "ROOT", tmp_path)
    triage = tmp_path / "qa/security"
    triage.mkdir(parents=True)
    (triage / "stage8-findings.json").write_text('{"findings":[],"triage":[]}')
    old, new = tmp_path / "old", tmp_path / "new"
    for directory in (old, new):
        (directory / "json").mkdir(parents=True)
        (directory / "json/gitleaks-current-findings.json").write_text("[]")
    (old / "json/stage8-batches.json").write_text(
        '[{"id":"gitleaks-current","status":"PASSED"}]'
    )
    (new / "json/gitleaks-current.json").write_text('{"exitCode":1}')
    summary = summarize_stage8.summarize([old, new])
    assert summary["releaseGate"]["requiredEvidence"]["currentGitleaks"] is False


def test_batch_failure_does_not_prevent_next_batch_and_logs_are_redacted(
    tmp_path, monkeypatch
):
    # Use the real report directory because run_batch records repository-relative paths.
    import tempfile

    from qa.security.stage8_support import ROOT

    token = "SYNTHETIC-HARNESS-NOT-A-CREDENTIAL"
    monkeypatch.setenv("AI_SERVICE_TOKEN", token)
    with tempfile.TemporaryDirectory(
        dir=ROOT / ".runtime", prefix="stage8-harness-"
    ) as temporary:
        from pathlib import Path

        output = Path(temporary)
        (output / "logs").mkdir()
        first = run_batch(
            "failure", [sys.executable, "-c", "raise SystemExit(1)"], output, {}, 5
        )
        second = run_batch(
            "next",
            [sys.executable, "-c", "print('authorization: Bearer " + token + "')"],
            output,
            {},
            5,
        )
        assert first["status"] == "FAILED"
        assert second["status"] == "PASSED"
        assert token not in (output / "logs/next.log").read_text()
