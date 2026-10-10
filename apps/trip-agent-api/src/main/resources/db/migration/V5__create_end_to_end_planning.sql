ALTER TABLE travel_plan
    ADD COLUMN plan_family_id UUID,
    ADD COLUMN alternative_key VARCHAR(40),
    ADD COLUMN strategy VARCHAR(32),
    ADD COLUMN trigger_reason VARCHAR(1000) NOT NULL DEFAULT 'INITIAL_GENERATION',
    ADD COLUMN replan_input JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN change_summary VARCHAR(2000) NOT NULL DEFAULT '',
    ADD COLUMN auto_replanned BOOLEAN NOT NULL DEFAULT FALSE;

WITH RECURSIVE plan_families AS (
    SELECT plan_id, plan_id AS family_id
    FROM travel_plan
    WHERE previous_plan_id IS NULL
    UNION ALL
    SELECT child.plan_id, parent.family_id
    FROM travel_plan child
    JOIN plan_families parent ON child.previous_plan_id = parent.plan_id
)
UPDATE travel_plan plan
SET plan_family_id = family.family_id,
    alternative_key = 'legacy-plan',
    strategy = 'CUSTOM'
FROM plan_families family
WHERE plan.plan_id = family.plan_id;

UPDATE travel_plan
SET plan_family_id = plan_id,
    alternative_key = 'legacy-plan',
    strategy = 'CUSTOM'
WHERE plan_family_id IS NULL;

ALTER TABLE travel_plan
    ALTER COLUMN plan_family_id SET NOT NULL,
    ALTER COLUMN alternative_key SET NOT NULL,
    ALTER COLUMN strategy SET NOT NULL,
    DROP CONSTRAINT plan_trip_version_unique,
    ADD CONSTRAINT plan_family_version_unique UNIQUE (plan_family_id, version),
    ADD CONSTRAINT plan_alternative_key_check CHECK (
        alternative_key ~ '^[a-z][a-z0-9-]{1,39}$'
    ),
    ADD CONSTRAINT plan_strategy_check CHECK (
        strategy IN ('BUDGET', 'BALANCED', 'COMFORT', 'LOW_TRANSIT', 'CUSTOM')
    );

CREATE INDEX idx_plan_family_version
    ON travel_plan (plan_family_id, version DESC);

ALTER TABLE task_record
    ADD COLUMN result_resource_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
