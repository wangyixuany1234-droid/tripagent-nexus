"""Bounded application security probes against a fresh local synthetic database."""

from __future__ import annotations

import base64
import json
import struct
import time
import zlib
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import httpx

from qa.security.stage8_support import require_loopback, write_records

ROOT = Path(__file__).resolve().parents[2]
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
    "+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


def run(seed: dict, output: Path, config: dict) -> list[dict]:
    require_loopback(seed["baseUrl"])
    records = []
    client = httpx.Client(
        base_url=seed["baseUrl"],
        timeout=config["requestTimeoutSeconds"],
        follow_redirects=False,
        trust_env=False,
    )
    auth_tokens = seed["authTokens"]

    def request(method, path, token=auth_tokens["validA"], **kwargs):
        headers = {"Idempotency-Key": f"stage8-{uuid4()}", **kwargs.pop("headers", {})}
        if token is not None:
            headers.setdefault("Authorization", "Bearer " + token)
        return client.request(method, path, headers=headers, **kwargs)

    def check(identifier, category, severity, operation, predicate, expected):
        started = time.monotonic()
        record = {
            "id": identifier,
            "category": category,
            "severity": severity,
            "startedAt": datetime.now(UTC).isoformat(),
            "expected": expected,
            "status": "ERROR",
            "traceId": None,
            "taskId": seed["taskB"],
        }
        try:
            response = operation()
            record["httpStatus"] = response.status_code
            record["traceId"] = response.headers.get("x-trace-id")
            record["status"] = "PASSED" if predicate(response) else "FAILED"
            record["responseType"] = response.headers.get("content-type", "")
            if response.headers.get("content-type", "").startswith("application/json"):
                value = response.json()
                record["responseKeys"] = (
                    sorted(value) if isinstance(value, dict) else []
                )
                if isinstance(value, dict) and isinstance(value.get("items"), list):
                    record["itemCount"] = len(value["items"])
                if isinstance(value, dict) and isinstance(value.get("code"), str):
                    record["errorCode"] = value["code"]
            record["observedHeaders"] = {
                name: response.headers.get(name)
                for name in (
                    "retry-after",
                    "content-security-policy",
                    "cache-control",
                    "x-content-type-options",
                    "referrer-policy",
                    "x-frame-options",
                    "access-control-allow-origin",
                )
            }
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as error:
            record["failureType"] = type(error).__name__
        record["durationMs"] = round((time.monotonic() - started) * 1000)
        records.append(record)

    def skipped(identifier, category, reason):
        records.append(
            {
                "id": identifier,
                "category": category,
                "status": "NOT_APPLICABLE",
                "reason": reason,
                "severity": "Medium",
            }
        )

    def denied(response):
        return response.status_code in (401, 403, 404)

    trip = f"/api/v1/trips/{seed['tripB']}"

    auth_cases = {
        "anonymous": None,
        "expired": auth_tokens["expired"],
        "badSignature": auth_tokens["badSignature"],
        "wrongIssuer": auth_tokens["wrongIssuer"],
        "wrongAudience": auth_tokens["wrongAudience"],
        "missingIdentity": auth_tokens["missingIdentity"],
    }
    for name, credential in auth_cases.items():
        check(
            f"auth.{name}",
            "authentication",
            "High",
            lambda value=credential: request("GET", trip, token=value),
            denied,
            "Unauthenticated or invalid credentials must not read a private resource.",
        )
    skipped(
        "auth.logout",
        "authentication",
        "No login/logout or token revocation contract exists.",
    )
    skipped(
        "abuse.login",
        "abuse",
        "No login endpoint exists; cannot measure login throttling.",
    )

    cross_headers = {
        "X-User-Id": seed["userA"],
    }
    for name, path in {
        "trip": trip,
        "plan": f"/api/v1/plans/{seed['planB']}",
        "document": f"/api/v1/documents/{seed['documentB']}",
        "task": f"/api/v1/tasks/{seed['taskB']}",
        "feedback": f"/api/v1/feedback?tripId={seed['tripB']}",
        "workbench": f"/api/v1/workbench/tasks?userId={seed['userB']}",
    }.items():
        check(
            f"authorization.{name}",
            "authorization",
            "Critical",
            lambda p=path: request("GET", p, headers=cross_headers),
            denied,
            "Claiming another identity must not read the owner's synthetic resource.",
        )
    check(
        "authorization.omitIdentity",
        "authorization",
        "Critical",
        lambda: request("GET", trip, token=None),
        denied,
        "Omitting X-User-Id must not bypass ownership.",
    )
    check(
        "authorization.spoofOwner",
        "authorization",
        "Critical",
        lambda: request("GET", trip, headers={"X-User-Id": seed["userB"]}),
        denied,
        "An unverified header must not establish the owner's identity.",
    )
    trace_path = "/api/v1/developer/traces/" + seed["traceId"]
    check(
        "authorization.traceNormal",
        "authorization",
        "High",
        lambda: request("GET", trace_path, headers={"X-Actor-Role": "USER"}),
        denied,
        "Normal user cannot access developer trace.",
    )
    check(
        "authorization.traceSpoof",
        "authorization",
        "High",
        lambda: request("GET", trace_path, headers={"X-Actor-Role": "ADMIN"}),
        denied,
        "An attacker-supplied admin role must not reach the trace provider.",
    )
    check(
        "infra.traceProjectionRedaction",
        "infrastructure",
        "Medium",
        lambda: request(
            "GET",
            trace_path,
            token=auth_tokens["admin"],
            headers={"X-Actor-Role": "ADMIN"},
        ),
        lambda r: (
            r.status_code == 200
            and "SYNTHETIC_PRIVATE_MARKER" not in r.text
            and "SYNTHETIC_PROMPT_MARKER" not in r.text
        ),
        "Synthetic Jaeger fixture is redacted by the API projection; not a raw collector check.",
    )

    fixture = json.loads(
        (ROOT / "qa/fixtures/trips/beijing-three-day.json").read_text()
    )
    req = fixture["requirement"]
    check(
        "authorization.modifyRequirement",
        "authorization",
        "High",
        lambda: request("PUT", trip + "/requirements", headers=cross_headers, json=req),
        denied,
        "A cannot modify B's requirement.",
    )
    check(
        "authorization.modifyBooking",
        "authorization",
        "High",
        lambda: request(
            "PATCH",
            f"/api/v1/documents/{seed['documentB']}/bookings/{seed['bookingB']}",
            headers={**cross_headers, "X-Actor-Id": seed["userA"]},
            json={
                "corrections": [{"field": "hotelName", "value": "Synthetic changed"}],
                "reason": "Synthetic authorization probe",
            },
        ),
        denied,
        "A cannot correct B's booking.",
    )
    check(
        "authorization.operations",
        "authorization",
        "High",
        lambda: request("GET", "/api/v1/operations/dead-letters"),
        denied,
        "Operations endpoints require a protected credential.",
    )
    check(
        "authorization.enumeration",
        "authorization",
        "Medium",
        lambda: request("GET", "/api/v1/trips/" + str(uuid4()), headers=cross_headers),
        lambda r: r.status_code == 404,
        "Unknown UUID produces a generic not-found response.",
    )

    def body(title="Synthetic security trip"):
        return {"userId": seed["userA"], "title": title, "timeZone": "UTC"}

    for name, title in {
        "sql": "' OR '1'='1",
        "cypher": "' RETURN 1 AS synthetic //",
        "command": "stage8; echo SYNTHETIC_ONLY",
    }.items():
        check(
            f"input.{name}",
            "input",
            "High",
            lambda t=title: request("POST", "/api/v1/trips", json=body(t)),
            lambda r, t=title: (
                r.status_code in (400, 422)
                or (r.status_code == 201 and r.json()["title"] == t)
            ),
            "Payload is rejected or stored as literal text; this is not proof of all injection sinks.",
        )
    for name, raw in {
        "massAssignment": {**body(), "status": "CONFIRMED", "admin": True},
        "deserialization": {**body(), "@class": "java.lang.ProcessBuilder"},
        "longInput": body("x" * 161),
        "ssrfField": {**body(), "url": "http://127.0.0.1:65534/synthetic-only"},
    }.items():
        check(
            f"input.{name}",
            "input",
            "Medium",
            lambda b=raw: request("POST", "/api/v1/trips", json=b),
            lambda r: r.status_code in (400, 422),
            "Unknown or invalid fields must be rejected.",
        )
    duplicate = json.dumps(body())[:-1] + ',"title":"second-value"}'
    check(
        "input.duplicateKey",
        "input",
        "Medium",
        lambda: request(
            "POST",
            "/api/v1/trips",
            content=duplicate,
            headers={"Content-Type": "application/json"},
        ),
        lambda r: r.status_code in (400, 422),
        "Duplicate JSON keys must be rejected unambiguously.",
    )
    deep = (
        '{"extra":' * config["deepJsonLevels"] + "null" + "}" * config["deepJsonLevels"]
    )
    check(
        "input.deepJson",
        "input",
        "Medium",
        lambda: request(
            "POST",
            "/api/v1/trips",
            content=deep,
            headers={"Content-Type": "application/json"},
        ),
        lambda r: r.status_code in (400, 413, 422),
        "Bounded deep JSON must fail without 5xx.",
    )
    check(
        "input.invalidUtf8",
        "input",
        "Medium",
        lambda: request(
            "POST",
            "/api/v1/trips",
            content=b'{"title":"\xff"}',
            headers={"Content-Type": "application/json"},
        ),
        lambda r: r.status_code in (400, 422),
        "Malformed UTF-8 is rejected.",
    )
    check(
        "input.headerInjection",
        "input",
        "Medium",
        lambda: request(
            "GET", trip, headers={"X-Request-Id": "stage8%0d%0aX-Injected:yes"}
        ),
        lambda r: "x-injected" not in r.headers,
        "Encoded header probe cannot create a response header.",
    )

    uploads = [
        ("mimeMismatch", "file.jpg", "image/png", PNG),
        (
            "svg",
            "file.svg",
            "image/svg+xml",
            b'<svg xmlns="http://www.w3.org/2000/svg"><script>void(0)</script></svg>',
        ),
        (
            "malformedPdf",
            "file.pdf",
            "application/pdf",
            b"%PDF-1.7\nsynthetic malformed document",
        ),
        ("archive", "file.zip", "application/zip", b"PK\x03\x04synthetic-not-a-bomb"),
        (
            "oversize",
            "file.png",
            "image/png",
            PNG + b"\0" * config["maxUploadProbeBytes"],
        ),
    ]
    # Change only the metadata; never allocate/decompress a huge bitmap.
    high_pixel = bytearray(PNG)
    high_pixel[16:24] = struct.pack(">II", 100000, 100000)
    high_pixel[29:33] = struct.pack(">I", zlib.crc32(high_pixel[12:29]))
    uploads.append(("pixelLimit", "metadata.png", "image/png", bytes(high_pixel)))
    for name, filename, mime, data in uploads:
        check(
            f"upload.{name}",
            "upload",
            "Medium",
            lambda f=filename, m=mime, d=data: request(
                "POST",
                "/api/v1/documents/parse",
                token=auth_tokens["validB"],
                data={"tripId": seed["tripB"]},
                files={"file": (f, d, m)},
            ),
            lambda r: r.status_code in (400, 413, 415, 422),
            "Invalid upload is rejected before processing.",
        )
    for name, filename in {
        "doubleExtension": "synthetic.exe.png",
        "pathFilename": "../../synthetic.png",
    }.items():
        check(
            f"upload.{name}",
            "upload",
            "Low",
            lambda f=filename: request(
                "POST",
                "/api/v1/documents/parse",
                token=auth_tokens["validB"],
                data={"tripId": seed["tripB"]},
                files={"file": (f, PNG, "image/png")},
            ),
            lambda r: (
                r.status_code in (400, 415, 422)
                or (
                    r.status_code == 202
                    and r.json().get("resourceId") == seed["documentB"]
                )
            ),
            "Benign image must be rejected or deduplicated to server-owned storage, never use a client path.",
        )
    check(
        "upload.duplicate",
        "upload",
        "Medium",
        lambda: request(
            "POST",
            "/api/v1/documents/parse",
            token=auth_tokens["validB"],
            data={"tripId": seed["tripB"]},
            files={"file": ("same.png", PNG, "image/png")},
        ),
        lambda r: (
            r.status_code == 202 and r.json().get("resourceId") == seed["documentB"]
        ),
        "Duplicate content does not create another document.",
    )

    for name, header, expected in [
        ("nosniff", "x-content-type-options", "nosniff"),
        ("cache", "cache-control", "no-store"),
        ("referrer", "referrer-policy", None),
        ("frame", "x-frame-options", None),
        ("csp", "content-security-policy", None),
    ]:
        check(
            f"web.{name}",
            "web",
            "Medium",
            lambda: request("GET", trip, token=auth_tokens["validB"]),
            lambda r, h=header, e=expected: (
                bool(r.headers.get(h)) and (e is None or e in r.headers[h])
            ),
            f"Sensitive API response has {header}.",
        )
    check(
        "web.cors",
        "web",
        "Medium",
        lambda: request(
            "OPTIONS",
            trip,
            headers={
                "Origin": "https://untrusted.example.test",
                "Access-Control-Request-Method": "GET",
            },
        ),
        lambda r: (
            r.headers.get("access-control-allow-origin")
            not in ("*", "https://untrusted.example.test")
        ),
        "Untrusted origin is not allowed.",
    )
    check(
        "web.errors",
        "web",
        "Medium",
        lambda: request("GET", "/api/v1/trips/invalid-uuid"),
        lambda r: (
            r.status_code == 400
            and all(
                field in r.json()
                for field in ("code", "message", "traceId", "timestamp")
            )
            and not any(
                text in r.text for text in ("java.lang", "SQLException", "stackTrace")
            )
        ),
        "Errors have correlation and no raw stack.",
    )
    for endpoint in ("env", "configprops", "heapdump", "mappings"):
        check(
            "infra.actuator." + endpoint,
            "infrastructure",
            "High",
            lambda p=endpoint: request("GET", "/actuator/" + p, token=None),
            denied,
            "Management data is not anonymously exposed.",
        )
    skipped(
        "web.hsts",
        "web",
        "Local target uses plain HTTP; HSTS must be checked at authorized TLS ingress.",
    )
    skipped(
        "web.cookieCsrf",
        "web",
        "No session cookie authentication exists; cannot claim session/CSRF protection.",
    )

    statuses = []
    retry_after = []
    for _ in range(config["boundedAbuseRequests"]):
        response = request(
            "POST",
            "/api/v1/plans",
            token=auth_tokens["validB"],
            json={"tripId": seed["tripB"]},
        )
        statuses.append(response.status_code)
        if response.status_code == 429:
            retry_after.append(response.headers.get("retry-after"))
    records.append(
        {
            "id": "abuse.taskLimit",
            "category": "abuse",
            "severity": "Medium",
            "status": "PASSED" if retry_after and all(retry_after) else "FAILED",
            "expected": "Finite burst must enforce a documented task limit with Retry-After.",
            "observedStatuses": statuses,
            "retryAfter": retry_after,
            "scope": "Workers are disabled: only submission limits are tested, not active execution concurrency.",
        }
    )
    request(
        "POST",
        "/api/v1/tasks/" + seed["taskB"] + "/cancel",
        token=auth_tokens["validB"],
    )
    check(
        "abuse.writeAfterCancel",
        "abuse",
        "Medium",
        lambda: request(
            "PATCH",
            f"/api/v1/documents/{seed['documentB']}/bookings/{seed['bookingB']}",
            token=auth_tokens["validB"],
            headers={"X-Actor-Id": seed["userB"]},
            json={
                "corrections": [
                    {"field": "hotelName", "value": "Synthetic after cancel"}
                ]
            },
        ),
        lambda r: r.status_code in (403, 409, 422),
        "Cancelled document work cannot continue writing.",
    )
    client.close()
    write_records(output, "stage8-api", records)
    return records
