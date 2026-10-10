CREATE TABLE planning_checkpoint (
    task_id UUID PRIMARY KEY REFERENCES task_record(task_id) ON DELETE CASCADE,
    input_hash VARCHAR(64) NOT NULL,
    tenant_hash VARCHAR(64) NOT NULL,
    coordinator_input JSONB NOT NULL,
    knowledge JSONB NOT NULL,
    execution_token UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at)
);

CREATE INDEX idx_planning_checkpoint_expiry ON planning_checkpoint(expires_at);

CREATE TABLE agent_step_checkpoint (
    task_id UUID NOT NULL REFERENCES planning_checkpoint(task_id) ON DELETE CASCADE,
    agent_name VARCHAR(100) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    tenant_hash VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(200) NOT NULL,
    execution_token UUID NOT NULL,
    agent_result_id UUID NOT NULL REFERENCES agent_result(agent_result_id),
    result_envelope JSONB,
    PRIMARY KEY (task_id, agent_name)
);
