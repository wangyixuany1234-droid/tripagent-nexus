package com.tripagent.nexus.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AgentName {
  REQUIREMENT("RequirementAgent"),
  RESEARCH("ResearchAgent"),
  TRANSPORT("TransportAgent"),
  ACCOMMODATION("AccommodationAgent"),
  RECOMMENDATION("RecommendationAgent"),
  BUDGET("BudgetAgent"),
  REVIEW("ReviewAgent"),
  COORDINATOR("CoordinatorAgent");

  private final String cardName;

  AgentName(String cardName) {
    this.cardName = cardName;
  }

  @JsonValue
  public String cardName() {
    return cardName;
  }

  @JsonCreator
  public static AgentName fromCardName(String value) {
    for (AgentName name : values()) {
      if (name.cardName.equals(value)) {
        return name;
      }
    }
    throw new IllegalArgumentException("Unknown Agent name: " + value);
  }
}
