import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from qa.scripts.stage10_metrics import (
    score_ocr,
    score_rag,
    summarize_repetitions,
)

SPEC = importlib.util.spec_from_file_location(
    "stage10_runner", ROOT / "qa/scripts/run_stage10.py"
)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
DATASET = json.loads(runner.DATASET_PATH.read_text(encoding="utf-8"))


def test_dataset_is_fixed_synthetic_and_covers_every_requested_scenario():
    assert DATASET["classification"] == "synthetic"
    assert DATASET["syntheticOnly"] is True
    assert DATASET["repetitions"] >= 3
    assert DATASET["random"] == {"seed": 20260908, "temperature": 0}
    assert {case["scenario"] for case in DATASET["ocrCases"]} >= {
        "清晰截图",
        "模糊截图",
        "倾斜图片",
        "深色模式",
        "多语言",
        "遮挡",
        "多订单同图",
        "缺少字段",
        "格式异常",
        "不相关图片",
    }
    assert {case["scenario"] for case in DATASET["ragCases"]} == {
        "精确景点查询",
        "附近和顺路查询",
        "区域级三日规划",
        "用户历史偏好",
        "过期知识",
        "冲突来源",
        "无相关知识",
        "同名地点",
        "多城市",
        "中文和英文混合",
    }


def test_dataset_is_registered_in_the_shared_fixture_manifest():
    manifest = json.loads(
        (ROOT / "qa/fixtures/manifest.json").read_text(encoding="utf-8")
    )
    registered = {
        item["path"]
        for item in manifest["fixtures"]
        if item["id"] == "evaluation-stage10-v1"
    }

    assert registered == {"evaluation/stage10-v1.json"}
    assert runner.DATASET_PATH == ROOT / "qa/fixtures/evaluation/stage10-v1.json"


def test_ocr_perfect_observations_score_one_without_semantic_judge():
    observations = []
    for case in DATASET["ocrCases"]:
        bookings = []
        for booking in case["expectedBookings"]:
            bookings.append(
                {
                    **booking,
                    "missingFields": case["expectedLowConfidenceFields"],
                    "fieldEvidence": [],
                    "confidence": "0.9000",
                }
            )
        observations.append(
            {
                "id": case["id"],
                "statusCode": 200,
                "response": {"bookings": bookings},
                "artifactSha256": "0" * 64,
            }
        )

    result = score_ocr(DATASET, observations)

    for name in (
        "fieldPrecision",
        "fieldRecall",
        "fieldF1",
        "dateAccuracy",
        "amountAccuracy",
        "locationAccuracy",
        "orderTypeAccuracy",
        "lowConfidenceFieldRecall",
        "duplicateOrderRecognitionRate",
        "irrelevantImageRejectionRate",
    ):
        assert result[name]["value"] == 1


def test_ocr_wrong_amount_counts_as_false_positive_and_false_negative():
    case = DATASET["ocrCases"][0]
    wrong = {**case["expectedBookings"][0], "amount": "1.0000"}
    result = score_ocr(
        {**DATASET, "ocrCases": [case]},
        [
            {
                "id": case["id"],
                "statusCode": 200,
                "response": {"bookings": [{**wrong, "missingFields": []}]},
            }
        ],
    )

    assert result["amountAccuracy"]["value"] == 0
    assert result["fieldPrecision"]["value"] < 1
    assert result["fieldRecall"]["value"] < 1


