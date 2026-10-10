ALTER TABLE uploaded_document
    ADD COLUMN storage_key VARCHAR(500),
    ADD COLUMN page_count INTEGER NOT NULL DEFAULT 1,
    ALTER COLUMN content DROP NOT NULL;

ALTER TABLE uploaded_document DROP CONSTRAINT document_status_check;
ALTER TABLE uploaded_document
    ADD CONSTRAINT document_status_check CHECK (
        status IN ('QUEUED', 'PARSING', 'NEEDS_REVIEW', 'PARSED', 'FAILED', 'CANCELLED')
    ),
    ADD CONSTRAINT document_page_count_check CHECK (page_count BETWEEN 1 AND 100);

ALTER TABLE parsed_booking
    ADD COLUMN origin VARCHAR(300),
    ADD COLUMN destination VARCHAR(300),
    ADD COLUMN hotel_name VARCHAR(300),
    ADD COLUMN hotel_address VARCHAR(500),
    ADD COLUMN transport_number VARCHAR(100),
    ADD COLUMN traveler_count INTEGER,
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'AUTO_ACCEPTED',
    ADD COLUMN field_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN validation_issues JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN entity_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE parsed_booking SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE parsed_booking
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT booking_traveler_count_check CHECK (
        traveler_count IS NULL OR traveler_count BETWEEN 1 AND 100
    ),
    ADD CONSTRAINT booking_review_status_check CHECK (
        review_status IN ('AUTO_ACCEPTED', 'NEEDS_REVIEW', 'CORRECTED')
    );

CREATE INDEX idx_booking_confirmation
    ON parsed_booking (confirmation_number)
    WHERE confirmation_number IS NOT NULL;

CREATE TABLE booking_correction_audit (
    audit_id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES parsed_booking (booking_id) ON DELETE CASCADE,
    field_name VARCHAR(100) NOT NULL,
    original_value VARCHAR(2000) NOT NULL,
    corrected_value VARCHAR(2000) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    reason VARCHAR(500),
    corrected_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_booking_correction_booking_time
    ON booking_correction_audit (booking_id, corrected_at);

CREATE TABLE event_outbox (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    topic VARCHAR(200) NOT NULL,
    event_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    trace_id VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT event_outbox_attempts_check CHECK (attempts >= 0)
);

CREATE INDEX idx_event_outbox_pending
    ON event_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;
