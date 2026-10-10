CREATE TABLE user_profile (
    user_id UUID PRIMARY KEY,
    preferred_pace VARCHAR(32) NOT NULL,
    interests JSONB NOT NULL DEFAULT '[]'::jsonb,
    dietary_restrictions JSONB NOT NULL DEFAULT '[]'::jsonb,
    accommodation_preferences JSONB NOT NULL DEFAULT '[]'::jsonb,
    transport_preferences JSONB NOT NULL DEFAULT '[]'::jsonb,
    budget_habits JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE trip (
    trip_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    title VARCHAR(160) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT trip_status_check CHECK (status IN ('DRAFT', 'READY', 'ARCHIVED'))
);

CREATE INDEX idx_trip_user_created ON trip (user_id, created_at DESC);

CREATE TABLE travel_requirement (
    trip_id UUID PRIMARY KEY REFERENCES trip (trip_id) ON DELETE CASCADE,
    origin JSONB NOT NULL,
    destinations JSONB NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    travelers JSONB NOT NULL,
    total_budget NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    interests JSONB NOT NULL DEFAULT '[]'::jsonb,
    pace VARCHAR(32) NOT NULL,
    must_visit JSONB NOT NULL DEFAULT '[]'::jsonb,
    avoid JSONB NOT NULL DEFAULT '[]'::jsonb,
    free_text_constraints VARCHAR(5000) NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT requirement_date_check CHECK (end_date >= start_date),
    CONSTRAINT requirement_budget_check CHECK (total_budget >= 0),
    CONSTRAINT requirement_pace_check CHECK (pace IN ('RELAXED', 'BALANCED', 'INTENSIVE')),
    CONSTRAINT requirement_currency_check CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE uploaded_document (
    document_id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trip (trip_id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    content BYTEA NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT document_size_check CHECK (size_bytes > 0),
    CONSTRAINT document_status_check CHECK (
        status IN ('QUEUED', 'PARSING', 'PARSED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT document_trip_hash_unique UNIQUE (trip_id, sha256)
);

CREATE INDEX idx_document_trip_created
    ON uploaded_document (trip_id, created_at DESC);

CREATE TABLE parsed_booking (
    booking_id UUID PRIMARY KEY,
    booking_type VARCHAR(32) NOT NULL,
    source_document_id UUID NOT NULL
        REFERENCES uploaded_document (document_id) ON DELETE CASCADE,
    provider VARCHAR(200),
    confirmation_number VARCHAR(200),
    start_time TIMESTAMPTZ,
    end_time TIMESTAMPTZ,
    location JSONB,
    amount NUMERIC(19,4),
    currency VARCHAR(3),
    confidence NUMERIC(5,4) NOT NULL,
    missing_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    raw_evidence TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT booking_type_check CHECK (
        booking_type IN ('FLIGHT', 'HOTEL', 'TICKET', 'TRAIN', 'OTHER')
    ),
    CONSTRAINT booking_amount_check CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT booking_confidence_check CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT booking_currency_check CHECK (
        currency IS NULL OR currency ~ '^[A-Z]{3}$'
    )
);

CREATE INDEX idx_booking_document
    ON parsed_booking (source_document_id, created_at);

CREATE TABLE travel_plan (
    plan_id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trip (trip_id) ON DELETE CASCADE,
    previous_plan_id UUID REFERENCES travel_plan (plan_id),
    version INTEGER NOT NULL,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(5000) NOT NULL DEFAULT '',
    days JSONB NOT NULL DEFAULT '[]'::jsonb,
    budget_summary JSONB NOT NULL,
    conflicts JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    CONSTRAINT plan_version_check CHECK (version >= 1),
    CONSTRAINT plan_status_check CHECK (
        status IN ('GENERATING', 'READY', 'PARTIAL', 'FAILED', 'CONFIRMED', 'CANCELLED')
    ),
    CONSTRAINT plan_trip_version_unique UNIQUE (trip_id, version)
);

CREATE INDEX idx_plan_trip_version
    ON travel_plan (trip_id, version DESC);

CREATE TABLE task_record (
    task_id UUID PRIMARY KEY,
    task_type VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(100) NOT NULL,
    progress INTEGER NOT NULL,
    message VARCHAR(1000),
    trace_id VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    entity_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT task_type_check CHECK (
        task_type IN ('DOCUMENT_PARSE', 'PLAN_GENERATION', 'PLAN_REPLAN')
    ),
    CONSTRAINT task_status_check CHECK (
        status IN (
            'CREATED',
            'QUEUED',
            'RUNNING',
            'WAITING_CONFIRMATION',
            'SUCCEEDED',
            'PARTIAL_SUCCESS',
            'FAILED',
            'CANCELLED'
        )
    ),
    CONSTRAINT task_progress_check CHECK (progress BETWEEN 0 AND 100)
);

CREATE INDEX idx_task_resource_created
    ON task_record (resource_id, created_at DESC);

CREATE TABLE task_event (
    sequence BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    task_id UUID NOT NULL REFERENCES task_record (task_id) ON DELETE CASCADE,
    stage VARCHAR(100) NOT NULL,
    progress INTEGER NOT NULL,
    message VARCHAR(1000) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT task_event_progress_check CHECK (progress BETWEEN 0 AND 100)
);

CREATE INDEX idx_task_event_task_sequence
    ON task_event (task_id, sequence);

CREATE TABLE agent_result (
    agent_result_id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES task_record (task_id) ON DELETE CASCADE,
    agent_type VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result JSONB NOT NULL,
    evidence_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT agent_result_status_check CHECK (
        status IN (
            'CREATED',
            'QUEUED',
            'RUNNING',
            'WAITING_CONFIRMATION',
            'SUCCEEDED',
            'PARTIAL_SUCCESS',
            'FAILED',
            'CANCELLED'
        )
    )
);

CREATE TABLE feedback (
    feedback_id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trip (trip_id) ON DELETE CASCADE,
    plan_id UUID REFERENCES travel_plan (plan_id) ON DELETE SET NULL,
    rating INTEGER NOT NULL,
    comment VARCHAR(5000) NOT NULL,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT feedback_rating_check CHECK (rating BETWEEN 1 AND 5)
);

CREATE INDEX idx_feedback_trip_created
    ON feedback (trip_id, created_at DESC);

CREATE TABLE idempotency_record (
    operation VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_type VARCHAR(255) NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation, idempotency_key)
);
