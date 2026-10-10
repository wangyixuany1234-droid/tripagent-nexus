from trip_agent_ai.core.logging import REDACTED, redact_sensitive_data


def test_redacts_nested_sensitive_fields_and_inline_secrets() -> None:
    event = {
        "authorization": "Bearer token.value",
        "nested": {
            "Cookie": "session-secret",
            "message": "api_key=unit-test-value",
        },
        "items": [{"x-api-key": "another-secret"}],
        "ocrText": "Passenger Example, confirmation CONF-001",
        "query": "private preference query",
        "content": "private user memory",
    }

    redacted = redact_sensitive_data(None, "info", event)

    assert redacted["authorization"] == REDACTED
    assert redacted["nested"]["Cookie"] == REDACTED
    assert "unit-test-value" not in redacted["nested"]["message"]
    assert redacted["items"][0]["x-api-key"] == REDACTED
    assert redacted["ocrText"] == REDACTED
    assert redacted["query"] == REDACTED
    assert redacted["content"] == REDACTED


def test_preserves_non_sensitive_structured_fields() -> None:
    event = {"taskId": "task-123", "status": "RUNNING"}

    assert redact_sensitive_data(None, "info", event) == event
