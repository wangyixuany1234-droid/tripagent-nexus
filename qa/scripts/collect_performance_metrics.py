#!/usr/bin/env python3
"""Collect sanitized runtime metrics while a local k6 run is active."""

from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import threading
import time
from collections.abc import Iterable
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.request import Request, urlopen

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
STOP_REQUESTED = False


def positive_env_number(name: str, default: str) -> float:
    value = float(os.getenv(name, default))
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--ai-base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--ai-token", default=os.getenv("QA_AI_SERVICE_TOKEN", ""))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--stop-file", type=Path, required=True)
    parser.add_argument(
        "--interval-seconds",
        type=float,
        default=float(os.getenv("QA_METRICS_INTERVAL_SECONDS", "5")),
    )
    parser.add_argument(
        "--compose-project",
        default=os.getenv("QA_COMPOSE_PROJECT", "tripagent-nexus-test"),
    )
    return parser.parse_args()


def request_text(url: str, headers: dict[str, str] | None = None) -> str:
    request = Request(url, headers=headers or {})
    timeout = positive_env_number("QA_METRICS_HTTP_TIMEOUT_SECONDS", "8")
    with urlopen(request, timeout=timeout) as response:
        return response.read().decode("utf-8")


def parse_prometheus(text: str) -> list[tuple[str, dict[str, str], float]]:
    parsed: list[tuple[str, dict[str, str], float]] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, raw_value = line.rsplit(None, 1)
        name, _, labels_text = key.partition("{")
        labels: dict[str, str] = {}
        if labels_text:
            for label in labels_text.removesuffix("}").split(","):
                label_name, _, value = label.partition("=")
                labels[label_name] = value.strip('"')
        try:
            parsed.append((name, labels, float(raw_value)))
        except ValueError:
            continue
    return parsed


def metric_sum(
    metrics: Iterable[tuple[str, dict[str, str], float]],
    name: str,
    required_labels: dict[str, str] | None = None,
) -> float | None:
    values = [
        value
        for metric_name, labels, value in metrics
        if metric_name == name
        and all(
            labels.get(key) == expected
            for key, expected in (required_labels or {}).items()
        )
        and value >= 0
    ]
    return sum(values) if values else None


def parse_redis_info(text: str) -> dict[str, float]:
    values: dict[str, float] = {}
    for line in text.splitlines():
        if ":" not in line or line.startswith("#"):
            continue
        key, raw_value = line.split(":", 1)
        try:
            values[key] = float(raw_value.strip())
        except ValueError:
            continue
    hits = values.get("keyspace_hits", 0)
    misses = values.get("keyspace_misses", 0)
    total = hits + misses
    return {
        "redisKeyspaceHits": hits,
        "redisKeyspaceMisses": misses,
        "redisHitRate": hits / total if total else 0.0,
        "redisUsedMemoryBytes": values.get("used_memory", 0),
        "redisConnectedClients": values.get("connected_clients", 0),
    }


def parse_kafka_lag(text: str) -> int:
    total = 0
    for line in text.splitlines():
        fields = line.split()
        if len(fields) < 6 or fields[0] == "GROUP":
            continue
        numeric_fields = []
        for field in fields:
            try:
                numeric_fields.append(int(field))
            except ValueError:
                continue
        if len(numeric_fields) >= 4:
            total += numeric_fields[3]
    return total


def byte_value(value: str) -> int:
    units = {
        "B": 1,
        "kB": 1000,
        "KB": 1000,
        "KiB": 1024,
        "MB": 1000**2,
        "MiB": 1024**2,
        "GB": 1000**3,
        "GiB": 1024**3,
    }
    number = ""
    unit = ""
    for character in value.strip():
        if character.isdigit() or character in {".", "-"}:
            number += character
        elif not character.isspace():
            unit += character
    return int(float(number) * units.get(unit, 1)) if number else 0


