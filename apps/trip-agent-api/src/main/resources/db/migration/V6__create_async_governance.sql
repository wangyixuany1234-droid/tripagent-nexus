ALTER TABLE event_outbox
    ADD COLUMN event_type VARCHAR(200),
    ADD COLUMN event_version VARCHAR(20),
    ADD COLUMN correlation_id UUID,
    ADD COLUMN causation_id UUID,
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN max_attempts INTEGER NOT NULL DEFAULT 5,
    ADD COLUMN last_error_code VARCHAR(100),
    ADD COLUMN last_error_message VARCHAR(1000),
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE event_outbox
SET event_type = COALESCE(payload ->> 'eventType', topic),
    event_version = COALESCE(payload ->> 'schemaVersion', '1.0.0'),
    correlation_id = COALESCE(
        NULLIF(payload ->> 'correlationId', '')::UUID,
        NULLIF(payload ->> 'taskId', '')::UUID,
        event_id
    ),
    causation_id = COALESCE(
        NULLIF(payload ->> 'causationId', '')::UUID,
        NULLIF(payload ->> 'causationEventId', '')::UUID,
        event_id
    ),
    idempotency_key = COALESCE(payload ->> 'idempotencyKey', 'legacy-' || event_id),
    status = CASE WHEN published_at IS NULL THEN 'PENDING' ELSE 'PUBLISHED' END,
    updated_at = COALESCE(published_at, created_at);

ALTER TABLE event_outbox
    ALTER COLUMN event_type SET NOT NULL,
    ALTER COLUMN event_version SET NOT NULL,
    ALTER COLUMN correlation_id SET NOT NULL,
    ALTER COLUMN causation_id SET NOT NULL,
    ALTER COLUMN idempotency_key SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT event_outbox_status_check CHECK (
        status IN ('PENDING', 'PUBLISHED', 'DEAD_LETTERED')
    ),
    ADD CONSTRAINT event_outbox_max_attempts_check CHECK (max_attempts BETWEEN 1 AND 100);

DROP INDEX idx_event_outbox_pending;
CREATE INDEX idx_event_outbox_pending
    ON event_outbox (next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE TABLE event_inbox (
    inbox_id UUID PRIMARY KEY,
    consumer_name VARCHAR(160) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    event_version VARCHAR(20) NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    trace_id VARCHAR(32) NOT NULL,
    task_id UUID,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 1,
    last_error_code VARCHAR(100),
    received_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT event_inbox_consumer_event_unique UNIQUE (consumer_name, event_id),
    CONSTRAINT event_inbox_status_check CHECK (
        status IN ('PROCESSING', 'RETRYABLE', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT event_inbox_attempts_check CHECK (attempts BETWEEN 1 AND 1000)
);

CREATE INDEX idx_event_inbox_status_updated
    ON event_inbox (status, updated_at);

CREATE TABLE dead_letter_event (
    dead_letter_id UUID PRIMARY KEY,
    original_event_id UUID NOT NULL UNIQUE,
    original_topic VARCHAR(200) NOT NULL,
    original_event_key VARCHAR(200) NOT NULL,
    original_event_type VARCHAR(200) NOT NULL,
    event_version VARCHAR(20) NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    trace_id VARCHAR(32) NOT NULL,
    trip_id UUID,
    task_id UUID,
    original_envelope JSONB NOT NULL,
    failure_code VARCHAR(100) NOT NULL,
    failure_reason VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    replay_count INTEGER NOT NULL DEFAULT 0,
    replay_event_id UUID,
    replay_task_id UUID REFERENCES task_record (task_id),
    replay_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    replayed_at TIMESTAMPTZ,
    CONSTRAINT dead_letter_status_check CHECK (status IN ('PENDING', 'REPLAYED')),
    CONSTRAINT dead_letter_replay_count_check CHECK (replay_count >= 0)
);

CREATE INDEX idx_dead_letter_status_created
    ON dead_letter_event (status, created_at DESC);

ALTER TABLE task_record
    ADD COLUMN failure_code VARCHAR(100),
    ADD COLUMN failure_reason VARCHAR(1000),
    ADD COLUMN retryable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN recovery_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN degraded_components JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN heartbeat_at TIMESTAMPTZ,
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN retry_of_task_id UUID REFERENCES task_record (task_id);

UPDATE task_record
SET heartbeat_at = updated_at;

ALTER TABLE task_record
    ALTER COLUMN heartbeat_at SET NOT NULL,
    ADD CONSTRAINT task_recovery_attempts_check CHECK (recovery_attempts >= 0);

CREATE INDEX idx_task_recovery
    ON task_record (status, heartbeat_at)
    WHERE status IN ('QUEUED', 'RUNNING');
