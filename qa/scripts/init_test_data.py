#!/usr/bin/env python3
"""Create a deterministic, disposable copy of the synthetic QA fixture set."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_ROOT = REPOSITORY_ROOT / "qa/fixtures"
DEFAULT_TARGET = REPOSITORY_ROOT / ".runtime/test-data"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", type=Path, default=DEFAULT_TARGET)
    parser.add_argument("--report", type=Path)
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest() -> dict:
    manifest = json.loads((FIXTURE_ROOT / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("classification") != "synthetic":
        raise ValueError("Fixture manifest must be classified as synthetic")
    if not isinstance(manifest.get("randomSeed"), int):
        raise ValueError("Fixture manifest must define an integer randomSeed")
    return manifest


def validate_target(target: Path) -> Path:
    resolved = target.expanduser().resolve()
    runtime_root = (REPOSITORY_ROOT / ".runtime").resolve()
    if resolved != runtime_root and runtime_root not in resolved.parents:
        raise ValueError("Test data target must be inside the repository .runtime directory")
    return resolved


def initialize(target: Path) -> dict:
    manifest = load_manifest()
    target = validate_target(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    entries = manifest.get("fixtures", [])
    ids: set[str] = set()
    paths: set[str] = set()
    index_entries: list[dict] = []

    with tempfile.TemporaryDirectory(
        prefix="test-data-stage-",
        dir=target.parent,
    ) as temporary:
        stage = Path(temporary)
        for entry in entries:
            fixture_id = entry["id"]
            relative_path = entry["path"]
            if fixture_id in ids or relative_path in paths:
                raise ValueError("Fixture manifest IDs and paths must be unique")
            ids.add(fixture_id)
            paths.add(relative_path)

            source = (FIXTURE_ROOT / relative_path).resolve()
            if FIXTURE_ROOT.resolve() not in source.parents or not source.is_file():
                raise ValueError(f"Fixture does not exist: {relative_path}")
            if source.suffix == ".json":
                json.loads(source.read_text(encoding="utf-8"))

            destination = stage / relative_path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            index_entries.append(
                {
                    **entry,
                    "bytes": source.stat().st_size,
                    "sha256": sha256(source),
                }
            )

        shutil.copy2(FIXTURE_ROOT / "manifest.json", stage / "manifest.json")
        index = {
            "schemaVersion": manifest["schemaVersion"],
            "classification": manifest["classification"],
            "randomSeed": manifest["randomSeed"],
            "fixtures": sorted(index_entries, key=lambda item: item["id"]),
        }
        (stage / "fixture-index.json").write_text(
            json.dumps(index, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(stage, target)

    return index


def main() -> None:
    args = parse_args()
    index = initialize(args.target)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(
            json.dumps(index, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    print(
        f"Initialized {len(index['fixtures'])} synthetic fixtures "
        f"with seed {index['randomSeed']}."
    )


if __name__ == "__main__":
    main()
