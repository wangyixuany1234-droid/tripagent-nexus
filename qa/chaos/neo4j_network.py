#!/usr/bin/env python3
"""Inject real Bolt network latency with an isolated Compose project."""

import asyncio
import os
import runpy
import subprocess
import time
from pathlib import Path
from uuid import uuid4

import httpx
from evidence import experiment, now, threshold
from neo4j import AsyncGraphDatabase
from neo4j.exceptions import Neo4jError, ServiceUnavailable, SessionExpired

ROOT = Path(__file__).resolve().parents[2]
PROJECT = "tripagent-stage7-" + uuid4().hex[:8]


def compose(*arguments: str) -> str:
    result = subprocess.run(
        [
            "docker",
            "compose",
            "-p",
            PROJECT,
            "-f",
            str(ROOT / "qa/chaos/neo4j-network.yaml"),
            *arguments,
        ],
        check=True,
        text=True,
        capture_output=True,
        timeout=threshold("containerStartupTimeoutMs") / 1000,
    )
    return result.stdout.strip()


def port(service: str, container_port: str) -> int:
    return int(compose("port", service, container_port).rsplit(":", 1)[1])


async def run() -> None:
    guard = runpy.run_path(str(ROOT / "qa/scripts/run_stage7.py"))
    guard["require_local_docker"](os.environ.copy())
    try:
        compose("up", "-d")
        api = f"http://127.0.0.1:{port('toxiproxy', '8474')}"
        bolt = f"bolt://127.0.0.1:{port('toxiproxy', '17687')}"
        deadline = time.monotonic() + threshold("containerStartupTimeoutMs") / 1000
        with httpx.Client(timeout=threshold("assertionTimeoutMs") / 1000) as client:
            while True:
                try:
                    client.get(f"{api}/version").raise_for_status()
                    break
                except httpx.HTTPError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError(
                            "Toxiproxy startup deadline exceeded"
                        ) from None
                    await asyncio.sleep(threshold("pollIntervalMs") / 1000)
            client.post(
                f"{api}/proxies",
                json={
                    "name": "neo4j",
                    "listen": "0.0.0.0:17687",
                    "upstream": "neo4j:7687",
                },
            ).raise_for_status()
            async with AsyncGraphDatabase.driver(
                bolt,
                auth=None,
                max_transaction_retry_time=0,
                connection_timeout=threshold("assertionTimeoutMs") / 1000,
            ) as driver:
                while True:
                    try:
                        async with asyncio.timeout(
                            threshold("assertionTimeoutMs") / 1000
                        ):
                            await driver.execute_query("RETURN 1 AS healthy")
                        break
                    except (
                        Neo4jError,
                        ServiceUnavailable,
                        SessionExpired,
                        TimeoutError,
                    ):
                        if time.monotonic() >= deadline:
                            raise TimeoutError(
                                "Neo4j startup deadline exceeded"
                            ) from None
                        await asyncio.sleep(threshold("callTimeoutMs") / 1000)
                await driver.execute_query("CREATE (:Stage7Probe {value: 'synthetic'})")
                with experiment(
                    "retrieval.graphNetwork",
                    "TOXIPROXY_BOLT",
                    "Real Neo4j driver, isolated Neo4j container and Bolt downstream latency",
                ) as record:
                    record["injectedAt"] = now()
                    client.post(
                        f"{api}/proxies/neo4j/toxics",
                        json={
                            "name": "latency",
                            "type": "latency",
                            "stream": "downstream",
                            "attributes": {"latency": threshold("networkLatencyMs")},
                        },
                    ).raise_for_status()
                    try:
                        try:
                            async with asyncio.timeout(
                                threshold("callTimeoutMs") / 1000
                            ):
                                await driver.execute_query(
                                    "MATCH (n:Stage7Probe) RETURN count(n) AS count"
                                )
                            raise AssertionError(
                                "Query unexpectedly completed within injected latency"
                            )
                        except TimeoutError:
                            record["firstDetectedAt"] = now()
                            record["degradationStartedAt"] = now()
                            record["userVisibleImpact"] = (
                                "Driver query cancelled at configured deadline; API fallback tested separately."
                            )
                    finally:
                        client.delete(
                            f"{api}/proxies/neo4j/toxics/latency"
                        ).raise_for_status()
                        record["faultClearedAt"] = now()
                    async with asyncio.timeout(threshold("recoveryTimeoutMs") / 1000):
                        result = await driver.execute_query(
                            "MATCH (n:Stage7Probe) RETURN count(n) AS count"
                        )
                    assert result.records[0]["count"] == 1
                    record["measurements"]["retainedNodes"] = result.records[0]["count"]
                    record["recoveredAt"] = now()
    finally:
        compose("down", "--volumes", "--remove-orphans")


if __name__ == "__main__":
    asyncio.run(run())
