ALTER TABLE task_record
    DROP CONSTRAINT task_type_check,
    ADD CONSTRAINT task_type_check CHECK (
        task_type IN (
            'DOCUMENT_PARSE',
            'PLAN_GENERATION',
            'PLAN_REPLAN',
            'EVALUATION_REPLAY'
        )
    );

CREATE TABLE guarded_action (
    action_id UUID PRIMARY KEY,
    action_type VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    risk_description VARCHAR(1000) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    proposed_by VARCHAR(128) NOT NULL,
    confirmed_by VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    entity_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT guarded_action_type_check CHECK (
        action_type IN ('ORDER_CREATE', 'PAYMENT', 'CANCELLATION', 'REAL_COST')
    ),
    CONSTRAINT guarded_action_status_check CHECK (
        status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'REJECTED', 'EXPIRED', 'EXECUTED')
    ),
    CONSTRAINT guarded_action_amount_check CHECK (amount >= 0),
    CONSTRAINT guarded_action_currency_check CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_guarded_action_resource_created
    ON guarded_action (resource_id, created_at DESC);

CREATE TABLE guarded_action_audit (
    sequence BIGSERIAL PRIMARY KEY,
    audit_id UUID NOT NULL UNIQUE,
    action_id UUID NOT NULL REFERENCES guarded_action (action_id) ON DELETE CASCADE,
    actor_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    reason VARCHAR(500),
    trace_id VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT guarded_action_audit_event_check CHECK (
        event_type IN ('PROPOSED', 'CONFIRMED', 'REJECTED', 'EXPIRED', 'EXECUTED')
    )
);

CREATE INDEX idx_guarded_action_audit_action_sequence
    ON guarded_action_audit (action_id, sequence);

CREATE TABLE evaluation_run (
    run_id UUID PRIMARY KEY,
    task_id UUID UNIQUE REFERENCES task_record (task_id),
    dataset_version VARCHAR(64) NOT NULL,
    selected_case_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    sample_size INTEGER NOT NULL DEFAULT 0,
    metrics JSONB,
    environment JSONB NOT NULL,
    trace_id VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    entity_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT evaluation_run_status_check CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT evaluation_run_sample_size_check CHECK (sample_size >= 0)
);

CREATE INDEX idx_evaluation_run_created
    ON evaluation_run (created_at DESC);

CREATE TABLE evaluation_case_result (
    result_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES evaluation_run (run_id) ON DELETE CASCADE,
    case_id VARCHAR(100) NOT NULL,
    category VARCHAR(64) NOT NULL,
    passed BOOLEAN NOT NULL,
    latency_ms BIGINT NOT NULL,
    token_usage BIGINT NOT NULL,
    failure_code VARCHAR(100),
    observed_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT evaluation_case_run_case_unique UNIQUE (run_id, case_id),
    CONSTRAINT evaluation_case_latency_check CHECK (latency_ms >= 0),
    CONSTRAINT evaluation_case_tokens_check CHECK (token_usage >= 0)
);

CREATE INDEX idx_evaluation_case_run
    ON evaluation_case_result (run_id, case_id);
