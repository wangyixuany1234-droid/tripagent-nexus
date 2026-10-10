import pytest

from trip_agent_ai.core.config import Settings
from trip_agent_ai.core.errors import AppError
from trip_agent_ai.core.upload_policy import UploadPolicy


@pytest.fixture
def policy(tmp_path) -> UploadPolicy:
    return UploadPolicy(
        Settings(
            otel_enabled=False,
            lancedb_uri=tmp_path,
            upload_max_size_bytes=1024,
            upload_allowed_mime_types="image/jpeg,application/pdf",
            upload_allowed_extensions=".jpg,.jpeg,.pdf",
        )
    )


def test_accepts_allowed_upload(policy: UploadPolicy) -> None:
    policy.validate("booking.JPG", "IMAGE/JPEG", 100)


@pytest.mark.parametrize(
    ("filename", "media_type", "size_bytes", "expected_code"),
    [
        ("booking.jpg", "text/html", 100, "UPLOAD_MIME_TYPE_DENIED"),
        ("booking.exe", "application/pdf", 100, "UPLOAD_EXTENSION_DENIED"),
        ("booking.pdf", "application/pdf", 1025, "UPLOAD_SIZE_INVALID"),
    ],
)
def test_rejects_invalid_upload(
    policy: UploadPolicy,
    filename: str,
    media_type: str,
    size_bytes: int,
    expected_code: str,
) -> None:
    with pytest.raises(AppError) as raised:
        policy.validate(filename, media_type, size_bytes)

    assert raised.value.code == expected_code
