package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.Pace;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TravelRequirement(
    UUID tripId,
    Place origin,
    List<Place> destinations,
    LocalDate startDate,
    LocalDate endDate,
    List<Traveler> travelers,
    BigDecimal totalBudget,
    String currency,
    List<String> interests,
    Pace pace,
    List<String> mustVisit,
    List<String> avoid,
    String freeTextConstraints,
    Instant updatedAt) {}
