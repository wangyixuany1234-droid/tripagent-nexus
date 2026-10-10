CREATE TABLE platform_health_marker (
    id SMALLINT PRIMARY KEY,
    service_name VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_health_marker_singleton CHECK (id = 1)
);

INSERT INTO platform_health_marker (id, service_name)
VALUES (1, 'trip-agent-api');
