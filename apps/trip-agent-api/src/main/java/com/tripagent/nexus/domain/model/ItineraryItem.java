package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.ItemType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ItineraryItem(
    UUID itemId,
    ItemType type,
    String title,
    String description,
    Instant startTime,
    Instant endTime,
    Place location,
    BigDecimal amount,
    String currency,
    List<UUID> evidenceIds) {}
