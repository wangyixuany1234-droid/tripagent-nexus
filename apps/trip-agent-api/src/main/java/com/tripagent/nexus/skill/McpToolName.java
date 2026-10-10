package com.tripagent.nexus.skill;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum McpToolName {
  PLACE_SEARCH("place_search"),
  WEATHER_QUERY("weather_query"),
  ROUTE_CALCULATE("route_calculate"),
  EXCHANGE_RATE("exchange_rate"),
  OPENING_HOURS("opening_hours"),
  BOOKING_PARSE("booking_parse"),
  BUDGET_VALIDATE("budget_validate"),
  ITINERARY_STORE("itinerary_store");

  private final String value;

  McpToolName(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static Set<String> names() {
    return Arrays.stream(values()).map(McpToolName::value).collect(Collectors.toUnmodifiableSet());
  }
}
