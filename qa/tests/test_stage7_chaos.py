"""Regression tests for truthful chaos evidence and environment guards."""

import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


evidence = load("stage7_evidence", "qa/chaos/evidence.py")
reporter = load("stage7_reporter", "qa/scripts/summarize_stage7.py")
runner = load("stage7_runner", "qa/scripts/run_stage7.py")


class ChaosHarnessTest(unittest.TestCase):
    def test_catalog_has_forty_unique_user_scenarios(self):
        ids = [item["id"] for item in evidence.CONFIG["scenarios"]]
        self.assertEqual(len(ids), 40)
        self.assertEqual(len(set(ids)), 40)

    def test_threshold_supports_environment_override(self):
        with patch.dict(os.environ, {"QA_CHAOS_CALL_TIMEOUT_MS": "321"}):
            self.assertEqual(evidence.threshold("callTimeoutMs"), 321)

    def test_negative_threshold_is_rejected(self):
        with (
            patch.dict(os.environ, {"QA_CHAOS_CALL_TIMEOUT_MS": "-1"}),
            self.assertRaises(ValueError),
        ):
            evidence.threshold("callTimeoutMs")

    def test_missing_timeline_fails_and_records_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            with (
                patch.dict(os.environ, {"QA_CHAOS_OUTPUT": temporary}),
                self.assertRaises(ValueError),
                evidence.experiment("incomplete", "TEST", "test"),
            ):
                pass
            record = json.loads(next(Path(temporary).glob("*.json")).read_text())
            self.assertEqual(record["status"], "FAILED")
            self.assertIsNone(record["lostTasks"])
            self.assertIsNone(record["duplicateTasks"])
            self.assertEqual(record["traceIds"], [])

    def test_retry_preserves_failed_attempt(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.dict(os.environ, {"QA_CHAOS_OUTPUT": temporary}):
                for _ in range(2):
                    with (
                        self.assertRaises(AssertionError),
                        evidence.experiment("failure", "TEST", "test"),
                    ):
                        raise AssertionError("synthetic")
            self.assertEqual(len(list(Path(temporary).glob("*.json"))), 2)

    def test_no_evidence_is_not_executed_not_passed(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = reporter.summarize(Path(temporary))
            self.assertEqual(report["notExecuted"], 40)
            self.assertEqual(report["passedScopedExperiments"], 0)

    def test_weaker_stub_cannot_mask_transport_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "experiments"
            directory.mkdir()
            for mode, status, timestamp in [
                ("OFFICIAL_MCP_HTTP_STUB_PROVIDER", "FAILED", "2026-01-01"),
                ("GATEWAY_STUB", "PASSED", "2026-01-02"),
            ]:
                (directory / f"{mode}.json").write_text(
                    json.dumps(
                        {
                            "id": "mcp.schema",
                            "injectionMode": mode,
                            "status": status,
                            "finishedAt": timestamp,
                        }
                    )
                )
            report = reporter.summarize(Path(temporary))
            scenario = next(
                item for item in report["scenarios"] if item["id"] == "mcp.schema"
            )
            self.assertEqual(scenario["latest"]["status"], "FAILED")
            self.assertEqual(scenario["attemptCount"], 2)

    def test_remote_docker_is_rejected_without_authorization(self):
        with self.assertRaises(RuntimeError):
            runner.require_local_docker(
                {"DOCKER_HOST": "tcp://unauthorized.example:2375"}
            )

    def test_resume_acceptance_uses_both_latest_process_results(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "experiments"
            directory.mkdir()
            for identifier, timestamp, status in [
                ("a2a.resume.ResearchAgent", "2026-01-01", "PASSED"),
                ("a2a.resume.AccommodationAgent", "2026-01-01", "PASSED"),
                ("a2a.resume.ResearchAgent", "2026-01-02", "FAILED"),
            ]:
                (directory / f"{identifier}-{timestamp}.json").write_text(json.dumps({
                    "id": identifier, "injectionMode": "PROCESS_KILL_DURING_AGENT",
                    "status": status, "finishedAt": timestamp,
                }))
            summary = reporter.summarize(Path(temporary))
            self.assertEqual(summary["agentResume"]["status"], "NOT_ACCEPTED")
            self.assertEqual(summary["additionalFailures"], 1)


if __name__ == "__main__":
    unittest.main()
