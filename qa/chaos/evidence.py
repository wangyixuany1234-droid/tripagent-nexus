"""Persist measured chaos timelines without inventing task or trace statistics."""

from __future__ import annotations

import json
import os
import re
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
CONFIG = json.loads((ROOT / "qa/config/chaos-stage7.json").read_text())


def threshold(name: str) -> int:
    env = "QA_CHAOS_" + re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name).upper()
    value = int(os.getenv(env, CONFIG["thresholds"][name]))
    if value < 0:
        raise ValueError(f"Invalid chaos threshold: {name}")
    return value


def now() -> str:
    return datetime.now(UTC).isoformat()


@contextmanager
def experiment(identifier: str, mode: str, scope: str):
    record = {
        "schemaVersion": 1,
        "id": identifier,
        "injectionMode": mode,
        "impactScope": scope,
        "status": "FAILED",
        "injectedAt": None,
        "firstDetectedAt": None,
        "degradationStartedAt": None,
        "faultClearedAt": None,
        "recoveredAt": None,
        "lostTasks": None,
        "duplicateTasks": None,
        "taskAccounting": "Not applicable: synchronous retrieval, no task submitted",
        "traceIds": [],
        "taskIds": [],
        "traceScope": "HTTP request correlation, not an exported distributed trace",
        "userVisibleImpact": "Not observed at the user interface",
        "measurements": {},
    }
    try:
        yield record
        for key in ("injectedAt", "firstDetectedAt", "recoveredAt"):
            if not record[key]:
                raise ValueError("CHAOS_TIMELINE_INCOMPLETE")
        record["status"] = "PASSED"
    except BaseException as exc:
        record["failureType"] = type(exc).__name__
        raise
    finally:
        record["finishedAt"] = now()
        output = Path(
            os.getenv(
                "QA_CHAOS_OUTPUT",
                str(ROOT / "output/test-results/stage7-local/experiments"),
            )
        )
        output.mkdir(parents=True, exist_ok=True)
        (output / f"{identifier}-{uuid4()}.json").write_text(
            json.dumps(record, ensure_ascii=False, indent=2) + "\n"
        )
