package com.tripagent.nexus.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tripagent.nexus.domain.model.DomainEnums.TripStatus;
import java.time.Instant;
import java.util.UUID;

public record Trip(
    UUID tripId,
    UUID userId,
    String title,
    String timeZone,
    TripStatus status,
    @JsonInclude(JsonInclude.Include.NON_NULL) TravelRequirement requirement,
    Instant createdAt,
    Instant updatedAt) {}
