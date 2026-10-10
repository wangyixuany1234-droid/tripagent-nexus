#!/usr/bin/env python3
"""Combine k6 and runtime samples into a CI-readable performance verdict."""

from __future__ import annotations

import argparse
import html
import json
import os
from pathlib import Path
from typing import Any

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
THRESHOLDS = json.loads(
    (REPOSITORY_ROOT / "qa/config/thresholds.json").read_text(encoding="utf-8")
)["performance"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", required=True)
    parser.add_argument("--k6-summary", type=Path, required=True)
    parser.add_argument("--resource-summary", type=Path, required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-html", type=Path, required=True)
    parser.add_argument("--k6-exit-code", type=int, default=0)
    return parser.parse_args()


def metric_values(k6_summary: dict[str, Any], name: str) -> dict[str, float]:
    return k6_summary.get("metrics", {}).get(name, {}).get("values", {})


def env_number(name: str, fallback: float) -> float:
    return float(os.getenv(name, str(fallback)))


def percentile(values: dict[str, float], percentile_name: str) -> float | None:
    value = values.get(percentile_name)
    return float(value) if value is not None else None


def latency_summary(values: dict[str, float]) -> dict[str, float | None]:
    return {
        "p50": percentile(values, "med"),
        "p90": percentile(values, "p(90)"),
        "p95": percentile(values, "p(95)"),
        "p99": percentile(values, "p(99)"),
    }


def threshold_failures(k6_summary: dict[str, Any]) -> list[str]:
    failures = []
    for name, metric in k6_summary.get("metrics", {}).items():
        for expression, state in metric.get("thresholds", {}).items():
            if not state.get("ok", False):
                failures.append(f"{name}: {expression}")
    return failures


def capacity_breakpoint(k6_summary: dict[str, Any]) -> dict[str, Any]:
    limit = float(
        os.getenv(
            "QA_CAPACITY_P95_MS",
            str(THRESHOLDS["write"]["p95Milliseconds"]),
        )
    )
    bands = [
        (25, "capacity_latency_vus_001_025"),
        (50, "capacity_latency_vus_026_050"),
        (100, "capacity_latency_vus_051_100"),
        (200, "capacity_latency_vus_101_200"),
        (400, "capacity_latency_vus_201_400"),
    ]
    observed = []
    breakpoint = None
    for maximum_vus, metric_name in bands:
        values = metric_values(k6_summary, metric_name)
        p95 = percentile(values, "p(95)")
        if p95 is None:
            continue
        observed.append({"maximumVus": maximum_vus, "p95Milliseconds": p95})
        if breakpoint is None and p95 >= limit:
            breakpoint = maximum_vus
    return {
        "latencyLimitMilliseconds": limit,
        "firstBandAtOrAboveLimit": breakpoint,
        "observedBands": observed,
    }


def growth(
    resource_summary: dict[str, Any],
    metric_name: str,
) -> float | None:
    value = resource_summary.get("metrics", {}).get(metric_name)
    return float(value["growthPercent"]) if value else None


def delta(
    resource_summary: dict[str, Any],
    metric_name: str,
) -> float | None:
    value = resource_summary.get("metrics", {}).get(metric_name)
    return float(value["last"] - value["first"]) if value else None


def last_value(
    resource_summary: dict[str, Any],
    metric_name: str,
) -> float | None:
    value = resource_summary.get("metrics", {}).get(metric_name)
    return float(value["last"]) if value else None


def maximum_value(
    resource_summary: dict[str, Any],
    metric_name: str,
) -> float | None:
    value = resource_summary.get("metrics", {}).get(metric_name)
    return float(value["maximum"]) if value else None


def recovery_failures(resource_summary: dict[str, Any]) -> list[str]:
    limits = THRESHOLDS["recovery"]
    required_metrics = {
        "jvmThreadsLive",
        "pythonThreads",
        "hikariConnectionsActive",
        "hikariConnectionsPending",
        "kafkaConsumerLag",
        "redisConnectedClients",
        "tripAgentEstablishedTcpConnections",
        "pythonEstablishedTcpConnections",
    }
    failures = []
    missing = sorted(required_metrics - resource_summary.get("metrics", {}).keys())
    if missing:
        failures.append(f"missing recovery metrics: {', '.join(missing)}")
    if resource_summary.get("sampleCount", 0) < 2:
        failures.append("recovery validation requires at least two runtime samples")

    max_thread_growth = env_number(
        "QA_MAX_FINAL_THREAD_GROWTH_PERCENT",
        limits["maximumFinalThreadGrowthPercent"],
    )
    for name in ("jvmThreadsLive", "pythonThreads"):
        value = growth(resource_summary, name)
        if value is not None and value > max_thread_growth:
            failures.append(
                f"{name} final growth {value:.2f}% exceeds {max_thread_growth}%"
            )

    final_lag = last_value(resource_summary, "kafkaConsumerLag")
    max_final_lag = env_number(
        "QA_MAX_FINAL_KAFKA_LAG",
        limits["maximumFinalKafkaLag"],
    )
    if final_lag is not None and final_lag > max_final_lag:
        failures.append(f"final Kafka lag {final_lag:.0f} exceeds {max_final_lag}")

    final_pending = last_value(resource_summary, "hikariConnectionsPending")
    max_final_pending = env_number(
        "QA_MAX_FINAL_HIKARI_PENDING",
        limits["maximumFinalHikariPending"],
    )
    if final_pending is not None and final_pending > max_final_pending:
        failures.append(
            f"final Hikari pending {final_pending:.0f} exceeds {max_final_pending}"
        )

    hikari_delta = delta(resource_summary, "hikariConnectionsActive")
    max_hikari_increase = env_number(
        "QA_MAX_FINAL_HIKARI_ACTIVE_INCREASE",
        limits["maximumFinalHikariActiveIncrease"],
    )
    if hikari_delta is not None and hikari_delta > max_hikari_increase:
        failures.append(
            f"final Hikari active increase {hikari_delta:.0f} exceeds "
            f"{max_hikari_increase}"
        )

    redis_delta = delta(resource_summary, "redisConnectedClients")
    max_redis_increase = env_number(
        "QA_MAX_FINAL_REDIS_CLIENT_INCREASE",
        limits["maximumFinalRedisClientIncrease"],
    )
    if redis_delta is not None and redis_delta > max_redis_increase:
        failures.append(
            f"final Redis client increase {redis_delta:.0f} exceeds "
            f"{max_redis_increase}"
        )

    max_tcp_increase = env_number(
        "QA_MAX_FINAL_TCP_CONNECTION_INCREASE",
        limits["maximumFinalTcpConnectionIncrease"],
    )
    for name in (
        "tripAgentEstablishedTcpConnections",
        "pythonEstablishedTcpConnections",
    ):
        value = delta(resource_summary, name)
        if value is not None and value > max_tcp_increase:
            failures.append(
                f"{name} final increase {value:.0f} exceeds {max_tcp_increase}"
            )
    return failures


def sse_resource_failures(resource_summary: dict[str, Any]) -> list[str]:
    cpu = maximum_value(resource_summary, "tripAgentContainerCpuPercent")
    if cpu is None:
        return ["missing SSE server CPU metric"]
    maximum_cpu = env_number(
        "QA_SSE_MAX_SERVER_CPU_PERCENT",
        THRESHOLDS["sseConnection"]["maximumServerCpuPercent"],
    )
    if cpu > maximum_cpu:
        return [f"SSE server CPU {cpu:.2f}% exceeds {maximum_cpu}%"]
    return []


def soak_failures(resource_summary: dict[str, Any]) -> list[str]:
    limits = THRESHOLDS["soak"]
    max_rss_growth = env_number(
        "QA_MAX_RSS_GROWTH_PERCENT",
        limits["maxRssGrowthPercent"],
    )
    max_thread_growth = env_number(
        "QA_MAX_THREAD_GROWTH_PERCENT",
        limits["maxThreadGrowthPercent"],
    )
    failures = []
    required_metrics = {
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
    }
    missing = sorted(required_metrics - resource_summary.get("metrics", {}).keys())
    if missing:
        failures.append(f"missing soak metrics: {', '.join(missing)}")
    if resource_summary.get("sampleCount", 0) < 2:
        failures.append("soak test requires at least two runtime samples")

    for name in ("jvmHeapUsedBytes", "pythonRssBytes"):
        value = growth(resource_summary, name)
        if value is not None and value > max_rss_growth:
            failures.append(f"{name} growth {value:.2f}% exceeds {max_rss_growth}%")
    for name in ("jvmThreadsLive", "pythonThreads"):
        thread_growth = growth(resource_summary, name)
        if thread_growth is not None and thread_growth > max_thread_growth:
            failures.append(
                f"{name} growth {thread_growth:.2f}% exceeds {max_thread_growth}%"
            )
    for name, limit in (
        (
            "lanceDbBytes",
            env_number(
                "QA_MAX_LANCEDB_GROWTH_BYTES",
                limits["maxLanceDbGrowthBytes"],
            ),
        ),
        (
            "uploadStorageBytes",
            env_number(
                "QA_MAX_UPLOAD_GROWTH_BYTES",
                limits["maxUploadGrowthBytes"],
            ),
        ),
    ):
        value = delta(resource_summary, name)
        if value is not None and value > limit:
            failures.append(f"{name} growth {value:.0f} bytes exceeds {limit}")
    event_loop_p95 = last_value(resource_summary, "pythonEventLoopLagP95Ms")
    max_event_loop_p95 = env_number(
        "QA_MAX_PYTHON_EVENT_LOOP_P95_MS",
        limits["maxPythonEventLoopLagP95Milliseconds"],
    )
    if event_loop_p95 is not None and event_loop_p95 > max_event_loop_p95:
        failures.append(
            f"python event loop p95 {event_loop_p95:.2f}ms exceeds "
            f"{max_event_loop_p95}ms"
        )
    final_lag = last_value(resource_summary, "kafkaConsumerLag")
    max_final_lag = env_number(
        "QA_MAX_FINAL_KAFKA_LAG",
        limits["maximumFinalKafkaLag"],
    )
    if final_lag is not None and final_lag > max_final_lag:
        failures.append(f"final Kafka lag {final_lag:.0f} exceeds {max_final_lag}")
    final_pending = last_value(resource_summary, "hikariConnectionsPending")
    max_final_pending = env_number(
        "QA_MAX_FINAL_HIKARI_PENDING",
        limits["maximumFinalHikariPending"],
    )
    if final_pending is not None and final_pending > max_final_pending:
        failures.append(
            f"final Hikari pending {final_pending:.0f} exceeds {max_final_pending}"
        )
    hikari_active_delta = delta(resource_summary, "hikariConnectionsActive")
    max_hikari_increase = env_number(
        "QA_MAX_FINAL_HIKARI_ACTIVE_INCREASE",
        limits["maximumFinalHikariActiveIncrease"],
    )
    if hikari_active_delta is not None and hikari_active_delta > max_hikari_increase:
        failures.append(
            f"final Hikari active increase {hikari_active_delta:.0f} exceeds "
            f"{max_hikari_increase}"
        )
    max_tcp_increase = env_number(
        "QA_MAX_FINAL_TCP_CONNECTION_INCREASE",
        limits["maximumFinalTcpConnectionIncrease"],
    )
    for name in (
        "tripAgentEstablishedTcpConnections",
        "pythonEstablishedTcpConnections",
    ):
        connection_delta = delta(resource_summary, name)
        if connection_delta is not None and connection_delta > max_tcp_increase:
            failures.append(
                f"{name} final increase {connection_delta:.0f} exceeds "
                f"{max_tcp_increase}"
            )
    running_tasks = last_value(resource_summary, "runningTasks")
    max_running_tasks = env_number(
        "QA_MAX_RUNNING_TASKS",
        limits["maximumRunningTasks"],
    )
    if running_tasks is not None and running_tasks > max_running_tasks:
        failures.append(
            f"running tasks {running_tasks:.0f} exceeds {max_running_tasks}"
        )
    return failures


def consistency_failures(
    profile: str,
    k6_summary: dict[str, Any],
) -> list[str]:
    failures = []
    if profile == "idempotency":
        values = metric_values(k6_summary, "idempotency_resource_fingerprint")
        if not values or values.get("min") is None or values.get("max") is None:
            failures.append("no successful idempotent resource response")
        elif values.get("min") != values.get("max"):
            failures.append(
                "concurrent idempotency requests returned different resources"
            )
    if profile == "race":
        successful = metric_values(k6_summary, "race_successful_writes").get("count", 0)
        conflicts = metric_values(k6_summary, "race_rejected_writes").get("count", 0)
        expected = int(os.getenv("QA_VUS", "20"))
        if successful == 0:
            failures.append("no concurrent write won the optimistic-lock race")
        elif successful > 1:
            failures.append(f"{int(successful - 1)} writes were silently overwritten")
        if conflicts != expected - 1:
            failures.append(
                f"{int(conflicts)} of {expected - 1} writes reported explicit conflicts"
            )
    return failures


def build_summary(
    profile: str,
    k6_summary: dict[str, Any],
    resource_summary: dict[str, Any],
    k6_exit_code: int,
) -> dict[str, Any]:
    duration = metric_values(k6_summary, "http_req_duration")
    requests = metric_values(k6_summary, "http_reqs")
    failure_rate = metric_values(k6_summary, "http_req_failed")
    failures = threshold_failures(k6_summary)
    failures.extend(consistency_failures(profile, k6_summary))
    if resource_summary.get("sampleCount", 0) < 1:
        failures.append("no runtime resource metrics were collected")
    collection_errors = int(resource_summary.get("collectionErrorCount", 0))
    if collection_errors > 0:
        failures.append(
            f"runtime metric collection reported {collection_errors} errors"
        )
    if profile == "soak":
        failures.extend(soak_failures(resource_summary))
    if profile == "spike":
        failures.extend(recovery_failures(resource_summary))
    if profile == "sse":
        failures.extend(sse_resource_failures(resource_summary))
    if k6_exit_code != 0 and not failures:
        failures.append(f"k6 exited with code {k6_exit_code}")
    return {
        "profile": profile,
        "status": "PASSED" if not failures and k6_exit_code == 0 else "FAILED",
        "k6ExitCode": k6_exit_code,
        "throughputRequestsPerSecond": requests.get("rate"),
        "latencyMilliseconds": latency_summary(duration),
        "apiGroups": {
            "synchronous": latency_summary(
                metric_values(k6_summary, "sync_duration")
            ),
            "write": latency_summary(metric_values(k6_summary, "write_duration")),
            "asyncSubmit": latency_summary(
                metric_values(k6_summary, "async_submit_duration")
            ),
            "asyncCompletion": latency_summary(
                metric_values(k6_summary, "async_completion_duration")
            ),
            "sseConnect": latency_summary(
                metric_values(k6_summary, "sse_connect_duration")
            ),
        },
        "httpErrorRate": failure_rate.get("rate"),
        "capacityBreakpoint": capacity_breakpoint(k6_summary),
        "resourceMetrics": resource_summary,
        "resourceCollectionWarnings": {
            "collectionErrorCount": resource_summary.get(
                "collectionErrorCount",
                0,
            )
        },
        "failures": failures,
    }


def build_html(summary: dict[str, Any]) -> str:
    latency = summary["latencyMilliseconds"]
    group_rows = []
    for name, values in summary["apiGroups"].items():
        group_rows.append(
            "<tr>"
            f"<td>{html.escape(name)}</td>"
            f"<td>{values['p50']}</td>"
            f"<td>{values['p90']}</td>"
            f"<td>{values['p95']}</td>"
            f"<td>{values['p99']}</td>"
            "</tr>"
        )
    resource_rows = []
    for name, values in summary["resourceMetrics"].get("metrics", {}).items():
        resource_rows.append(
            "<tr>"
            f"<td>{html.escape(name)}</td>"
            f"<td>{values['first']:.3f}</td>"
            f"<td>{values['maximum']:.3f}</td>"
            f"<td>{values['last']:.3f}</td>"
            f"<td>{values['growthPercent']:.3f}%</td>"
            "</tr>"
        )
    failures = (
        "".join(f"<li>{html.escape(item)}</li>" for item in summary["failures"])
        or "<li>无</li>"
    )
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>阶段五性能报告 {html.escape(summary["profile"])}</title>
  <style>
    body {{ font: 14px/1.5 system-ui, sans-serif; margin: 32px; color: #17202a; }}
    table {{ border-collapse: collapse; width: 100%; margin: 16px 0 28px; }}
    th, td {{ border: 1px solid #d9e0e7; padding: 8px 10px; text-align: right; }}
    th:first-child, td:first-child {{ text-align: left; }}
    th {{ background: #eef2f5; }}
  </style>
</head>
<body>
  <h1>阶段五性能报告：{html.escape(summary["profile"])}</h1>
  <p>状态：{html.escape(summary["status"])}</p>
  <table>
    <tr><th>吞吐量 req/s</th><th>p50 ms</th><th>p90 ms</th><th>p95 ms</th><th>p99 ms</th><th>错误率</th></tr>
    <tr>
      <td>{summary["throughputRequestsPerSecond"]}</td>
      <td>{latency["p50"]}</td><td>{latency["p90"]}</td>
      <td>{latency["p95"]}</td><td>{latency["p99"]}</td>
      <td>{summary["httpErrorRate"]}</td>
    </tr>
  </table>
  <h2>接口组延迟</h2>
  <table>
    <tr><th>接口组</th><th>p50 ms</th><th>p90 ms</th><th>p95 ms</th><th>p99 ms</th></tr>
    {"".join(group_rows)}
  </table>
  <h2>运行时资源</h2>
  <table>
    <tr><th>指标</th><th>初始</th><th>最大</th><th>结束</th><th>增长</th></tr>
    {"".join(resource_rows)}
  </table>
  <h2>失败项</h2>
  <ul>{failures}</ul>
</body>
</html>
"""


def main() -> None:
    args = parse_args()
    k6_summary = json.loads(args.k6_summary.read_text(encoding="utf-8"))
    resource_summary = json.loads(args.resource_summary.read_text(encoding="utf-8"))
    summary = build_summary(
        args.profile,
        k6_summary,
        resource_summary,
        args.k6_exit_code,
    )
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_html.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    args.output_html.write_text(build_html(summary), encoding="utf-8")
    if summary["status"] != "PASSED":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
