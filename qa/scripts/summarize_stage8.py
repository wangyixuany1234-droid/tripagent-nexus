#!/usr/bin/env python3
"""Evidence-based stage-eight release gate; missing evidence always blocks."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from qa.security.stage8_support import load_config, write_records


def evaluate_release(
    findings: list[dict],
    required: dict[str, bool],
    dependencies: list[dict],
    policy: dict,
) -> dict:
    open_findings = [item for item in findings if item["status"] != "FIXED"]
    counts = Counter(item["severity"] for item in open_findings)
    reasons = []
    for severity in ("Critical", "High"):
        if counts[severity] > policy[severity]:
            reasons.append(f"{severity} 应用问题未清零")
    unresolved_medium = [
        item["id"]
        for item in open_findings
        if item["severity"] == "Medium"
        and not (
            item.get("disposition") == "ACCEPTED"
            and item.get("acceptanceRecord")
            and item.get("approvedBy")
        )
    ]
    if policy["MediumRequiresDisposition"] and unresolved_medium:
        reasons.append("Medium 尚未修复，也无批准接受记录")
    if any(item["severity"] in {"CRITICAL", "HIGH"} for item in dependencies):
        reasons.append("依赖存在未关闭的 Critical/High 告警；应用可利用性未据此推定")
    reasons.extend(
        f"证据缺失或未通过：{name}" for name, passed in required.items() if not passed
    )
    return {
        "status": "BLOCKED" if reasons else "PASSED",
        "applicationOpenCounts": dict(counts),
        "unresolvedMedium": unresolved_medium,
        "requiredEvidence": required,
        "reasons": reasons,
    }


def summarize(runs: list[Path]) -> dict:
    used: set[Path] = set()

    def latest(name):
        for run in reversed(runs):
            candidate = run / "json" / name
            if candidate.is_file():
                used.add(candidate)
                return json.loads(candidate.read_text())
        return None

    def count_xml(pattern):
        for run in reversed(runs):
            paths = sorted(run.glob(pattern))
            if not paths:
                continue
            counts = Counter(tests=0, failures=0, errors=0, skipped=0)
            for path in paths:
                used.add(path)
                root = ET.parse(path).getroot()
                suites = (
                    [root]
                    if root.tag == "testsuite"
                    else list(root.findall("testsuite"))
                )
                for suite in suites:
                    for key in counts:
                        counts[key] += int(suite.get(key, 0))
            counts["passed"] = (
                counts["tests"]
                - counts["failures"]
                - counts["errors"]
                - counts["skipped"]
            )
            return dict(counts)
        return {"status": "NOT_EXECUTED"}

    def successful(suite):
        for run in reversed(runs):
            report = run / "json" / (suite + ".json")
            if report.is_file():
                used.add(report)
                return json.loads(report.read_text()).get("exitCode") == 0
            batch_file = run / "json/stage8-batches.json"
            if batch_file.is_file():
                for item in reversed(json.loads(batch_file.read_text())):
                    if item["id"] == suite:
                        used.add(batch_file)
                        return item["status"] == "PASSED"
        return False

    artifacts = latest("stage8-artifacts.json") or []
    images = latest("stage8-images.json") or []
    zap = latest("stage8-zap.json") or []
    zap_details = latest("zap-execution.json") or {}
    zap_findings = latest("zap-findings.json") or []
    api = latest("stage8-api.json") or []
    semgrep = latest("semgrep-findings.json") or {}
    trivy = latest("trivy-findings.json") or {}
    java_deps = latest("trivy-java-sbom-findings.json") or {}
    secrets = latest("gitleaks-current-findings.json")
    build_secrets = latest("gitleaks-build-findings.json")
    history_secrets = latest("gitleaks-history-findings.json")
    sbom = latest("java-runtime-components.json") or {}
    npm_audit = {}
    for run in reversed(runs):
        log = run / "logs/npm-audit.log"
        if log.is_file():
            used.add(log)
            try:
                npm_audit, _ = json.JSONDecoder().raw_decode(log.read_text().lstrip())
            except json.JSONDecodeError:
                pass
            break
    dependencies = {}
    for document in (trivy, java_deps):
        for result in document.get("Results", []):
            for item in result.get("Vulnerabilities", []):
                key = (
                    item["VulnerabilityID"],
                    item["PkgName"],
                    item["InstalledVersion"],
                )
                dependencies[key] = {
                    "id": key[0],
                    "package": key[1],
                    "version": key[2],
                    "fixedVersion": item.get("FixedVersion"),
                    "severity": item["Severity"],
                    "disposition": "FIX_REQUIRED",
                    "exploitability": "NOT_ESTABLISHED",
                }
    triage_path = ROOT / "qa/security/stage8-findings.json"
    used.add(triage_path)
    triage = json.loads(triage_path.read_text())
    required = {
        "currentGitleaks": secrets == [] and successful("gitleaks-current"),
        "frontendGitleaks": build_secrets == [] and successful("gitleaks-build"),
        "frontendBackendSecretIsolation": any(
            item["id"] == "build.serverSecretIsolation" and item["status"] == "PASSED"
            for item in artifacts
        ),
        "gitHistoryGitleaks": history_secrets == [] and successful("gitleaks-history"),
        "builtApplicationImages": bool(images)
        and all(item["status"] == "PASSED" for item in images),
        "javaHttpSecurity": bool(api)
        and all(item["status"] in {"PASSED", "NOT_APPLICABLE"} for item in api),
        "zapScopedExecution": all(
            any(item["id"] == name and item["status"] == "PASSED" for item in zap)
            for name in ("zap.passive", "zap.active", "zap.scope")
        ),
        "semgrepExecution": bool(semgrep.get("paths", {}).get("scanned"))
        and not semgrep.get("errors"),
        "trivyFilesystemExecution": bool(trivy.get("Results")),
        "javaRuntimeDependencyExecution": bool(sbom.get("components"))
        and bool(java_deps.get("Results")),
        "npmAuditExecution": npm_audit.get("auditReportVersion") == 2,
        "pythonAuditExecution": successful("pip-audit"),
    }
    summary = {
        "generatedAt": datetime.now(UTC).isoformat(),
        "scope": "本地、合成数据、Mock 外部服务；结论以 releaseGate 及覆盖清单为准",
        "runs": [str(path.relative_to(ROOT)) for path in runs],
        "tests": {
            "javaSecurity": count_xml("junit/java-security/TEST-*.xml"),
            "pythonSecurity": count_xml("junit/python-security.xml"),
            "browserRendering": count_xml("junit/browser-security.xml"),
            "harness": count_xml("junit/stage8-harness.xml"),
        },
        "scans": {
            "semgrepRawFindings": len(semgrep.get("results", [])),
            "semgrepErrors": semgrep.get("errors", []),
            "semgrepScannedFiles": len(semgrep.get("paths", {}).get("scanned", [])),
            "javaRuntimeComponents": len(sbom.get("components", [])),
            "npmAuditMetadata": npm_audit.get("metadata", {}),
            "pythonAudit": "PASSED" if successful("pip-audit") else "NOT_VERIFIED",
            "dependencyPackageAdvisoryCount": len(dependencies),
            "dependencySeverityCounts": dict(
                Counter(item["severity"] for item in dependencies.values())
            ),
            "gitleaksCurrent": "PASSED"
            if required["currentGitleaks"]
            else "NOT_VERIFIED",
            "gitleaksFrontend": "PASSED"
            if required["frontendGitleaks"]
            else "NOT_VERIFIED",
        },
        "applicationFindings": triage["findings"],
        "triage": triage["triage"],
        "dependencies": list(dependencies.values()),
        "artifacts": artifacts,
        "images": images,
        "zap": {"records": zap, "alerts": zap_findings, "execution": zap_details},
        "fastapiHttp": latest("stage8-fastapi-http.json") or [],
        "releaseGate": evaluate_release(
            triage["findings"],
            required,
            list(dependencies.values()),
            load_config()["releaseGate"],
        ),
        "evidence": [],
    }
    for path in sorted(used):
        summary["evidence"].append(
            {
                "path": str(path.relative_to(ROOT)),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
        )
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True, help="Existing output run directory")
    parser.add_argument("--include-run", action="append", default=[])
    args = parser.parse_args()
    runs = [Path(value).resolve() for value in [*args.include_run, args.run]]
    results_root = (ROOT / "output/test-results").resolve()
    if any(not path.is_relative_to(results_root) or not path.is_dir() for path in runs):
        parser.error("All evidence directories must exist under output/test-results")
    summary = summarize(runs)
    output = runs[-1]
    (output / "json").mkdir(exist_ok=True)
    (output / "html").mkdir(exist_ok=True)
    (output / "json/stage8-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n"
    )
    rows = [
        {
            "id": name,
            "status": "PASSED" if passed else "FAILED",
            "expected": "Required release evidence must be complete and passing.",
        }
        for name, passed in summary["releaseGate"]["requiredEvidence"].items()
    ]
    rows.append(
        {
            "id": "stage8.release",
            "status": "FAILED"
            if summary["releaseGate"]["status"] == "BLOCKED"
            else "PASSED",
            "expected": "Critical/High zero, Medium disposition and complete required evidence.",
        }
    )
    write_records(output / "json", "stage8-release", rows)
    table = "".join(
        "<tr><td>"
        + html.escape(item["id"])
        + "</td><td>"
        + html.escape(item["severity"])
        + "</td><td>"
        + html.escape(item["title"])
        + "</td><td>"
        + html.escape(item["disposition"])
        + "</td></tr>"
        for item in summary["applicationFindings"]
    )
    label = "发布阻断" if summary["releaseGate"]["status"] == "BLOCKED" else "通过"
    page = (
        """<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>阶段八安全验收</title><style>