class ComposeClient:
    def __init__(self, project: str) -> None:
        self._project = project
        self._command_timeout = positive_env_number(
            "QA_METRICS_COMMAND_TIMEOUT_SECONDS",
            "15",
        )
        self._container_ids: dict[str, str] = {}
        self._container_ids_lock = threading.Lock()
        self._base = [
            "docker",
            "compose",
            "-p",
            project,
            "-f",
            str(REPOSITORY_ROOT / "compose.yaml"),
            "-f",
            str(REPOSITORY_ROOT / "qa/compose.qa.yaml"),
        ]

    def run(self, *arguments: str, timeout: float | None = None) -> str:
        completed = subprocess.run(
            [*self._base, *arguments],
            cwd=REPOSITORY_ROOT,
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout or self._command_timeout,
        )
        return completed.stdout

    def container_id(self, service: str) -> str:
        with self._container_ids_lock:
            cached = self._container_ids.get(service)
            if cached:
                return cached
            completed = subprocess.run(
                [
                    "docker",
                    "ps",
                    "--filter",
                    f"label=com.docker.compose.project={self._project}",
                    "--format",
                    '{{.Label "com.docker.compose.service"}}|{{.ID}}',
                ],
                check=True,
                capture_output=True,
                text=True,
                timeout=self._command_timeout,
            )
            self._container_ids.update(
                line.split("|", 1)
                for line in completed.stdout.splitlines()
                if "|" in line
            )
            return self._container_ids.get(service, "")

    def exec(
        self,
        service: str,
        *arguments: str,
        timeout: float | None = None,
    ) -> str:
        container_id = self.container_id(service)
        if not container_id:
            raise RuntimeError(f"Compose service is not running: {service}")
        completed = subprocess.run(
            ["docker", "exec", container_id, *arguments],
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout or self._command_timeout,
        )
        return completed.stdout

    def stats(self, service: str) -> dict[str, float]:
        container_id = self.container_id(service)
        if not container_id:
            return {}
        completed = subprocess.run(
            [
                "docker",
                "stats",
                "--no-stream",
                "--format",
                "{{json .}}",
                container_id,
            ],
            check=True,
            capture_output=True,
            text=True,
            timeout=self._command_timeout,
        )
        values = json.loads(completed.stdout)
        memory_used = values.get("MemUsage", "0B / 0B").split("/", 1)[0].strip()
        return {
            "cpuPercent": float(values.get("CPUPerc", "0").rstrip("%")),
            "memoryBytes": byte_value(memory_used),
            "pids": float(values.get("PIDs", 0)),
        }


def collect_spring(base_url: str) -> dict[str, float]:
    metrics = parse_prometheus(request_text(f"{base_url}/actuator/prometheus"))
    selected = {
        "jvmHeapUsedBytes": metric_sum(
            metrics,
            "jvm_memory_used_bytes",
            {"area": "heap"},
        ),
        "jvmHeapMaxBytes": metric_sum(
            metrics,
            "jvm_memory_max_bytes",
            {"area": "heap"},
        ),
        "jvmGcPauseSecondsCount": metric_sum(metrics, "jvm_gc_pause_seconds_count"),
        "jvmGcPauseSecondsSum": metric_sum(metrics, "jvm_gc_pause_seconds_sum"),
        "jvmThreadsLive": metric_sum(metrics, "jvm_threads_live_threads"),
        "hikariConnectionsActive": metric_sum(metrics, "hikaricp_connections_active"),
        "hikariConnectionsIdle": metric_sum(metrics, "hikaricp_connections_idle"),
        "hikariConnectionsPending": metric_sum(metrics, "hikaricp_connections_pending"),
        "hikariConnectionsMax": metric_sum(metrics, "hikaricp_connections_max"),
        "kafkaConsumerLag": metric_sum(
            metrics,
            "kafka_consumer_fetch_manager_records_lag",
        ),
    }
    return {key: value for key, value in selected.items() if value is not None}


def collect_python(ai_base_url: str, token: str) -> dict[str, float]:
    payload = json.loads(
        request_text(
            f"{ai_base_url}/internal/v1/runtime-metrics",
            {"Authorization": f"Bearer {token}"},
        )
    )
    return {
        "pythonRssBytes": float(payload["processRssBytes"]),
        "pythonThreads": float(payload["threadCount"]),
        "pythonEventLoopLagMs": float(payload["eventLoopLagMs"]),
        "pythonEventLoopLagP95Ms": float(payload["eventLoopLagP95Ms"]),
        "pythonEventLoopLagMaxMs": float(payload["eventLoopLagMaxMs"]),
    }


def collect_redis(compose: ComposeClient) -> dict[str, float]:
    password = os.getenv("REDIS_PASSWORD", "test-redis-password")
    output = compose.exec(
        "redis",
        "redis-cli",
        "--no-auth-warning",
        "-a",
        password,
        "INFO",
        "ALL",
    )
    return parse_redis_info(output)


def collect_kafka(compose: ComposeClient) -> dict[str, float]:
    timeout = float(os.getenv("QA_KAFKA_METRICS_TIMEOUT_SECONDS", "30"))
    if timeout <= 0:
        raise ValueError("QA_KAFKA_METRICS_TIMEOUT_SECONDS must be positive")
    output = compose.exec(
        "kafka",
        "/opt/kafka/bin/kafka-consumer-groups.sh",
        "--bootstrap-server",
        "kafka:29092",
        "--all-groups",
        "--describe",
        timeout=timeout,
    )
    return {"kafkaConsumerLag": float(parse_kafka_lag(output))}


def collect_postgres(compose: ComposeClient) -> dict[str, float]:
    username = os.getenv("POSTGRES_USERNAME", "tripagent_test")
    database = os.getenv("POSTGRES_DATABASE", "tripagent_test")
    output = compose.exec(
        "postgres",
        "psql",
        "-U",
        username,
        "-d",
        database,
        "-At",
        "-c",
        """
        SELECT
          (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()),
          (SELECT count(*) FROM task_record WHERE status = 'RUNNING');
        """,
    ).strip()
    connections, running_tasks = output.split("|", 1)
    return {
        "postgresConnections": float(connections),
        "runningTasks": float(running_tasks),
    }


