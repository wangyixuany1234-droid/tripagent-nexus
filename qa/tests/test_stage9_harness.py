import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "stage9_runner", ROOT / "qa/scripts/run_stage9.py"
)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


def full_records():
    records = [
        {"id": f"{name}::{i}", "status": "PASSED"}
        for name, count in runner.JAVA_CLASSES.items()
        for i in range(count)
    ]
    for fragment, count in (
        ("test_stage9_ai_security", 63),
        ("stage9-ai-security", 7),
        ("stage8-security", 2),
        ("stage9-security", 2),
        ("test_stage9_harness", 8),
    ):
        records.extend(
            {"id": f"{fragment}::{i}", "status": "PASSED"} for i in range(count)
        )
    return records


def full_batches():
    return [{"id": name, "status": "PASSED"} for name in runner.REQUIRED_BATCHES]


def test_complete_gate_and_empty_gate():
    assert runner.gate(full_records(), full_batches(), [])["status"] == "PASSED"
    assert runner.gate([], [], [])["status"] == "BLOCKED"


def test_failure_skip_gap_and_batch_error_never_green():
    for status in ("FAILED", "ERROR", "SKIPPED"):
        records = full_records()
        records[0]["status"] = status
        assert runner.gate(records, full_batches(), [])["status"] == "BLOCKED"
    batches = full_batches()
    batches[0]["status"] = "ERROR"
    assert runner.gate(full_records(), batches, [])["status"] == "BLOCKED"
    assert runner.gate(full_records(), [], [{"id": "gap"}])["status"] == "BLOCKED"
    assert runner.gate(full_records(), [], [])["status"] == "BLOCKED"


def test_missing_case_blocks_coverage():
    result = runner.gate(full_records()[1:], full_batches(), [])
    assert result["status"] == "BLOCKED"
    assert result["missingOrChangedCoverage"]
    records = full_records()
    records[1] = dict(records[0])
    assert (
        "duplicate-test-ids"
        in runner.gate(records, full_batches(), [])["missingOrChangedCoverage"]
    )


def test_corpus_is_synthetic_unique_and_bounded():
    corpus = json.loads(runner.CORPUS_PATH.read_text())
    assert corpus["syntheticOnly"] is True
    cases = [
        case
        for group in ("direct", "indirect", "benign", "urls")
        for case in corpus[group]
    ]
    assert len({case["id"] for case in cases}) == len(cases)
    assert all(0 < len(case["text"]) <= 2000 for case in cases)


def test_report_redacts_payloads_and_secrets(monkeypatch):
    monkeypatch.setenv("DEEPSEEK_API_KEY", "synthetic-secret-only")
    payload = json.loads(runner.CORPUS_PATH.read_text())["direct"][0]["text"]
    clean = runner.safe_text(payload + " synthetic-secret-only " + "x" * 1000)
    assert payload not in clean
    assert "synthetic-secret-only" not in clean
    assert "x" * 256 not in clean


def test_junit_preserves_failure_and_removes_environment(tmp_path):
    evidence = tmp_path / "junit.xml"
    evidence.write_text(
        '<testsuite><properties><property name="token" value="synthetic-secret"/></properties>'
        '<testcase classname="probe" name="bad"><failure message="rejected">detail</failure>'
        '<system-out>raw</system-out></testcase><testcase classname="probe" name="skip">'
        "<skipped/></testcase></testsuite>"
    )
    records = runner.junit_records([evidence], tmp_path)
    assert [case["status"] for case in records] == ["FAILED", "SKIPPED"]
    assert "synthetic-secret" not in evidence.read_text()
    assert "system-out" not in evidence.read_text()
    assert "detail" in evidence.read_text()
    evidence.write_text(
        '<testsuites><testsuite hostname="desktop"><testcase name="same" classname="web"/>'
        '</testsuite><testsuite hostname="mobile"><testcase name="same" classname="web"/>'
        "</testsuite></testsuites>"
    )
    records = runner.junit_records([evidence], tmp_path)
    assert len({case["id"] for case in records}) == 2


def test_malformed_junit_is_error(tmp_path):
    evidence = tmp_path / "bad.xml"
    evidence.write_text("<broken")
    assert runner.junit_records([evidence], tmp_path)[0]["status"] == "ERROR"


def test_fingerprint_is_repeatable():
    assert runner.source_fingerprint() == runner.source_fingerprint()
