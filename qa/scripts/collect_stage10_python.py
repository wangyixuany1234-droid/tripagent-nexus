#!/usr/bin/env python3
"""Collect fresh OCR and RAG observations from the FastAPI service."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import random
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any
from uuid import NAMESPACE_URL, UUID, uuid5

from fastapi.testclient import TestClient
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[2]
SERVICE_ROOT = ROOT / "services/ai-retrieval-service"
sys.path.insert(0, str(SERVICE_ROOT / "src"))
os.environ.setdefault("APP_ENV", "test")
os.environ.setdefault("AI_SERVICE_TOKEN", "test-service-token")
os.environ.setdefault("NEO4J_PASSWORD", "test-neo4j-password")
os.environ.setdefault("DOCUMENT_PARSER_MODE", "mock")
os.environ.setdefault("RETRIEVAL_PROVIDER_MODE", "mock")
os.environ.setdefault("GRAPH_STORE_MODE", "memory")
os.environ.setdefault("DEEPSEEK_BASE_URL", "http://127.0.0.1:65535")
os.environ.setdefault("ALLOWED_EXTERNAL_HOSTS", "localhost,127.0.0.1")
os.environ.setdefault("OTEL_ENABLED", "false")

from trip_agent_ai.core.config import Settings
from trip_agent_ai.main import create_app

AUTH = {"Authorization": "Bearer test-service-token"}
TENANT_ID = UUID("10000000-0000-0000-0000-000000000001")
USER_A = UUID("20000000-0000-0000-0000-000000000001")
USER_B = UUID("20000000-0000-0000-0000-000000000002")


class RecordingReranker:
    def __init__(self, delegate: Any) -> None:
        self._delegate = delegate
        self.mode = delegate.mode
        self.before: list[str] = []
        self.after: list[str] = []

    async def rerank(self, query: str, candidates: list[Any]) -> list[Any]:
        self.before = [candidate.source_id for candidate in candidates]
        reranked = await self._delegate.rerank(query, candidates)
        self.after = [candidate.source_id for candidate in reranked]
        return reranked


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--repetition", type=int, required=True)
    args = parser.parse_args()

    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    random.seed(int(dataset["random"]["seed"]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.artifacts.mkdir(parents=True, exist_ok=True)
    if args.runtime.exists():
        shutil.rmtree(args.runtime)
    args.runtime.mkdir(parents=True)
    try:
        observations = {
            "datasetVersion": dataset["version"],
            "repetition": args.repetition,
            "mode": "MOCK",
            "ocr": collect_ocr(dataset, args.artifacts, args.runtime / "ocr"),
            "rag": collect_rag(dataset, args.runtime / "rag"),
        }
        args.output.write_text(
            json.dumps(observations, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    finally:
        shutil.rmtree(args.runtime, ignore_errors=True)
    return 0


def collect_ocr(dataset: dict, artifacts: Path, runtime: Path) -> list[dict]:
    settings = _settings(runtime / "lancedb", document_parser_mode="mock")
    app = create_app(settings)
    results: list[dict] = []
    with TestClient(app) as client:
        for case in dataset["ocrCases"]:
            filename, media_type, content = _build_ocr_artifact(case, artifacts)
            response = client.post(
                "/internal/v1/documents/parse",
                headers=AUTH,
                data={
                    "documentId": str(uuid5(NAMESPACE_URL, case["id"] + ":document")),
                    "taskId": str(uuid5(NAMESPACE_URL, case["id"] + ":task")),
                    "mediaType": media_type,
                    "sha256": hashlib.sha256(content).hexdigest(),
                    "locale": case["locale"],
                },
                files={"file": (filename, content, media_type)},
            )
            results.append(
                {
                    "id": case["id"],
                    "scenario": case["scenario"],
                    "statusCode": response.status_code,
                    "response": response.json(),
                    "artifact": f"ocr/{filename}",
                    "artifactSha256": hashlib.sha256(content).hexdigest(),
                }
            )
    return results


def collect_rag(dataset: dict, runtime: Path) -> list[dict]:
    settings = _settings(runtime / "lancedb", document_parser_mode="mock")
    app = create_app(settings)
    fixture_root = ROOT / "qa/fixtures/retrieval"
    knowledge = _load_json(fixture_root / "public-knowledge.json")
    knowledge["chunks"].extend(dataset["ragSupplementalKnowledge"]["chunks"])
    memory_a = _load_json(fixture_root / "memory-user-a.json")
    memory_b = _load_json(fixture_root / "memory-user-b.json")
    results: list[dict] = []

    with TestClient(app) as client:
        _require_success(
            client.post(
                "/internal/v1/knowledge/ingest",
                headers={**AUTH, "Idempotency-Key": "stage10-knowledge-v1"},
                json=knowledge,
            )
        )
        _require_success(
            client.post(
                "/internal/v1/memory/ingest",
                headers=_memory_headers(USER_A, "stage10-memory-a-v1"),
                json=memory_a,
            )
        )
        _require_success(
            client.post(
                "/internal/v1/memory/ingest",
                headers=_memory_headers(USER_B, "stage10-memory-b-v1"),
                json=memory_b,
            )
        )
        service = client.app.state.retrieval_service
        recorder = RecordingReranker(service._reranker)
        service._reranker = recorder
        for case in dataset["ragCases"]:
            headers = dict(AUTH)
            if case.get("user"):
                headers.update(
                    {
                        "X-Tenant-Id": str(TENANT_ID),
                        "X-User-Id": str(USER_A if case["user"] == "A" else USER_B),
                    }
                )
            recorder.before = []
            recorder.after = []
            response = client.post(
                "/internal/v1/retrieval/search",
                headers=headers,
                json=case["request"],
            )
            results.append(
                {
                    "id": case["id"],
                    "scenario": case["scenario"],
                    "statusCode": response.status_code,
                    "beforeRerank": list(recorder.before),
                    "afterRerank": list(recorder.after),
                    "response": response.json(),
                }
            )
    return results


def _build_ocr_artifact(case: dict, artifacts: Path) -> tuple[str, str, bytes]:
    kind = case["transform"]["kind"]
    media_type = case["mediaType"]
    suffix = ".pdf" if media_type == "application/pdf" else ".png"
    filename = f"{case['id']}{suffix}"
    target = artifacts / filename

    if kind == "identity" and media_type == "application/pdf":
        content = _fixture_path(case["source"]).read_bytes()
        target.write_bytes(content)
        return filename, media_type, content

    image = _source_image(case)
    transform = case["transform"]
    if kind == "gaussianBlur":
        image = image.filter(ImageFilter.GaussianBlur(float(transform["radius"])))
    elif kind == "rotate":
        image = image.rotate(
            float(transform["degrees"]),
            expand=True,
            fillcolor=(255, 255, 255),
        )
    elif kind == "darkMode":
        image = ImageEnhance.Contrast(ImageOps.invert(image)).enhance(1.15)
    elif kind == "multilingualOverlay":
        draw = ImageDraw.Draw(image)
        draw.rectangle((40, 40, 1240, 110), fill=(28, 28, 28))
        draw.text(
            (70, 58),
            transform["text"],
            fill=(245, 245, 245),
            font=_font(28),
        )
    elif kind == "occlude":
        ImageDraw.Draw(image).rectangle(tuple(transform["box"]), fill=(120, 120, 120))
    elif kind == "abnormalFormat":
        image = Image.new("RGB", (1280, 720), "white")
        draw = ImageDraw.Draw(image)
        for index, line in enumerate(transform["lines"]):
            draw.text((80, 100 + index * 105), line, fill="black", font=_font(30))
    elif kind == "irrelevantImage":
        image = Image.new("RGB", (1280, 720), (226, 239, 244))
        draw = ImageDraw.Draw(image)
        draw.ellipse((170, 130, 510, 470), fill=(76, 140, 120))
        draw.rectangle((650, 220, 1110, 560), fill=(210, 177, 92))
        draw.line((100, 620, 1180, 620), fill=(45, 65, 72), width=8)
    elif kind not in {"identity", "stackVertical"}:
        raise ValueError(f"Unsupported OCR transform: {kind}")

    with io.BytesIO() as buffer:
        image.save(buffer, format="PNG", optimize=False)
        content = buffer.getvalue()
    target.write_bytes(content)
    return filename, "image/png", content


def _source_image(case: dict) -> Image.Image:
    if case["transform"]["kind"] == "stackVertical":
        images = [
            Image.open(_fixture_path(path)).convert("RGB") for path in case["sources"]
        ]
        width = max(image.width for image in images)
        resized = [
            image.resize((width, round(image.height * width / image.width)))
            for image in images
        ]
        combined = Image.new(
            "RGB",
            (width, sum(image.height for image in resized)),
            "white",
        )
        y = 0
        for image in resized:
            combined.paste(image, (0, y))
            y += image.height
        return combined
    if case["transform"]["kind"] in {"abnormalFormat", "irrelevantImage"}:
        return Image.new("RGB", (1280, 720), "white")
    return Image.open(_fixture_path(case["source"])).convert("RGB")


def _font(size: int) -> ImageFont.ImageFont:
    try:
        result = subprocess.run(
            ["fc-match", "-f", "%{file}", "sans-serif:lang=zh"],
            capture_output=True,
            check=True,
            text=True,
            timeout=5,
        )
        path = Path(result.stdout.strip())
        if path.is_file():
            return ImageFont.truetype(str(path), size=size)
    except (OSError, subprocess.SubprocessError):
        pass
    return ImageFont.load_default(size=size)


def _settings(lancedb_uri: Path, *, document_parser_mode: str) -> Settings:
    return Settings(
        app_env="test",
        ai_service_token="test-service-token",
        neo4j_password="test-neo4j-password",
        otel_enabled=False,
        lancedb_uri=lancedb_uri,
        document_parser_mode=document_parser_mode,
        retrieval_provider_mode="mock",
        retrieval_embedding_dimensions=64,
        retrieval_max_candidates=40,
        retrieval_min_relevance_score=0.12,
        retrieval_context_max_chars=6000,
        graph_store_mode="memory",
    )


def _memory_headers(user_id: UUID, idempotency_key: str) -> dict[str, str]:
    return {
        **AUTH,
        "Idempotency-Key": idempotency_key,
        "X-Tenant-Id": str(TENANT_ID),
        "X-User-Id": str(user_id),
    }


def _fixture_path(relative: str) -> Path:
    path = ROOT / "qa/fixtures" / relative
    if not path.is_file():
        raise FileNotFoundError(relative)
    return path


def _load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _require_success(response: Any) -> None:
    if response.status_code != 200:
        raise RuntimeError(
            f"Fixture ingestion failed with status {response.status_code}"
        )


if __name__ == "__main__":
    raise SystemExit(main())
