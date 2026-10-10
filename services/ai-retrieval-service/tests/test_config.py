from trip_agent_ai.core.config import Settings


def test_sensitive_settings_are_not_exposed_in_repr(tmp_path) -> None:
    settings = Settings(
        otel_enabled=False,
        lancedb_uri=tmp_path,
        ai_service_token="service-secret-value",
        deepseek_api_key="deepseek-secret-value",
        neo4j_password="neo4j-secret-value",
    )

    rendered = repr(settings)

    assert "service-secret-value" not in rendered
    assert "deepseek-secret-value" not in rendered
    assert "neo4j-secret-value" not in rendered


def test_comma_separated_upload_policy_settings(monkeypatch, tmp_path) -> None:
    monkeypatch.setenv("UPLOAD_ALLOWED_MIME_TYPES", "image/jpeg,application/pdf")
    monkeypatch.setenv("UPLOAD_ALLOWED_EXTENSIONS", ".jpg,.pdf")

    settings = Settings(otel_enabled=False, lancedb_uri=tmp_path)

    assert settings.upload_allowed_mime_types == ("image/jpeg", "application/pdf")
    assert settings.upload_allowed_extensions == (".jpg", ".pdf")


def test_retrieval_mock_mode_and_table_names_are_explicit(
    monkeypatch,
    tmp_path,
) -> None:
    monkeypatch.setenv("RETRIEVAL_PROVIDER_MODE", "mock")
    monkeypatch.setenv("GRAPH_STORE_MODE", "memory")
    monkeypatch.setenv("LANCEDB_PUBLIC_TABLE", "test_public")
    monkeypatch.setenv("LANCEDB_USER_MEMORY_TABLE", "test_memory")

    settings = Settings(otel_enabled=False, lancedb_uri=tmp_path)

    assert settings.retrieval_provider_mode == "mock"
    assert settings.graph_store_mode == "memory"
    assert settings.lancedb_public_table == "test_public"
    assert settings.lancedb_user_memory_table == "test_memory"
