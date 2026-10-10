package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.TravelerType;
import java.util.List;
import java.util.UUID;

public record Traveler(
    UUID travelerId, String displayName, TravelerType type, List<String> accessibilityNeeds) {}
