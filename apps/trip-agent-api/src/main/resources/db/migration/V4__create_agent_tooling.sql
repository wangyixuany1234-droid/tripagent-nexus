CREATE TABLE itinerary_artifact (
    itinerary_artifact_id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trip (trip_id) ON DELETE CASCADE,
    plan_version INTEGER NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    itinerary JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT itinerary_artifact_plan_version_check CHECK (plan_version >= 1),
    CONSTRAINT itinerary_artifact_idempotency_unique UNIQUE (idempotency_key)
);

CREATE INDEX idx_itinerary_artifact_trip_version
    ON itinerary_artifact (trip_id, plan_version DESC);