def test_rag_metrics_use_rank_positions_and_explicit_relevance():
    dataset = {
        "retrievalParameters": {"metricK": 2},
        "ragCases": [
            {
                "id": "rank",
                "scenario": "rank",
                "relevance": {"best": 3, "second": 1},
                "requiredEvidence": ["best"],
                "forbiddenEvidence": ["leak"],
            },
            {
                "id": "empty",
                "scenario": "empty",
                "relevance": {},
                "requiredEvidence": [],
                "forbiddenEvidence": [],
                "expectNoEvidence": True,
            },
        ],
    }
    observations = [
        {
            "id": "rank",
            "statusCode": 200,
            "beforeRerank": ["distractor", "best"],
            "response": {
                "evidence": [{"sourceId": "best"}, {"sourceId": "second"}],
                "evidenceInsufficient": False,
            },
        },
        {
            "id": "empty",
            "statusCode": 200,
            "beforeRerank": [],
            "response": {"evidence": [], "evidenceInsufficient": True},
        },
    ]

    result = score_rag(dataset, observations)

    assert result["recallAtK"]["value"] == 1
    assert result["mrr"]["value"] == 1
    assert result["nDcgAtK"]["value"] == 1
    assert result["recallAtK"]["numerator"] / result["recallAtK"]["denominator"] == 1
    assert result["evidenceCoverageRate"]["value"] == 1
    assert result["noEvidenceRefusalRate"]["value"] == 1
    assert result["userMemoryIsolationRate"]["value"] == 1
    assert result["rerankDifference"]["delta"]["mrr"] == 0.5


def test_repetition_summary_exposes_variation_instead_of_hiding_it():
    reports = [
        {"ocr": {"fieldF1": {"status": "MEASURED", "value": value}}}
        for value in (0.8, 0.9, 1.0)
    ]

    result = summarize_repetitions(reports)

    assert result["repetitions"] == 3
    assert result["identicalMeasuredMetrics"] is False
    assert result["metrics"]["ocr"]["fieldF1"] == {
        "status": "MEASURED",
        "values": [0.8, 0.9, 1.0],
        "mean": 0.9,
        "minimum": 0.8,
        "maximum": 1.0,
        "populationStandardDeviation": 0.0816,
    }


def test_junit_parser_preserves_failures_and_skips(tmp_path):
    report = tmp_path / "TEST-sample.xml"
    report.write_text(
        f'<testsuite><properties><property name="user.home" '
        f'value="{Path.home()}"/></properties>'
        '<testcase classname="A" name="pass" time="0.1"/>'
        '<testcase classname="A" name="fail" time="0.2"><failure/></testcase>'
        '<testcase classname="A" name="skip"><skipped/></testcase>'
        f"<system-out>{Path.home().name}</system-out></testsuite>",
        encoding="utf-8",
    )

    records = runner.junit_records([report], tmp_path)
    sanitized = report.read_text(encoding="utf-8")

    assert [item["status"] for item in records] == [
        "PASSED",
        "FAILED",
        "SKIPPED",
    ]
    assert "<properties>" not in sanitized
    assert "<system-out>" not in sanitized
    assert str(Path.home()) not in sanitized
    assert Path.home().name not in sanitized


def test_acceptance_gaps_cannot_be_removed_by_mock_success():
    reports = [
        {
            "repetition": index,
            "status": "MEASURED",
            "metrics": {
                "ocr": {
                    "cases": [
                        {
                            "id": case["id"],
                            "status": "MEASURED",
                        }
                        for case in DATASET["ocrCases"]
                    ]
                },
                "rag": {
                    "cases": [
                        {
                            "id": case["id"],
                            "status": "MEASURED",
                        }
                        for case in DATASET["ragCases"]
                    ]
                },
            },
        }
        for index in range(1, 4)
    ]
    batches = [{"id": "all", "status": "PASSED"}]

    gaps = runner.build_gaps(
        DATASET,
        reports,
        batches,
        runner.source_fingerprint(),
    )

    assert {item["id"] for item in gaps} >= {
        "real-model-not-authorized",
        "mock-ocr-pixel-insensitive",
        "manual-semantic-review-pending",
        "acceptance-thresholds-undefined",
    }


def test_source_fingerprint_and_dataset_hash_are_repeatable():
    source_paths = {
        path.relative_to(ROOT).as_posix() for path in runner.source_paths()
    }

    assert "services/ai-retrieval-service/src/trip_agent_ai/main.py" in source_paths
    assert (
        "apps/trip-agent-api/src/main/java/com/tripagent/nexus/"
        "agent/coordinator/CoordinatorAgent.java"
    ) in source_paths
    assert "apps/trip-agent-api/pom.xml" in source_paths
    assert "services/ai-retrieval-service/uv.lock" in source_paths
    assert runner.source_fingerprint() == runner.source_fingerprint()
    assert runner._sha256(runner.DATASET_PATH) == runner._sha256(runner.DATASET_PATH)
