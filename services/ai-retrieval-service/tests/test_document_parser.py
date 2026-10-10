import hashlib
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from trip_agent_ai.core.config import Settings
from trip_agent_ai.main import create_app


def _settings(lancedb_uri) -> Settings:
    return Settings(
        document_parser_mode="mock",
        lancedb_uri=lancedb_uri,
        ai_service_token="test-service-token",
        neo4j_password="test-neo4j-password",
        otel_enabled=False,
    )


def _parse(
    fixture_root,
    lancedb_uri,
    filename: str,
    content: bytes | None = None,
    media_type: str = "image/png",
):
    body = (
        content
        if content is not None
        else (fixture_root / "documents" / filename).read_bytes()
    )
    app = create_app(_settings(lancedb_uri))
    with TestClient(app) as client:
        return client.post(
            "/internal/v1/documents/parse",
            headers={"Authorization": "Bearer test-service-token"},
            data={
                "documentId": str(uuid4()),
                "taskId": str(uuid4()),
                "mediaType": media_type,
                "sha256": hashlib.sha256(body).hexdigest(),
                "locale": "zh-CN",
            },
            files={"file": (filename, body, media_type)},
        )


def test_mock_pipeline_runs_ocr_before_semantic_extraction(
    qa_fixture_root,
    isolated_lancedb_dir,
) -> None:
    response = _parse(
        qa_fixture_root,
        isolated_lancedb_dir,
        "hotel-confirmation.png",
    )

    assert response.status_code == 200
    body = response.json()
    assert body["parserMode"] == "MOCK"
    assert body["ocrEngine"] == "mock-ocr"
    assert body["bookings"][0]["type"] == "HOTEL"
    assert body["bookings"][0]["amount"] == "4500.0000"


def test_missing_date_fixture_returns_low_confidence_fields(
    qa_fixture_root,
    isolated_lancedb_dir,
) -> None:
    response = _parse(
        qa_fixture_root,
        isolated_lancedb_dir,
        "low-confidence.png",
    )

    assert response.status_code == 200
    booking = response.json()["bookings"][0]
    assert booking["startTime"] is None
    assert booking["missingFields"] == ["startTime", "endTime"]


@pytest.mark.parametrize(
    ("filename", "media_type", "booking_type"),
    [
        ("hotel-confirmation.png", "image/png", "HOTEL"),
        ("flight-confirmation.jpg", "image/jpeg", "FLIGHT"),
        ("attraction-ticket.webp", "image/webp", "TICKET"),
        ("low-confidence.png", "image/png", "HOTEL"),
        ("duplicate-orders.pdf", "application/pdf", "FLIGHT"),
    ],
)
def test_all_redacted_fixtures_pass_the_internal_pipeline(
    qa_fixture_root,
    isolated_lancedb_dir,
    filename: str,
    media_type: str,
    booking_type: str,
) -> None:
    response = _parse(
        qa_fixture_root,
        isolated_lancedb_dir,
        filename,
        media_type=media_type,
    )

    assert response.status_code == 200
    assert response.json()["bookings"][0]["type"] == booking_type


def test_duplicate_order_fixture_preserves_both_matching_confirmations(
    qa_fixture_root,
    isolated_lancedb_dir,
) -> None:
    response = _parse(
        qa_fixture_root,
        isolated_lancedb_dir,
        "duplicate-orders.pdf",
        media_type="application/pdf",
    )

    assert response.status_code == 200
    bookings = response.json()["bookings"]
    assert len(bookings) == 2
    assert {booking["confirmationNumber"] for booking in bookings} == {"DUP-2026"}


def test_rejects_mime_spoof_and_hash_mismatch(
    qa_fixture_root,
    isolated_lancedb_dir,
) -> None:
    png = (qa_fixture_root / "documents/hotel-confirmation.png").read_bytes()
    spoofed = _parse(
        qa_fixture_root,
        isolated_lancedb_dir,
        "ticket.jpg",
        content=png,
        media_type="image/jpeg",
    )
    assert spoofed.status_code == 415
    assert spoofed.json()["code"] == "UPLOAD_MIME_MISMATCH"

    app = create_app(_settings(isolated_lancedb_dir))
    with TestClient(app) as client:
        mismatch = client.post(
            "/internal/v1/documents/parse",
            headers={"Authorization": "Bearer test-service-token"},
            data={
                "documentId": str(uuid4()),
                "taskId": str(uuid4()),
                "mediaType": "image/png",
                "sha256": "0" * 64,
            },
            files={"file": ("ticket.png", png, "image/png")},
        )
    assert mismatch.status_code == 422
    assert mismatch.json()["code"] == "DOCUMENT_HASH_MISMATCH"


def test_rejects_the_anomalous_file_fixture(
    qa_fixture_root,
    isolated_lancedb_dir,
) -> None:
    content = (qa_fixture_root / "documents/anomalous-invalid-image.jpg").read_bytes()
    response = _parse(
        qa_fixture_root,
        isolated_lancedb_dir,
        "anomalous-invalid-image.jpg",
        content=content,
        media_type="image/jpeg",
    )

    assert response.status_code == 422
    assert response.json()["code"] == "UPLOAD_FILE_MALFORMED"


def test_requires_service_authentication(
    qa_fixture_root,
    isolated_lancedb_dir,
) -> None:
    png = (qa_fixture_root / "documents/hotel-confirmation.png").read_bytes()
    app = create_app(_settings(isolated_lancedb_dir))
    with TestClient(app) as client:
        response = client.post(
            "/internal/v1/documents/parse",
            data={
                "documentId": str(uuid4()),
                "taskId": str(uuid4()),
                "mediaType": "image/png",
                "sha256": hashlib.sha256(png).hexdigest(),
            },
            files={"file": ("ticket.png", png, "image/png")},
        )

    assert response.status_code == 401
    assert response.json()["code"] == "SERVICE_AUTHENTICATION_FAILED"