body{font:15px/1.6 system-ui;margin:32px;color:#182026;background:#fff}
table{border-collapse:collapse;width:100%}td,th{border:1px solid #bbb;padding:8px;text-align:left}
pre{white-space:pre-wrap;overflow-wrap:anywhere}h1{font-size:24px}
</style><h1>阶段八安全验收："""
        + label
        + """</h1>
<p>仅本地合成数据。失败、未执行与有限范围通过分别计数。</p>
<h2>应用问题</h2><table><tr><th>编号</th><th>等级</th><th>问题</th><th>处置</th></tr>"""
        + table
        + """</table>
<h2>实测统计</h2><pre>"""
        + html.escape(json.dumps(summary["tests"], ensure_ascii=False, indent=2))
        + """</pre>
<h2>发布门禁</h2><pre>"""
        + html.escape(json.dumps(summary["releaseGate"], ensure_ascii=False, indent=2))
        + """</pre>
<p>详细范围、修复记录与未执行清单见仓库 docs/测试阶段八-应用安全测试验收.md。</p></html>"""
    )
    (output / "html/stage8.html").write_text(page, encoding="utf-8")
    print(json.dumps(summary["releaseGate"], ensure_ascii=False, indent=2))
    return int(summary["releaseGate"]["status"] != "PASSED")


if __name__ == "__main__":
    raise SystemExit(main())
