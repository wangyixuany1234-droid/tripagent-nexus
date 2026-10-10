package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.DeadLetterStatus;
import java.time.Instant;
import java.util.UUID;

public record DeadLetterRecord(
    UUID deadLetterId,
    UUID originalEventId,
    String originalTopic,
    String originalEventType,
    String eventVersion,
    UUID correlationId,
    UUID causationId,
    String traceId,
    UUID taskId,
    String failureCode,
    String failureReason,
    DeadLetterStatus status,
    int replayCount,
    UUID replayEventId,
    UUID replayTaskId,
    String replayReason,
    Instant createdAt,
    Instant updatedAt) {}
