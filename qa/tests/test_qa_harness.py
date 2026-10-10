import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = REPOSITORY_ROOT / "qa/scripts/run_with_report.py"
GUARD_PATH = REPOSITORY_ROOT / "qa/scripts/guard_environment.py"
FIXTURE_INITIALIZER_PATH = REPOSITORY_ROOT / "qa/scripts/init_test_data.py"
TEST_ENV_VALIDATOR_PATH = REPOSITORY_ROOT / "qa/scripts/validate_test_environment.py"
COVERAGE_GATE_PATH = REPOSITORY_ROOT / "qa/scripts/check_backend_functional_coverage.py"
PYTHON_COVERAGE_GATE_PATH = REPOSITORY_ROOT / "qa/scripts/check_python_coverage.py"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载测试模块：{path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class QaHarnessTest(unittest.TestCase):
    def test_qa_compose_enables_runtime_components_disabled_by_test_profile(
        self,
    ) -> None:
        validator = load_module(
            "qa_runtime_override_validator",
            TEST_ENV_VALIDATOR_PATH,
        )

        with mock.patch.dict(os.environ, {}, clear=True):
            report = validator.validate()

        self.assertEqual(
            set(report["runtimeOverrides"]),
            validator.REQUIRED_QA_RUNTIME_OVERRIDES,
        )

    def test_coverage_patterns_do_not_cross_package_boundaries(self) -> None:
        coverage_gate = load_module("qa_coverage_gate", COVERAGE_GATE_PATH)
        controller_pattern = ["com/tripagent/nexus/controller/*.java"]

        self.assertTrue(
            coverage_gate.matches(
                "com/tripagent/nexus/controller/TripController.java",
                controller_pattern,
            )
        )
        self.assertFalse(
            coverage_gate.matches(
                "com/tripagent/nexus/controller/dto/ApiModels.java",
                controller_pattern,
            )
        )
        self.assertTrue(
            coverage_gate.matches(
                "services/ai-retrieval-service/src/trip_agent_ai/api/documents.py",
                ["*src/trip_agent_ai/api/*.py"],
            )
        )

    def test_python_coverage_gate_uses_line_only_percentage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            report_path = Path(temporary_directory) / "coverage.json"
            report_path.write_text(
                json.dumps(
                    {
                        "totals": {
                            "percent_covered": 70.0,
                            "percent_statements_covered": 85.0,
                            "num_branches": 10,
                            "covered_branches": 7,
                        }
                    }
                ),
                encoding="utf-8",
            )

            completed = subprocess.run(
                [
                    sys.executable,
                    str(PYTHON_COVERAGE_GATE_PATH),
                    "--report",
                    str(report_path),
                ],
                check=False,
                capture_output=True,
                text=True,
            )

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("行 85.00%", completed.stdout)
        self.assertIn("分支 70.00%", completed.stdout)

    def test_fixture_initialization_is_repeatable(self) -> None:
        initializer = load_module("qa_fixture_initializer", FIXTURE_INITIALIZER_PATH)
        runtime_root = REPOSITORY_ROOT / ".runtime"
        runtime_root.mkdir(parents=True, exist_ok=True)
        temporary_root = Path(
            tempfile.mkdtemp(prefix="fixture-test-", dir=runtime_root)
        )
        target = temporary_root / "fixtures"
        try:
            first = initializer.initialize(target)
            second = initializer.initialize(target)

            self.assertEqual(first, second)
            self.assertEqual(first["randomSeed"], 20260908)
            self.assertGreaterEqual(len(first["fixtures"]), 13)
            self.assertTrue((target / "fixture-index.json").is_file())
        finally:
            shutil.rmtree(temporary_root, ignore_errors=True)

    def test_test_profile_rejects_remote_resource_overrides(self) -> None:
        validator = load_module(
            "qa_test_environment_validator", TEST_ENV_VALIDATOR_PATH
        )

        with (
            mock.patch.dict(
                os.environ,
                {
                    "MODEL_GATEWAY_PROVIDER": "mock",
                    "DOCUMENT_PARSER_MODE": "mock",
                    "RETRIEVAL_PROVIDER_MODE": "mock",
                    "GRAPH_STORE_MODE": "memory",
                    "MCP_PROVIDER_MODE": "mock",
                    "PLANNING_PROVIDER_MODE": "mock",
                    "SPRING_DATASOURCE_URL": (
                        "jdbc:postgresql://production.example.test:5432/tripagent"
                    ),
                },
                clear=True,
            ),
            self.assertRaisesRegex(
                ValueError,
                "outside the isolated test network",
            ),
        ):
            validator.validate()

    def test_sanitizer_removes_credentials_and_personal_identifiers(self) -> None:
        runner = load_module("qa_report_runner", RUNNER_PATH)
        secret = "synthetic-secret-value"
        raw = (
            "Authorization: Bearer synthetic-bearer-value "
            "api_key=synthetic-key-value password=synthetic-password "
            "phone=13800138000 id=110101199001011234 "
            f"configured={secret}"
        )

        sanitized = runner.sanitize(raw, [secret])

        self.assertNotIn("synthetic-bearer-value", sanitized)
        self.assertNotIn("synthetic-key-value", sanitized)
        self.assertNotIn("synthetic-password", sanitized)
        self.assertNotIn("13800138000", sanitized)
        self.assertNotIn("110101199001011234", sanitized)
        self.assertNotIn(secret, sanitized)

    def test_report_keeps_trace_context_but_never_keeps_secret(self) -> None:
        with tempfile.TemporaryDirectory() as reports:
            env = os.environ.copy()
            env["DEEPSEEK_API_KEY"] = "synthetic-secret-value"
            command = [
                sys.executable,
                str(RUNNER_PATH),
                "--suite",
                "sanitizer-self-test",
                "--reports-dir",
                reports,
                "--",
                sys.executable,
                "-c",
                (
                    "print('traceId=0123456789abcdef0123456789abcdef "
                    "taskId=10000000-0000-4000-8000-000000000901 "
                    "token=synthetic-secret-value')"
                ),
            ]

            result = subprocess.run(command, env=env, check=False, capture_output=True)

            self.assertEqual(result.returncode, 0)

            self.assertEqual(result.returncode, 0)
            report_path = next(Path(reports).glob("*/json/sanitizer-self-test.json"))
            report = json.loads(report_path.read_text(encoding="utf-8"))
            log = (
                report_path.parent.parent / "logs/sanitizer-self-test.log"
            ).read_text(encoding="utf-8")
            self.assertEqual(
                report["failureContext"]["traceIds"],
                ["0123456789abcdef0123456789abcdef"],
            )
            self.assertEqual(
                report["failureContext"]["taskIds"],
                ["10000000-0000-4000-8000-000000000901"],
            )
            self.assertNotIn(
                "synthetic-secret-value",
                report_path.read_text(encoding="utf-8"),
            )
            self.assertNotIn("synthetic-secret-value", log)

    def test_identifier_collection_is_bounded_for_long_running_suites(self) -> None:
        runner = load_module("qa_bounded_report_runner", RUNNER_PATH)
        identifiers: set[str] = set()

        for index in range(50):
            runner.collect_identifiers(
                identifiers,
                runner.TRACE_ID,
                f"traceId={index:032x}",
            )

        self.assertEqual(len(identifiers), 20)

    def test_remote_destructive_target_requires_explicit_authorization(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                str(GUARD_PATH),
                "--kind",
                "performance",
                "--base-url",
                "https://qa.example.test",
            ],
            env={
                key: value
                for key, value in os.environ.items()
                if key != "QA_AUTHORIZED_TEST_ENV"
            },
            check=False,
            capture_output=True,
        )

        self.assertEqual(result.returncode, 2)
        self.assertIn("QA_AUTHORIZED_TEST_ENV=true", result.stderr.decode())

    def test_third_party_target_is_rejected_even_when_remote_is_authorized(
        self,
    ) -> None:
        env = os.environ.copy()
        env["QA_AUTHORIZED_TEST_ENV"] = "true"

        result = subprocess.run(
            [
                sys.executable,
                str(GUARD_PATH),
                "--kind",
                "performance",
                "--base-url",
                "https://api.deepseek.com",
            ],
            env=env,
            check=False,
            capture_output=True,
        )

        self.assertEqual(result.returncode, 2)
        self.assertIn("禁止压测或扫描的第三方服务", result.stderr.decode())

    def test_real_model_requires_explicit_enablement(self) -> None:
        env = {
            key: value
            for key, value in os.environ.items()
            if key != "REAL_MODEL_TEST_ENABLED"
        }

        result = subprocess.run(
            [
                sys.executable,
                str(GUARD_PATH),
                "--kind",
                "real-model",
                "--base-url",
                "http://127.0.0.1:8080",
            ],
            env=env,
            check=False,
            capture_output=True,
        )

        self.assertEqual(result.returncode, 2)
        self.assertIn("REAL_MODEL_TEST_ENABLED=true", result.stderr.decode())

    def test_real_model_limits_and_local_performance_target_are_enforced(self) -> None:
        allowed = subprocess.run(
            [
                sys.executable,
                str(GUARD_PATH),
                "--kind",
                "performance",
                "--base-url",
                "http://127.0.0.1:8080",
            ],
            check=False,
            capture_output=True,
        )
        env = os.environ.copy()
        env["REAL_MODEL_TEST_ENABLED"] = "true"
        over_limit = subprocess.run(
            [
                sys.executable,
                str(GUARD_PATH),
                "--kind",
                "real-model",
                "--base-url",
                "http://127.0.0.1:8080",
                "--concurrency",
                "3",
                "--max-calls",
                "1",
            ],
            env=env,
            check=False,
            capture_output=True,
        )

        self.assertEqual(allowed.returncode, 0)
        self.assertEqual(over_limit.returncode, 2)
        self.assertIn("并发数不得超过", over_limit.stderr.decode())


if __name__ == "__main__":
    unittest.main()
