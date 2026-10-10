CREATE TABLE user_preference (
    preference_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    category VARCHAR(100) NOT NULL,
    value VARCHAR(500) NOT NULL,
    source VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    entity_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT user_preference_source_check CHECK (source IN ('USER', 'INFERRED')),
    CONSTRAINT user_preference_status_check CHECK (status IN ('ACTIVE', 'REJECTED'))
);

CREATE INDEX idx_user_preference_user_updated
    ON user_preference (user_id, updated_at DESC);

ALTER TABLE feedback
    ADD COLUMN target_type VARCHAR(32),
    ADD COLUMN target_id VARCHAR(200),
    ADD COLUMN sentiment VARCHAR(32),
    ADD COLUMN memory_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT feedback_target_type_check CHECK (
        target_type IS NULL OR target_type IN ('PLAN', 'PLACE', 'BUDGET', 'EVIDENCE')
    ),
    ADD CONSTRAINT feedback_sentiment_check CHECK (
        sentiment IS NULL OR sentiment IN ('LIKE', 'DISLIKE')
    );
