import gc
import json
import os
import random
import shutil
import tempfile
from pathlib import Path

import pytest

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
QA_FIXTURE_ROOT = REPOSITORY_ROOT / "qa/fixtures"
TEST_RUNTIME_ROOT = REPOSITORY_ROOT / ".runtime/lancedb-tests"
RANDOM_SEED = json.loads(
    (QA_FIXTURE_ROOT / "manifest.json").read_text(encoding="utf-8")
)["randomSeed"]

os.environ["APP_ENV"] = "test"
os.environ["OTEL_ENABLED"] = "false"
os.environ["AI_SERVICE_TOKEN"] = "test-service-token"
os.environ["NEO4J_PASSWORD"] = "test-neo4j-password"
os.environ["DOCUMENT_PARSER_MODE"] = "mock"
os.environ["RETRIEVAL_PROVIDER_MODE"] = "mock"
os.environ["GRAPH_STORE_MODE"] = "memory"
os.environ["DEEPSEEK_BASE_URL"] = "http://127.0.0.1:65535"
os.environ["ALLOWED_EXTERNAL_HOSTS"] = "localhost,127.0.0.1"


@pytest.fixture(autouse=True)
def deterministic_test_runtime(monkeypatch):
    random.seed(RANDOM_SEED)
    TEST_RUNTIME_ROOT.mkdir(parents=True, exist_ok=True)
    temporary_root = Path(tempfile.mkdtemp(prefix="case-", dir=TEST_RUNTIME_ROOT))
    lancedb_uri = temporary_root / "lancedb"
    monkeypatch.setenv("LANCEDB_URI", str(lancedb_uri))
    yield
    gc.collect()
    shutil.rmtree(temporary_root, ignore_errors=True)
    assert not temporary_root.exists()


@pytest.fixture
def isolated_lancedb_dir() -> Path:
    value = Path(os.environ["LANCEDB_URI"])
    value.mkdir(parents=True, exist_ok=True)
    return value


@pytest.fixture(scope="session")
def qa_fixture_root() -> Path:
    return QA_FIXTURE_ROOT
