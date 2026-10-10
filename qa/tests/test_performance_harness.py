import importlib.util
import json
import os
import shutil
import subprocess
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def load_script(name: str, relative_path: str):
    spec = importlib.util.spec_from_file_location(
        name,
        REPOSITORY_ROOT / relative_path,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load {relative_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


collector = load_script(
    "collect_performance_metrics",
    "qa/scripts/collect_performance_metrics.py",
)
summarizer = load_script(
    "summarize_performance",
    "qa/scripts/summarize_performance.py",
)


class PerformanceHarnessTest(unittest.TestCase):
    def test_stage5_profiles_and_thresholds_match_acceptance_defaults(self) -> None:
        config = json.loads(
            (REPOSITORY_ROOT / "qa/config/thresholds.json").read_text(encoding="utf-8")
        )["performance"]

        self.assertEqual(config["smoke"], {"virtualUsers": 2, "duration": "5m"})
        self.assertEqual(
            [stage["target"] for stage in config["load"]["stages"]],
            [25, 25, 50, 50, 100, 100, 0],
        )
        self.assertEqual(
            max(stage["target"] for stage in config["stress"]["stages"]),
            400,
        )
        self.assertEqual(
            max(stage["target"] for stage in config["spike"]["stages"]),
            300,
        )
        self.assertEqual(config["soak"]["virtualUsers"], 50)
        self.assertEqual(config["soak"]["duration"], "2h")
        self.assertEqual(config["soak"]["maximumDuration"], "8h")
        self.assertEqual(
            max(stage["target"] for stage in config["sse"]["stages"]),
            1000,
        )
        self.assertEqual(config["synchronous"]["p95Milliseconds"], 500)
        self.assertEqual(config["synchronous"]["p99Milliseconds"], 1000)
        self.assertEqual(config["write"]["p95Milliseconds"], 800)
        self.assertEqual(config["write"]["p99Milliseconds"], 1500)
        self.assertEqual(config["async"]["completionP95Milliseconds"], 30000)
        self.assertEqual(config["sseConnection"]["maximumServerCpuPercent"], 90)
        self.assertEqual(config["recovery"]["maximumFinalKafkaLag"], 0)

    def test_runtime_metric_parsers_extract_required_signals(self) -> None:
        prometheus = collector.parse_prometheus(
            """
            # HELP ignored documentation
            jvm_memory_used_bytes{area="heap",id="eden"} 120
            jvm_memory_used_bytes{area="heap",id="old"} 80
            hikaricp_connections_active{pool="HikariPool-1"} 3
            """
        )
        self.assertEqual(
            collector.metric_sum(
                prometheus,
                "jvm_memory_used_bytes",
                {"area": "heap"},
            ),
            200,
        )
        self.assertEqual(
            collector.metric_sum(
                prometheus,
                "hikaricp_connections_active",
            ),
            3,
        )

        redis = collector.parse_redis_info(
            "keyspace_hits:90\nkeyspace_misses:10\nused_memory:2048\n"
            "connected_clients:4\n"
        )
        self.assertEqual(redis["redisHitRate"], 0.9)
        self.assertEqual(redis["redisUsedMemoryBytes"], 2048)

        kafka = (
            "GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG\n"
            "trip-agent-api topic-a 0 10 12 2\n"
            "trip-agent-api topic-b 0 8 11 3\n"
        )
        self.assertEqual(collector.parse_kafka_lag(kafka), 5)
        self.assertEqual(collector.byte_value("12.5MiB"), 13_107_200)

    def test_kafka_collector_aggregates_all_groups_with_configured_timeout(
        self,
    ) -> None:
        compose = Mock()
        compose.exec.return_value = (
            "GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG\n"
            "trip-agent-orchestrator-v1 topic-a 0 10 12 2\n"
            "trip-agent-document-parser-adapter-v1 topic-b 0 8 11 3\n"
        )

        with patch.dict(
            os.environ,
            {"QA_KAFKA_METRICS_TIMEOUT_SECONDS": "45"},
        ):
            metrics = collector.collect_kafka(compose)

        self.assertEqual(metrics, {"kafkaConsumerLag": 5.0})
        compose.exec.assert_called_once_with(
            "kafka",
            "/opt/kafka/bin/kafka-consumer-groups.sh",
            "--bootstrap-server",
            "kafka:29092",
            "--all-groups",
            "--describe",
            timeout=45.0,
        )

    def test_compose_client_caches_container_ids_for_direct_exec(self) -> None:
        completed = [
            subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout="redis|container-123\n",
                stderr="",
            ),
            subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout="PONG\n",
                stderr="",
            ),
            subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout="PONG\n",
                stderr="",
            ),
        ]
        with patch.object(
            collector.subprocess,
            "run",
            side_effect=completed,
        ) as run:
            compose = collector.ComposeClient("qa-stage5")
            self.assertEqual(
                compose.exec("redis", "redis-cli", "PING"),
                "PONG\n",
            )
            self.assertEqual(
                compose.exec("redis", "redis-cli", "PING"),
                "PONG\n",
            )

        self.assertEqual(run.call_count, 3)
        self.assertEqual(run.call_args_list[1].args[0][0:3], [
            "docker",
            "exec",
            "container-123",
        ])
        self.assertEqual(run.call_args_list[2].args[0][0:3], [
            "docker",
            "exec",
            "container-123",
        ])

    def test_metric_sample_records_only_sanitized_error_types(self) -> None:
        args = SimpleNamespace(
            base_url="http://127.0.0.1:8080",
            ai_base_url="http://127.0.0.1:8000",
            ai_token="synthetic-token",
        )
        compose = Mock()
        compose.stats.return_value = {}

        with (
            patch.object(
                collector,
                "collect_spring",
                side_effect=subprocess.TimeoutExpired(
                    "sensitive-command",
                    1,
                ),
            ),
            patch.object(collector, "collect_python", return_value={}),
            patch.object(collector, "collect_redis", return_value={}),
            patch.object(collector, "collect_postgres", return_value={}),
            patch.object(collector, "collect_disk", return_value={}),
            patch.object(
                collector,
                "collect_process_descriptors",
                return_value={},
            ),
        ):
            sample = collector.collect_sample(args, compose)

        self.assertEqual(sample["collectionErrors"], ["spring"])
        self.assertEqual(
            sample["collectionErrorTypes"],
            {"spring": "TimeoutExpired"},
        )
        self.assertNotIn("sensitive-command", json.dumps(sample))

    def test_spring_collector_reads_kafka_lag_without_starting_kafka_cli(
        self,
    ) -> None:
        prometheus = """
        jvm_threads_live_threads 12
        kafka_consumer_fetch_manager_records_lag{partition="0",topic="a"} 2
        kafka_consumer_fetch_manager_records_lag{partition="1",topic="a"} 3
        """

        with patch.object(collector, "request_text", return_value=prometheus):
            metrics = collector.collect_spring("http://127.0.0.1:8080")

        self.assertEqual(metrics["kafkaConsumerLag"], 5)

    def test_mixed_workload_accepts_human_review_and_valid_feedback_targets(
        self,
    ) -> None:
        workflows = (
            REPOSITORY_ROOT / "qa/performance/lib/workflows.js"
        ).read_text(encoding="utf-8")
        data = (REPOSITORY_ROOT / "qa/performance/lib/data.js").read_text(
            encoding="utf-8"
        )

        self.assertIn('"WAITING_CONFIRMATION"', workflows)
        self.assertIn("allowExistingTask: true", workflows)
        self.assertIn("allowExistingResource: true", workflows)
        self.assertIn('payload.targetType = "PLACE"', data)

    def test_capacity_and_consistency_summary_detect_failures(self) -> None:
        k6_summary = {
            "metrics": {
                "http_req_duration": {
                    "values": {
                        "med": 200,
                        "p(90)": 500,
                        "p(95)": 900,
                        "p(99)": 1400,
                    },
                    "thresholds": {},
                },
                "http_reqs": {"values": {"rate": 75}},
                "http_req_failed": {"values": {"rate": 0}},
                "capacity_latency_vus_001_025": {"values": {"p(95)": 400}},
                "capacity_latency_vus_026_050": {"values": {"p(95)": 850}},
                "race_successful_writes": {"values": {"count": 20}},
                "race_rejected_writes": {"values": {"count": 0}},
            }
        }

        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("QA_CAPACITY_P95_MS", None)
            summary = summarizer.build_summary(
                "race",
                k6_summary,
                {"sampleCount": 0, "metrics": {}},
                0,
            )

        self.assertEqual(
            summary["capacityBreakpoint"]["firstBandAtOrAboveLimit"],
            50,
        )
        self.assertEqual(summary["status"], "FAILED")
        self.assertIn(
            "19 writes were silently overwritten",
            summary["failures"],
        )

    def test_race_summary_accepts_one_winner_and_explicit_conflicts(self) -> None:
        k6_summary = {
            "metrics": {
                "http_req_duration": {"values": {}, "thresholds": {}},
                "http_reqs": {"values": {"rate": 20}},
                "http_req_failed": {
                    "values": {"rate": 0},
                    "thresholds": {"rate<0.01": {"ok": True}},
                },
                "race_successful_writes": {
                    "values": {"count": 1},
                    "thresholds": {"count==1": {"ok": True}},
                },
                "race_rejected_writes": {
                    "values": {"count": 19},
                    "thresholds": {"count==19": {"ok": True}},
                },
            }
        }
        resources = {
            "sampleCount": 1,
            "collectionErrorCount": 0,
            "metrics": {},
        }

        summary = summarizer.build_summary("race", k6_summary, resources, 0)

        self.assertEqual(summary["status"], "PASSED")
        self.assertEqual(summary["failures"], [])

    def test_idempotency_summary_accepts_one_shared_resource(self) -> None:
        k6_summary = {
            "metrics": {
                "http_req_duration": {"values": {}, "thresholds": {}},
                "http_reqs": {"values": {"rate": 20}},
                "http_req_failed": {
                    "values": {"rate": 0},
                    "thresholds": {"rate<0.01": {"ok": True}},
                },
                "idempotency_resource_fingerprint": {
                    "values": {"min": 1234, "max": 1234}
                },
            }
        }
        resources = {
            "sampleCount": 1,
            "collectionErrorCount": 0,
            "metrics": {},
        }

        summary = summarizer.build_summary(
            "idempotency",
            k6_summary,
            resources,
            0,
        )

        self.assertEqual(summary["status"], "PASSED")
        self.assertEqual(summary["failures"], [])

    def test_high_load_and_canary_require_explicit_approval(self) -> None:
        runner = REPOSITORY_ROOT / "qa/scripts/run_performance.sh"
        environment = {
            **os.environ,
            "K6_BIN": "/usr/bin/true",
            "QA_HIGH_LOAD_APPROVED": "false",
            "QA_REAL_MODEL_CANARY_APPROVED": "false",
        }

        high_load = subprocess.run(
            [str(runner), "stress"],
            cwd=REPOSITORY_ROOT,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
        )
        canary = subprocess.run(
            [str(runner), "canary"],
            cwd=REPOSITORY_ROOT,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(high_load.returncode, 2)
        self.assertIn("QA_HIGH_LOAD_APPROVED=true", high_load.stderr)
        self.assertEqual(canary.returncode, 2)
        self.assertIn("QA_REAL_MODEL_CANARY_APPROVED=true", canary.stderr)

    def test_high_load_requires_mock_external_tool_confirmation(self) -> None:
        runner = REPOSITORY_ROOT / "qa/scripts/run_performance.sh"
        environment = {
            **os.environ,
            "K6_BIN": "/usr/bin/true",
            "QA_HIGH_LOAD_APPROVED": "true",
            "QA_MOCK_EXTERNAL_TOOLS_CONFIRMED": "false",
        }

        result = subprocess.run(
            [str(runner), "load"],
            cwd=REPOSITORY_ROOT,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(result.returncode, 2)
        self.assertIn("OCR 和外部工具均为 Mock", result.stderr)

    def test_docker_k6_uses_the_calling_user_for_report_outputs(self) -> None:
        runner = (
            REPOSITORY_ROOT / "qa/scripts/run_performance.sh"
        ).read_text(encoding="utf-8")

        self.assertIn(
            '--user "$(id -u):$(id -g)"',
            runner,
        )

    def test_spike_and_sse_resource_thresholds_are_enforced(self) -> None:
        stable_metric = {
            "first": 10.0,
            "last": 10.0,
            "minimum": 10.0,
            "maximum": 10.0,
            "growthPercent": 0.0,
        }
        metrics = {
            name: dict(stable_metric)
            for name in (
                "jvmThreadsLive",
                "pythonThreads",
                "hikariConnectionsActive",
                "hikariConnectionsPending",
                "kafkaConsumerLag",
                "redisConnectedClients",
                "tripAgentEstablishedTcpConnections",
                "pythonEstablishedTcpConnections",
            )
        }
        metrics["hikariConnectionsPending"]["last"] = 0
        metrics["kafkaConsumerLag"]["last"] = 0
        metrics["tripAgentContainerCpuPercent"] = {
            **stable_metric,
            "maximum": 95.0,
        }
        resources = {
            "sampleCount": 2,
            "collectionErrorCount": 0,
            "metrics": metrics,
        }

        self.assertEqual(summarizer.recovery_failures(resources), [])
        self.assertIn(
            "SSE server CPU",
            summarizer.sse_resource_failures(resources)[0],
        )

    def test_soak_summary_requires_recovered_runtime_metrics(self) -> None:
        stable_metric = {
            "first": 10.0,
            "last": 10.0,
            "minimum": 10.0,
            "maximum": 10.0,
            "growthPercent": 0.0,
        }
        metrics = {
            name: dict(stable_metric)
            for name in (
                "jvmHeapUsedBytes",
                "jvmThreadsLive",
                "hikariConnectionsActive",
                "hikariConnectionsPending",
                "kafkaConsumerLag",
                "lanceDbBytes",
                "pythonRssBytes",
                "pythonThreads",
                "pythonEventLoopLagP95Ms",
                "runningTasks",
                "tripAgentEstablishedTcpConnections",
                "pythonEstablishedTcpConnections",
            )
        }
        metrics["hikariConnectionsPending"]["last"] = 0
        metrics["kafkaConsumerLag"]["last"] = 0
        metrics["runningTasks"]["last"] = 0

        failures = summarizer.soak_failures(
            {
                "sampleCount": 2,
                "collectionErrorCount": 0,
                "metrics": metrics,
            }
        )

        self.assertEqual(failures, [])
        self.assertIn(
            "missing soak metrics",
            summarizer.soak_failures(
                {
                    "sampleCount": 1,
                    "collectionErrorCount": 0,
                    "metrics": {},
                }
            )[0],
        )

    @unittest.skipUnless(shutil.which("node"), "Node.js is unavailable")
    def test_k6_sources_are_valid_ecmascript(self) -> None:
        sources = [
            *sorted((REPOSITORY_ROOT / "qa/performance/lib").glob("*.js")),
            *sorted((REPOSITORY_ROOT / "qa/performance").glob("*.js")),
        ]
        for source in sources:
            with self.subTest(source=source):
                subprocess.run(
                    [shutil.which("node"), "--check", str(source)],
                    check=True,
                    capture_output=True,
                    text=True,
                )


if __name__ == "__main__":
    unittest.main()
