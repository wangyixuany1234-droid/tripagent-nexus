package com.tripagent.nexus.domain.model;

import java.time.Instant;
import java.util.UUID;

public record TaskEvent(
    UUID eventId, UUID taskId, String stage, int progress, String message, Instant timestamp) {}