def collect_process_descriptors(
    compose: ComposeClient,
    service: str,
    prefix: str,
) -> dict[str, float]:
    output = compose.exec(
        service,
        "sh",
        "-c",
        (
            "fds=$(find /proc/1/fd -mindepth 1 -maxdepth 1 2>/dev/null | wc -l); "
            "tcp=$(awk 'NR>1 && $4==\"01\" {count++} END {print count+0}' "
            '/proc/1/net/tcp); printf \'%s|%s\\n\' "$fds" "$tcp"'
        ),
    ).strip()
    descriptors, established_connections = output.split("|", 1)
    return {
        f"{prefix}OpenFileDescriptors": float(descriptors),
        f"{prefix}EstablishedTcpConnections": float(established_connections),
    }


def collect_disk(compose: ComposeClient) -> dict[str, float]:
    lancedb = REPOSITORY_ROOT / ".runtime/test-env/lancedb"
    lancedb_bytes = sum(
        path.stat().st_size for path in lancedb.rglob("*") if path.is_file()
    )
    upload_kib = compose.exec(
        "trip-agent-api",
        "du",
        "-sk",
        "/var/lib/tripagent/uploads",
    ).split()[0]
    return {
        "lanceDbBytes": float(lancedb_bytes),
        "uploadStorageBytes": float(upload_kib) * 1024,
    }


def collect_sample(args: argparse.Namespace, compose: ComposeClient) -> dict[str, Any]:
    sample: dict[str, Any] = {
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "collectionErrors": [],
    }
    collectors = (
        ("spring", lambda: collect_spring(args.base_url)),
        ("python", lambda: collect_python(args.ai_base_url, args.ai_token)),
        ("redis", lambda: collect_redis(compose)),
        ("postgres", lambda: collect_postgres(compose)),
        ("disk", lambda: collect_disk(compose)),
        (
            "tripAgentDescriptors",
            lambda: collect_process_descriptors(
                compose,
                "trip-agent-api",
                "tripAgent",
            ),
        ),
        (
            "pythonDescriptors",
            lambda: collect_process_descriptors(
                compose,
                "ai-retrieval-service",
                "python",
            ),
        ),
        (
            "tripAgentContainer",
            lambda: {
                f"tripAgentContainer{key[0].upper()}{key[1:]}": value
                for key, value in compose.stats("trip-agent-api").items()
            },
        ),
        (
            "pythonContainer",
            lambda: {
                f"pythonContainer{key[0].upper()}{key[1:]}": value
                for key, value in compose.stats("ai-retrieval-service").items()
            },
        ),
    )
    maximum_workers = min(
        len(collectors),
        int(positive_env_number("QA_METRICS_MAX_WORKERS", "4")),
    )
    error_types: dict[str, str] = {}
    with ThreadPoolExecutor(
        max_workers=maximum_workers,
        thread_name_prefix="qa-metrics",
    ) as executor:
        futures = {
            executor.submit(collector): name
            for name, collector in collectors
        }
        for future in as_completed(futures):
            name = futures[future]
            try:
                sample.update(future.result())
            except (
                json.JSONDecodeError,
                OSError,
                RuntimeError,
                subprocess.SubprocessError,
                ValueError,
            ) as exception:
                sample["collectionErrors"].append(name)
                error_types[name] = type(exception).__name__
    sample["collectionErrors"].sort()
    if error_types:
        sample["collectionErrorTypes"] = error_types
    return sample


def numeric_summary(samples: list[dict[str, Any]]) -> dict[str, Any]:
    keys = sorted(
        {
            key
            for sample in samples
            for key, value in sample.items()
            if isinstance(value, (int, float)) and not isinstance(value, bool)
        }
    )
    result: dict[str, Any] = {}
    for key in keys:
        values = [
            float(sample[key])
            for sample in samples
            if isinstance(sample.get(key), (int, float))
        ]
        if not values:
            continue
        first = values[0]
        last = values[-1]
        result[key] = {
            "first": first,
            "last": last,
            "minimum": min(values),
            "maximum": max(values),
            "growthPercent": ((last - first) / first * 100) if first else 0.0,
        }
    return result


def request_stop(_signum: int, _frame: object) -> None:
    global STOP_REQUESTED
    STOP_REQUESTED = True


def main() -> None:
    args = parse_args()
    if args.interval_seconds <= 0:
        raise SystemExit("interval-seconds must be positive")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.stop_file.unlink(missing_ok=True)
    compose = ComposeClient(args.compose_project)
    samples: list[dict[str, Any]] = []
    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)

    with args.output.open("w", encoding="utf-8") as output:
        while not STOP_REQUESTED and not args.stop_file.exists():
            sample_started = time.monotonic()
            sample = collect_sample(args, compose)
            samples.append(sample)
            output.write(json.dumps(sample, ensure_ascii=False) + "\n")
            output.flush()
            elapsed = time.monotonic() - sample_started
            time.sleep(max(0, args.interval_seconds - elapsed))

    summary = {
        "sampleCount": len(samples),
        "collectionErrorCount": sum(
            len(sample["collectionErrors"]) for sample in samples
        ),
        "metrics": numeric_summary(samples),
    }
    args.summary.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
