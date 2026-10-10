package com.tripagent.nexus.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.infrastructure.config.McpProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpToolCatalogMockTest {

  private McpToolCatalog catalog;

  @BeforeEach
  void setUp() {
    McpProperties properties = new McpProperties();
    properties.setProviderMode(McpProperties.ProviderMode.MOCK);
    catalog = new McpToolCatalog(new ObjectMapper(), null, null, null, null, properties);
  }

  @Test
  void returnsDeterministicWeatherRouteAndExchangeRateData() {
    McpToolResult weather =
        catalog.execute(
            McpToolName.WEATHER_QUERY,
            Map.of("city", "北京", "startDate", "2026-10-01", "endDate", "2026-10-03"));
    McpToolResult route =
        catalog.execute(
            McpToolName.ROUTE_CALCULATE,
            Map.of(
                "origin",
                "故宫博物院",
                "destination",
                "景山公园",
                "departureTime",
                "2026-10-01T10:00:00+08:00"));
    McpToolResult exchange =
        catalog.execute(
            McpToolName.EXCHANGE_RATE,
            Map.of("amount", "100.0000", "fromCurrency", "CNY", "toCurrency", "USD"));

    assertMockSuccess(weather);
    assertThat(weather.data().path("city").asText()).isEqualTo("北京");
    assertMockSuccess(route);
    assertThat(route.data().path("durationMinutes").asInt()).isEqualTo(45);
    assertMockSuccess(exchange);
    assertThat(exchange.data().path("rate").decimalValue()).isEqualByComparingTo("0.1400");
  }

  @Test
  void returnsDeterministicPlaceDataWithoutCallingRetrieval() {
    McpToolResult result =
        catalog.execute(McpToolName.PLACE_SEARCH, Map.of("query", "历史景点", "city", "北京"));

    assertMockSuccess(result);
    assertThat(result.data().path("places").get(0).path("sourceId").asText())
        .isEqualTo("mock-source-place-1");
  }

  private static void assertMockSuccess(McpToolResult result) {
    assertThat(result.status()).isEqualTo(McpToolResult.Status.SUCCEEDED);
    assertThat(result.providerMode()).isEqualTo(McpToolResult.ProviderMode.MOCK);
    assertThat(result.warnings()).isNotEmpty();
  }
}
