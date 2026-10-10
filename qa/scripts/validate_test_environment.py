#!/usr/bin/env python3
"""Fail closed when a default test run could reach a non-test resource."""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
from urllib.parse import urlparse

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
TEST_PROFILE = (
    REPOSITORY_ROOT
    / "apps/trip-agent-api/src/main/resources/application-test.yml"
)
QA_COMPOSE = REPOSITORY_ROOT / "qa/compose.qa.yaml"
ALLOWED_HOSTS = {
    "127.0.0.1",
    "localhost",
    "postgres",
    "redis",
    "kafka",
    "neo4j",
    "toxiproxy",
    "wiremock",
    "ai-retrieval-service",
    "trip-agent-api",
}
MODE_REQUIREMENTS = {
    "MODEL_GATEWAY_PROVIDER": {"mock", "none"},
    "DOCUMENT_PARSER_MODE": {"mock"},
    "RETRIEVAL_PROVIDER_MODE": {"mock"},
    "GRAPH_STORE_MODE": {"memory"},
    "MCP_PROVIDER_MODE": {"mock"},
    "PLANNING_PROVIDER_MODE": {"mock"},
}
REQUIRED_QA_RUNTIME_OVERRIDES = {
    "APP_AGENTS_ENABLED",
    "APP_ASYNC_GOVERNANCE_ENABLED",
    "APP_ASYNC_GOVERNANCE_REDIS_ENABLED",
    "APP_DOCUMENT_PROCESSING_MESSAGING_ENABLED",
    "APP_HEALTH_AI_RETRIEVAL_ENABLED",
    "APP_HEALTH_KAFKA_ENABLED",
    "APP_PLANNING_ENABLED",
    "SPRING_KAFKA_ADMIN_AUTO_CREATE",
}
URL_VARIABLES = (
    "AI_RETRIEVAL_BASE_URL",
    "MCP_BASE_URL",
    "MAP_TILE_BASE_URL",
    "DEEPSEEK_BASE_URL",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path)
    return parser.parse_args()


def host_from_url(value: str) -> str:
    normalized = value.removeprefix("jdbc:postgresql:")
    return (urlparse(normalized).hostname or "").lower()


def require_allowed_host(variable: str, value: str) -> None:
    host = host_from_url(value)
    if host not in ALLOWED_HOSTS:
        raise ValueError(f"{variable} points outside the isolated test network")


def validate() -> dict:
    profile = TEST_PROFILE.read_text(encoding="utf-8")
    compose = QA_COMPOSE.read_text(encoding="utf-8")

    for forbidden in ("deepseek.com", "openstreetmap.org"):
        if forbidden in profile:
            raise ValueError(f"application-test.yml contains forbidden host: {forbidden}")
    if "SPRING_PROFILES_ACTIVE: test,mock-model" not in compose:
        raise ValueError("QA Compose must activate test,mock-model")
    for variable in REQUIRED_QA_RUNTIME_OVERRIDES:
        if not re.search(
            rf"^\s+{re.escape(variable)}:\s*[\"']?true[\"']?\s*$",
            compose,
            re.MULTILINE,
        ):
            raise ValueError(f"QA Compose must explicitly enable {variable}")

    for variable, allowed in MODE_REQUIREMENTS.items():
        value = os.getenv(variable)
        if value is not None and value.lower() not in allowed:
            raise ValueError(f"{variable} must use a test-safe mode")

    if os.getenv("REAL_MODEL_TEST_ENABLED", "false").lower() == "true":
        raise ValueError("Default test commands cannot enable the real model")

    for variable in URL_VARIABLES:
        if value := os.getenv(variable):
            require_allowed_host(variable, value)
    if value := os.getenv("SPRING_DATASOURCE_URL"):
        require_allowed_host("SPRING_DATASOURCE_URL", value)
    if value := os.getenv("SPRING_DATA_REDIS_HOST"):
        if value.lower() not in ALLOWED_HOSTS:
            raise ValueError("SPRING_DATA_REDIS_HOST is outside the test network")
    if value := os.getenv("NEO4J_URI"):
        require_allowed_host("NEO4J_URI", value)
    if value := os.getenv("SPRING_KAFKA_BOOTSTRAP_SERVERS"):
        for server in value.split(","):
            host = re.sub(r":\d+$", "", server.strip()).lower()
            if host not in ALLOWED_HOSTS:
                raise ValueError(
                    "SPRING_KAFKA_BOOTSTRAP_SERVERS is outside the test network"
                )

    return {
        "status": "passed",
        "profile": str(TEST_PROFILE.relative_to(REPOSITORY_ROOT)),
        "activeProfiles": ["test", "mock-model"],
        "allowedHosts": sorted(ALLOWED_HOSTS),
        "runtimeOverrides": sorted(REQUIRED_QA_RUNTIME_OVERRIDES),
        "realModelEnabled": False,
    }


def main() -> None:
    args = parse_args()
    report = validate()
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    print("Test environment isolation validation passed.")


if __name__ == "__main__":
    main()
