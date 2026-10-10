WITH
orphan_tasks AS (
    SELECT task.task_id::text AS violation_id
    FROM task_record task
    LEFT JOIN uploaded_document document
        ON task.task_type = 'DOCUMENT_PARSE'
       AND document.document_id = task.resource_id
    LEFT JOIN travel_plan plan
        ON task.task_type IN ('PLAN_GENERATION', 'PLAN_REPLAN')
       AND plan.plan_id = task.resource_id
    LEFT JOIN evaluation_run evaluation
        ON task.task_type = 'EVALUATION_REPLAY'
       AND evaluation.run_id = task.resource_id
    WHERE (task.task_type = 'DOCUMENT_PARSE' AND document.document_id IS NULL)
       OR (task.task_type IN ('PLAN_GENERATION', 'PLAN_REPLAN') AND plan.plan_id IS NULL)
       OR (task.task_type = 'EVALUATION_REPLAY' AND evaluation.run_id IS NULL)
),
orphan_plans AS (
    SELECT plan.plan_id::text AS violation_id
    FROM travel_plan plan
    LEFT JOIN trip ON trip.trip_id = plan.trip_id
    LEFT JOIN travel_plan previous ON previous.plan_id = plan.previous_plan_id
    LEFT JOIN task_record task
        ON task.resource_id = plan.plan_id
       AND task.task_type IN ('PLAN_GENERATION', 'PLAN_REPLAN')
    WHERE trip.trip_id IS NULL
       OR (plan.previous_plan_id IS NOT NULL AND previous.plan_id IS NULL)
       OR (plan.status = 'GENERATING' AND task.task_id IS NULL)
),
duplicate_plan_versions AS (
    SELECT (plan_family_id::text || ':' || version::text) AS violation_id
    FROM travel_plan
    GROUP BY plan_family_id, version
    HAVING count(*) > 1
),
plan_evidence AS (
    SELECT plan.plan_id, evidence.value ->> 'evidenceId' AS evidence_id
    FROM travel_plan plan
    CROSS JOIN LATERAL jsonb_array_elements(plan.evidence) evidence(value)
),
item_evidence AS (
    SELECT DISTINCT
        plan.plan_id,
        evidence_id.value AS evidence_id
    FROM travel_plan plan
    CROSS JOIN LATERAL jsonb_array_elements(plan.days) day(value)
    CROSS JOIN LATERAL jsonb_array_elements(
        COALESCE(day.value -> 'items', '[]'::jsonb)
    ) item(value)
    CROSS JOIN LATERAL jsonb_array_elements_text(
        COALESCE(item.value -> 'evidenceIds', '[]'::jsonb)
    ) evidence_id(value)
),
unreferenced_evidence AS (
    SELECT (evidence.plan_id::text || ':' || evidence.evidence_id) AS violation_id
    FROM plan_evidence evidence
    LEFT JOIN item_evidence item
        ON item.plan_id = evidence.plan_id
       AND item.evidence_id = evidence.evidence_id
    WHERE evidence.evidence_id IS NULL
       OR item.evidence_id IS NULL
),
unfinished_outbox AS (
    SELECT event_id::text AS violation_id
    FROM event_outbox
    WHERE status = 'PENDING'
      AND next_attempt_at < clock_timestamp()
          - make_interval(secs => :stale_outbox_seconds)
),
long_running_tasks AS (
    SELECT task_id::text AS violation_id
    FROM task_record
    WHERE status IN ('CREATED', 'QUEUED', 'RUNNING')
      AND heartbeat_at < clock_timestamp()
          - make_interval(secs => :long_running_task_seconds)
),
stale_inbox_claims AS (
    SELECT inbox_id::text AS violation_id
    FROM event_inbox
    WHERE status = 'PROCESSING'
      AND updated_at < clock_timestamp()
          - make_interval(secs => :stale_inbox_seconds)
),
missing_result_plans AS (
    SELECT (task.task_id::text || ':' || result_id.value) AS violation_id
    FROM task_record task
    CROSS JOIN LATERAL jsonb_array_elements_text(task.result_resource_ids) result_id(value)
    LEFT JOIN travel_plan plan ON plan.plan_id = result_id.value::uuid
    WHERE task.task_type IN ('PLAN_GENERATION', 'PLAN_REPLAN')
      AND plan.plan_id IS NULL
),
successful_tasks_without_plans AS (
    SELECT task.task_id::text AS violation_id
    FROM task_record task
    WHERE task.task_type IN ('PLAN_GENERATION', 'PLAN_REPLAN')
      AND task.status IN ('SUCCEEDED', 'PARTIAL_SUCCESS')
      AND (
          jsonb_array_length(task.result_resource_ids) = 0
          OR EXISTS (
              SELECT 1
              FROM jsonb_array_elements_text(task.result_resource_ids) result_id(value)
              LEFT JOIN travel_plan plan ON plan.plan_id = result_id.value::uuid
              WHERE plan.plan_id IS NULL
          )
      )
),
task_events_without_provenance AS (
    SELECT event_id::text AS violation_id
    FROM task_event
    WHERE trace_id IS NULL
       OR source_event_id IS NULL
       OR source_event_type IS NULL
       OR source_event_type = ''
),
incomplete_dead_letter_audits AS (
    SELECT dead_letter_id::text AS violation_id
    FROM dead_letter_event
    WHERE status = 'REPLAYED'
      AND (
          replay_count < 1
          OR replay_event_id IS NULL
          OR replay_reason IS NULL
          OR replay_reason = ''
          OR replayed_at IS NULL
      )
),
checks AS (
    SELECT 'orphanTasks' AS check_name, violation_id FROM orphan_tasks
    UNION ALL
    SELECT 'orphanPlans', violation_id FROM orphan_plans
    UNION ALL
    SELECT 'duplicatePlanVersions', violation_id FROM duplicate_plan_versions
    UNION ALL
    SELECT 'unreferencedEvidence', violation_id FROM unreferenced_evidence
    UNION ALL
    SELECT 'unfinishedKafkaOutbox', violation_id FROM unfinished_outbox
    UNION ALL
    SELECT 'longRunningTasks', violation_id FROM long_running_tasks
    UNION ALL
    SELECT 'staleInboxClaims', violation_id FROM stale_inbox_claims
    UNION ALL
    SELECT 'missingTaskResultPlans', violation_id FROM missing_result_plans
    UNION ALL
    SELECT 'successfulTasksWithoutPlans', violation_id FROM successful_tasks_without_plans
    UNION ALL
    SELECT 'taskEventsWithoutProvenance', violation_id FROM task_events_without_provenance
    UNION ALL
    SELECT 'incompleteDeadLetterAudits', violation_id FROM incomplete_dead_letter_audits
),
check_names(check_name) AS (
    VALUES
        ('orphanTasks'),
        ('orphanPlans'),
        ('duplicatePlanVersions'),
        ('unreferencedEvidence'),
        ('unfinishedKafkaOutbox'),
        ('longRunningTasks'),
        ('staleInboxClaims'),
        ('missingTaskResultPlans'),
        ('successfulTasksWithoutPlans'),
        ('taskEventsWithoutProvenance'),
        ('incompleteDeadLetterAudits')
)
SELECT
    names.check_name,
    count(checks.violation_id),
    COALESCE(
        (
            SELECT jsonb_agg(sample.violation_id ORDER BY sample.violation_id)
            FROM (
                SELECT violation_id
                FROM checks candidate
                WHERE candidate.check_name = names.check_name
                ORDER BY violation_id
                LIMIT :sample_limit
            ) sample
        ),
        '[]'::jsonb
    )
FROM check_names names
LEFT JOIN checks ON checks.check_name = names.check_name
GROUP BY names.check_name
ORDER BY names.check_name;
