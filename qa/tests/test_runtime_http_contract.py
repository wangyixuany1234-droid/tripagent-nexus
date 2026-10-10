import os

import httpx


BASE_URL = os.getenv("CONTRACT_BASE_URL", "http://127.0.0.1:8080").rstrip("/")


def assert_api_error(response: httpx.Response) -> None:
    assert response.headers["content-type"].startswith("application/json")
    body = response.json()
    assert {"code", "message", "traceId", "timestamp"} <= body.keys()


def test_unsupported_method_returns_405_with_allow_header() -> None:
    response = httpx.request("QUERY", f"{BASE_URL}/api/v1/health", timeout=10)

    assert response.status_code == 405
    assert "GET" in response.headers["allow"]
    assert_api_error(response)


def test_malformed_multipart_request_never_returns_500() -> None:
    response = httpx.post(
        f"{BASE_URL}/api/v1/documents/parse",
        headers={
            "Idempotency-Key": "contract-malformed-multipart",
            "Content-Type": "multipart/form-data; boundary=invalid",
        },
        content=b"--invalid\r\nmalformed\r\n--invalid--\r\n",
        timeout=10,
    )

    assert response.status_code in {400, 415, 422}
    assert_api_error(response)
