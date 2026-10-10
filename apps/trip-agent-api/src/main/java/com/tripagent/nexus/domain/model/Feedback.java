package com.tripagent.nexus.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Feedback(
    UUID feedbackId,
    UUID tripId,
    @JsonInclude(JsonInclude.Include.NON_NULL) UUID planId,
    @JsonInclude(JsonInclude.Include.NON_NULL) String targetType,
    @JsonInclude(JsonInclude.Include.NON_NULL) String targetId,
    @JsonInclude(JsonInclude.Include.NON_NULL) String sentiment,
    int rating,
    String comment,
    List<String> tags,
    boolean memoryAccepted,
    Instant createdAt) {}
