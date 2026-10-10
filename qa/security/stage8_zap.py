"""Scoped ZAP scan: fixed local GET targets, two low-strength active rules."""

from __future__ import annotations

import hashlib
import json
import re
import secrets
import shutil
import socket
import subprocess
import tarfile
import tempfile
import time
from pathlib import Path

import httpx

from qa.security.stage8_support import ROOT, require_loopback, write_records


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def run(seed: dict, output: Path, config: dict) -> list[dict]:
    from qa.scripts.run_stage8 import stop

    require_loopback(seed["baseUrl"])
    archive = ROOT / ".runtime/stage8/ZAP.tar.gz"
    expected = "5b2eb8319b085121a6e8ad50d69d67dbef8c867166f71a937bfc888d247a2ac1"
    with archive.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
            raise ValueError("ZAP official release checksum mismatch")
    distribution = Path(tempfile.mkdtemp(prefix="zap-dist-", dir=archive.parent))
    allowed_plugins = {
        "ascanrules-release-71.zap",
        "callhome-release-0.14.0.zap",
        "commonlib-release-1.31.0.zap",
        "database-alpha-0.8.0.zap",
        "network-beta-0.21.0.zap",
        "oast-beta-0.21.0.zap",
        "pscan-alpha-0.2.1.zap",
        "pscanrules-release-64.zap",
    }
    with tarfile.open(archive) as bundle:
        for member in bundle:
            path = Path(member.name)
            if (
                not member.isfile()
                or path.is_absolute()
                or ".." in path.parts
                or path.parts[0] != "ZAP_2.16.1"
            ):
                continue
            if (
                path.name == "zap-2.16.1.jar"
                or "lib" in path.parts
                or path.name in allowed_plugins
            ):
                bundle.extract(member, distribution, filter="data")
    zap_home = ROOT / ".runtime/stage8" / f"zap-home-{time.time_ns()}"
    zap_home.mkdir(parents=True, exist_ok=True)
    zap_port, web_port = free_port(), free_port()
    api_key = secrets.token_hex(24)
    records = []
    children = []
    log_handles = []
    http = httpx.Client(timeout=config["requestTimeoutSeconds"], trust_env=False)
    api = f"http://127.0.0.1:{zap_port}"
    web = f"http://127.0.0.1:{web_port}"

    def call(component, kind, action, **params):
        response = http.get(
            f"{api}/JSON/{component}/{kind}/{action}/",
            params={"apikey": api_key, **params},
        )
        response.raise_for_status()
        data = response.json()
        if "code" in data:
            raise RuntimeError(f"ZAP API failed: {component}.{action}: {data['code']}")
        return data

    def wait_ready(operation, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                if operation():
                    return
            except httpx.HTTPError:
                pass
            time.sleep(0.2)
        raise TimeoutError("Bounded ZAP wait expired")

    try:
        for name, command, cwd in [
            (
                "zap",
                [
                    "java",
                    "-Xmx512m",
                    "-Djava.awt.headless=true",
                    "-jar",
                    str(distribution / "ZAP_2.16.1/zap-2.16.1.jar"),
                    "-silent",
                    "-daemon",
                    "-host",
                    "127.0.0.1",
                    "-port",
                    str(zap_port),
                    "-dir",
                    str(zap_home),
                    "-config",
                    f"api.key={api_key}",
                    "-config",
                    "autoupdate.checkOnStart=false",
                    "-config",
                    "autoupdate.downloadNewRelease=false",
                    "-config",
                    "connection.timeoutInSecs=5",
                    "-config",
                    "ascan.threadPerHost=1",
                    "-config",
                    "ascan.maxRuleDurationInMins=1",
                    "-config",
                    "ascan.maxScanDurationInMins=3",
                ],
                ROOT,
            ),
            (
                "web-preview",
                [
                    "node",
                    "node_modules/vite/bin/vite.js",
                    "preview",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    str(web_port),
                    "--strictPort",
                ],
                ROOT / "apps/web",
            ),
        ]:
            log = (output.parent / "logs" / f"stage8-{name}.log").open("w")
            log_handles.append(log)
            children.append(
                subprocess.Popen(
                    command,
                    cwd=cwd,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                )
            )
        wait_ready(
            lambda: bool(call("core", "view", "version")),
            config["serverStartupSeconds"],
        )
        wait_ready(
            lambda: http.get(web).status_code == 200, config["serverStartupSeconds"]
        )
        context = call(
            "context", "action", "newContext", contextName="stage8-loopback"
        )["contextId"]
        for origin in (seed["baseUrl"], web):
            call(
                "context",
                "action",
                "includeInContext",
                contextName="stage8-loopback",
                regex=re.escape(origin) + r"/.*",
            )
        call(
            "context",
            "action",
            "setContextInScope",
            contextName="stage8-loopback",
            booleanInScope="true",
        )
        call("core", "action", "setMode", mode="protect")
        policy = "stage8-safe-get"
        call("ascan", "action", "addScanPolicy", scanPolicyName=policy)
        call("ascan", "action", "disableAllScanners", scanPolicyName=policy)
        # No OS-command, traversal, file-write, timing, or external-callback scanner.
        ids = "40012,40018"
        available = call("ascan", "view", "scanners", scanPolicyName=policy)["scanners"]
        if not {"40012", "40018"} <= {str(item["id"]) for item in available}:
            raise RuntimeError("Required safe active scan rules unavailable")
        call("ascan", "action", "enableScanners", ids=ids, scanPolicyName=policy)
        for scanner in ids.split(","):
            call(
                "ascan",
                "action",
                "setScannerAttackStrength",
                id=scanner,
                attackStrength="LOW",
                scanPolicyName=policy,
            )
        paths = seed.get("paths") or [
            f"/api/v1/trips/{seed['tripB']}?stage8Probe=synthetic",
            f"/api/v1/documents/{seed['documentB']}",
            f"/api/v1/plans/{seed['planB']}",
            f"/api/v1/tasks/{seed['taskB']}",
            "/actuator/health",
        ]
        targets = [seed["baseUrl"] + path for path in paths] + [
            web + "/?stage8Probe=synthetic"
        ]
        html = http.get(web).text
        for path in re.findall(r'(?:src|href)="(/assets/[^"]+)"', html):
            targets.append(web + path)
        with httpx.Client(
            proxy=api,
            trust_env=False,
            follow_redirects=False,
            timeout=config["requestTimeoutSeconds"],
        ) as proxy:
            for url in targets:
                proxy.get(url)
        wait_ready(
            lambda: int(call("pscan", "view", "recordsToScan")["recordsToScan"]) == 0,
            config["maxPassiveScanSeconds"],
        )
        records.append(
            {
                "id": "zap.passive",
                "status": "PASSED",
                "targetCount": len(targets),
                "scope": "Fixed local API GET responses and Vite-served production assets, not nginx/TLS ingress.",
            }
        )
        (output / "zap-passive-findings.json").write_text(
            json.dumps(
                call("core", "view", "alerts", start=0, count=config["maxZAPRecords"])[
                    "alerts"
                ],
                indent=2,
            )
            + "\n"
        )
        scans = []
        for url in (targets[0], web + "/?stage8Probe=synthetic"):
            scan = call(
                "ascan",
                "action",
                "scan",
                url=url,
                recurse="false",
                inScopeOnly="true",
                scanPolicyName=policy,
                contextId=context,
                method="GET",
            )["scan"]
            scans.append(scan)
        deadline = time.monotonic() + config["maxActiveScanSeconds"]
        complete = False
        while time.monotonic() < deadline:
            if all(
                call("ascan", "view", "status", scanId=s)["status"] == "100"
                for s in scans
            ):
                complete = True
                break
            time.sleep(0.5)
        call("ascan", "action", "stopAllScans")
        wait_ready(
            lambda: int(call("pscan", "view", "recordsToScan")["recordsToScan"]) == 0,
            config["maxPassiveScanSeconds"],
        )
        alerts = call("core", "view", "alerts", start=0, count=config["maxZAPRecords"])[
            "alerts"
        ]
        summaries = [
            {
                key: alert.get(key)
                for key in (
                    "pluginId",
                    "alert",
                    "risk",
                    "confidence",
                    "cweid",
                    "wascid",
                    "url",
                    "method",
                )
            }
            for alert in alerts
        ]
        (output / "zap-findings.json").write_text(
            json.dumps(summaries, indent=2) + "\n"
        )
        records.append(
            {
                "id": "zap.active",
                "status": "PASSED" if complete else "ERROR",
                "scannerIds": ids.split(","),
                "scanIds": scans,
                "scope": "Two GET URLs with inert query input; XSS/SQL rules LOW, single thread. Not all input sinks.",
            }
        )
        records.append(
            {
                "id": "zap.alertGate",
                "status": "FAILED" if alerts else "PASSED",
                "alertCount": len(alerts),
                "expected": "ZAP alerts need individual disposition.",
            }
        )
        sites = call("core", "view", "sites")["sites"]
        records.append(
            {
                "id": "zap.scope",
                "status": "PASSED"
                if set(sites) <= {seed["baseUrl"], web}
                else "FAILED",
                "observedSites": sites,
            }
        )
        (output / "zap-execution.json").write_text(
            json.dumps(
                {
                    "version": call("core", "view", "version"),
                    "releaseSha256": expected,
                    "scope": targets,
                    "sites": sites,
                    "activePolicy": policy,
                    "activeRules": [
                        item
                        for item in call(
                            "ascan", "view", "scanners", scanPolicyName=policy
                        )["scanners"]
                        if str(item["id"]) in ids.split(",")
                    ],
                    "scanProgress": [
                        call("ascan", "view", "status", scanId=s) for s in scans
                    ],
                },
                indent=2,
            )
            + "\n"
        )
    except Exception as error:
        records.append(
            {
                "id": "zap.execution",
                "status": "ERROR",
                "failureType": type(error).__name__,
                "reason": "Inspect stage8-zap.log; incomplete scan cannot pass.",
            }
        )
        raise
    finally:
        try:
            call("core", "action", "shutdown")
        except (httpx.HTTPError, RuntimeError):
            pass
        http.close()
        for child in reversed(children):
            stop(child)
        for log in log_handles:
            log.close()
        log_path = output.parent / "logs/stage8-zap.log"
        if log_path.exists():
            log_path.write_text(log_path.read_text().replace(api_key, "[REDACTED]"))
        shutil.rmtree(distribution)
        shutil.rmtree(zap_home)
        write_records(output, "stage8-zap", records)
    return records
