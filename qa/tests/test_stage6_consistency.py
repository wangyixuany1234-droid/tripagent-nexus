import importlib.util
import json
import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def load_checker():
    path = REPOSITORY_ROOT / "qa/scripts/check_stage6_consistency.py"
    spec = importlib.util.spec_from_file_location("stage6_consistency", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("Could not load stage-six consistency checker")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


checker = load_checker()


class Stage6ConsistencyTest(unittest.TestCase):
    def test_scenario_catalog_contains_all_required_failures(self) -> None:
        catalog = json.loads(
            (REPOSITORY_ROOT / "qa/consistency/stage6-scenarios.json").read_text(
                encoding="utf-8"
            )
        )

        self.assertEqual(len(catalog["scenarios"]), 15)
        self.assertEqual(
            [scenario["id"] for scenario in catalog["scenarios"]],
            [f"S6-{index:02d}" for index in range(1, 16)],
        )

    def test_sql_contains_every_required_integrity_check(self) -> None:
        sql = (REPOSITORY_ROOT / "qa/consistency/stage6_consistency.sql").read_text(
            encoding="utf-8"
        )

        for check_name in (
            "orphanTasks",
            "orphanPlans",
            "duplicatePlanVersions",
            "unreferencedEvidence",
            "unfinishedKafkaOutbox",
            "longRunningTasks",
            "successfulTasksWithoutPlans",
            "taskEventsWithoutProvenance",
            "incompleteDeadLetterAudits",
        ):
            with self.subTest(check_name=check_name):
                self.assertIn(check_name, sql)

    def test_remote_database_requires_explicit_authorization(self) -> None:
        with (
            patch.dict(
                os.environ,
                {"QA_AUTHORIZED_TEST_ENV": "false"},
                clear=False,
            ),
            self.assertRaisesRegex(
                ValueError,
                "QA_AUTHORIZED_TEST_ENV=true",
            ),
        ):
            checker.database_environment(
                "postgresql://synthetic:secret@example.test/tripagent"
            )

    def test_local_database_credentials_are_passed_only_via_environment(
        self,
    ) -> None:
        environment = checker.database_environment(
            "postgresql://synthetic:local-secret@127.0.0.1:5432/tripagent_test"
        )

        self.assertEqual(environment["PGHOST"], "127.0.0.1")
        self.assertEqual(environment["PGDATABASE"], "tripagent_test")
        self.assertEqual(environment["PGPASSWORD"], "local-secret")

    def test_psql_rows_are_converted_to_machine_readable_checks(self) -> None:
        rows = checker.parse_rows(
            'orphanTasks\t0\t[]\nlongRunningTasks\t2\t["task-a", "task-b"]\n'
        )

        self.assertEqual(
            rows,
            [
                {
                    "name": "orphanTasks",
                    "violationCount": 0,
                    "sampleIds": [],
                },
                {
                    "name": "longRunningTasks",
                    "violationCount": 2,
                    "sampleIds": ["task-a", "task-b"],
                },
            ],
        )

    def test_eventual_consistency_time_is_measured_until_checks_converge(
        self,
    ) -> None:
        outputs = iter(
            [
                'unfinishedKafkaOutbox\t1\t["event-a"]\n',
                "unfinishedKafkaOutbox\t0\t[]\n",
            ]
        )
        now = [0.0]

        def runner(*args, **kwargs):
            del args, kwargs
            now[0] += 0.1
            return subprocess.CompletedProcess([], 0, next(outputs), "")

        def sleeper(seconds: float) -> None:
            now[0] += seconds

        checks, convergence = checker.wait_for_consistency(
            ["psql"],
            {},
            0,
            5,
            1,
            runner=runner,
            clock=lambda: now[0],
            sleeper=sleeper,
        )

        self.assertEqual(checks[0]["violationCount"], 0)
        self.assertEqual(
            convergence,
            {
                "converged": True,
                "elapsedMilliseconds": 1200,
                "attempts": 2,
                "timeoutMilliseconds": 5000,
            },
        )

    def test_convergence_after_the_deadline_is_reported_as_failed(self) -> None:
        now = [0.0]

        def runner(*args, **kwargs):
            del args, kwargs
            now[0] = 1.1
            return subprocess.CompletedProcess(
                [],
                0,
                "unfinishedKafkaOutbox\t0\t[]\n",
                "",
            )

        checks, convergence = checker.wait_for_consistency(
            ["psql"],
            {},
            0,
            1,
            0.1,
            runner=runner,
            clock=lambda: now[0],
            sleeper=lambda _: None,
        )

        self.assertEqual(checks[0]["violationCount"], 0)
        self.assertEqual(
            convergence,
            {
                "converged": False,
                "elapsedMilliseconds": 1100,
                "attempts": 1,
                "timeoutMilliseconds": 1000,
            },
        )


if __name__ == "__main__":
    unittest.main()
