#!/usr/bin/env python3
"""Reject unsafe test targets before load, fault, security, or model tests."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import socket
import sys
from pathlib import Path
from urllib.parse import urlparse

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
THRESHOLDS = json.loads(
    (REPOSITORY_ROOT / "qa/config/thresholds.json").read_text(encoding="utf-8")
)
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1", "host.docker.internal"}
DENIED_THIRD_PARTY_SUFFIXES = (
    ".deepseek.com",
    ".openstreetmap.org",
    ".weather.com",
    ".amap.com",
    ".gaode.com",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--kind",
        required=True,
        choices=("performance", "security", "chaos", "real-model"),
    )
    parser.add_argument("--base-url", required=True)
    parser.add_argument(
        "--concurrency",
        type=int,
        default=int(os.getenv("REAL_MODEL_MAX_CONCURRENCY", "1")),
    )
    parser.add_argument(
        "--max-calls",
        type=int,
        default=int(os.getenv("REAL_MODEL_MAX_CALLS", "1")),
    )
    return parser.parse_args()


def is_local(hostname: str) -> bool:
    if hostname in LOCAL_HOSTS:
        return True
    try:
        return ipaddress.ip_address(socket.gethostbyname(hostname)).is_loopback
    except (OSError, ValueError):
        return False


def fail(message: str) -> None:
    print(f"测试环境门禁拒绝执行：{message}", file=sys.stderr)
    raise SystemExit(2)


def main() -> None:
    args = parse_args()
    parsed = urlparse(args.base_url)
    hostname = (parsed.hostname or "").lower()

    if parsed.scheme not in {"http", "https"} or not hostname:
        fail("BASE_URL 必须是完整的 HTTP(S) 地址")
    if any(
        hostname == suffix[1:] or hostname.endswith(suffix)
        for suffix in DENIED_THIRD_PARTY_SUFFIXES
    ):
        fail("目标属于禁止压测或扫描的第三方服务")

    authorized_remote = os.getenv("QA_AUTHORIZED_TEST_ENV", "").lower() == "true"
    if not is_local(hostname) and not authorized_remote:
        fail("非本地目标必须显式设置 QA_AUTHORIZED_TEST_ENV=true")

    if args.kind != "real-model":
        return

    if os.getenv("REAL_MODEL_TEST_ENABLED", "").lower() != "true":
        fail("真实模型测试必须显式设置 REAL_MODEL_TEST_ENABLED=true")

    limits = THRESHOLDS["realModel"]
    if args.concurrency < 1 or args.concurrency > limits["maxConcurrency"]:
        fail(f"真实模型并发数不得超过 {limits['maxConcurrency']}")
    if args.max_calls < 1 or args.max_calls > limits["maxCalls"]:
        fail(f"真实模型最大调用数不得超过 {limits['maxCalls']}")


if __name__ == "__main__":
    main()
