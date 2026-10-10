package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.Pace;
import com.tripagent.nexus.domain.model.DomainEnums.TransportMode;
import java.util.List;
import java.util.UUID;

public record UserProfile(
    UUID userId,
    Pace preferredPace,
    List<String> interests,
    List<String> dietaryRestrictions,
    List<String> accommodationPreferences,
    List<TransportMode> transportPreferences,
    List<String> budgetHabits) {}
