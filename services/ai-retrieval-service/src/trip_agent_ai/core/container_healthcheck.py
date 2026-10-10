import os
import sys
import urllib.request


def main() -> int:
    port = os.getenv("AI_RETRIEVAL_SERVICE_PORT", "8000")
    token = os.getenv("AI_SERVICE_TOKEN", "")
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/internal/v1/health",
        headers={"Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=3) as response:  # noqa: S310
            return 0 if response.status == 200 else 1
    except Exception:
        return 1


if __name__ == "__main__":
    sys.exit(main())
