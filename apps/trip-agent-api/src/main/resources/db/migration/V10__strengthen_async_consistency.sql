ALTER TABLE task_event
    ADD COLUMN trace_id VARCHAR(32),
    ADD COLUMN source_event_id UUID,
    ADD COLUMN source_event_type VARCHAR(200);

UPDATE task_event event
SET trace_id = task.trace_id,
    source_event_id = event.event_id,
    source_event_type = 'task.state.changed'
FROM task_record task
WHERE task.task_id = event.task_id;

ALTER TABLE task_event
    ALTER COLUMN trace_id SET NOT NULL,
    ALTER COLUMN source_event_id SET NOT NULL,
    ALTER COLUMN source_event_type SET NOT NULL;

CREATE INDEX idx_task_event_source
    ON task_event (source_event_id, sequence);

ALTER TABLE agent_result
    ADD COLUMN result_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN source_event_id UUID,
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN entity_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN execution_sequence BIGINT GENERATED ALWAYS AS IDENTITY;

UPDATE agent_result
SET completed_at = created_at
WHERE status <> 'RUNNING';

CREATE UNIQUE INDEX idx_agent_result_source_event_unique
    ON agent_result (source_event_id)
    WHERE source_event_id IS NOT NULL;

CREATE INDEX idx_agent_result_task_agent_sequence
    ON agent_result (task_id, agent_type, execution_sequence DESC);

DELETE FROM event_outbox newer
USING event_outbox older
WHERE newer.topic = older.topic
  AND newer.idempotency_key = older.idempotency_key
  AND (newer.created_at, newer.event_id) > (older.created_at, older.event_id);

CREATE UNIQUE INDEX idx_event_outbox_topic_idempotency_unique
    ON event_outbox (topic, idempotency_key);
