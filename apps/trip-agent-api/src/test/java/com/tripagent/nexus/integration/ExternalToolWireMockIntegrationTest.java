package com.tripagent.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ExternalToolWireMockIntegrationTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private static WireMockServer server;

  @BeforeAll
  static void startWireMock() {
    server =
        new WireMockServer(
            WireMockConfiguration.options()
                .dynamicPort()
                .usingFilesUnderDirectory(findWireMockRoot().toString()));
    server.start();
  }

  @AfterAll
  static void stopWireMock() {
    server.stop();
  }

  @Test
  void servesDeterministicMapWeatherExchangeAndRouteFixtures() throws Exception {
    HttpResponse<byte[]> map = send("GET", "/maps/2/1/2.png", null);
    assertThat(map.statusCode()).isEqualTo(200);
    assertThat(map.headers().firstValue("Content-Type")).contains("image/png");
    assertThat(map.body()).isNotEmpty();

    for (String path : List.of("/tools/weather", "/tools/exchange-rate", "/tools/route")) {
      HttpResponse<byte[]> response = send("POST", path, null);
      assertThat(response.statusCode()).isEqualTo(200);
      JsonNode body = OBJECT_MAPPER.readTree(response.body());
      assertThat(body.path("providerMode").asText()).isEqualTo("MOCK");
    }
  }

  @Test
  void switchesBetweenRateLimitProviderErrorAndMalformedResponses() throws Exception {
    assertThat(send("POST", "/tools/weather", "rate-limit").statusCode()).isEqualTo(429);
    assertThat(send("POST", "/tools/exchange-rate", "error").statusCode()).isEqualTo(503);

    HttpResponse<byte[]> malformed = send("POST", "/tools/route", "malformed");
    assertThat(malformed.statusCode()).isEqualTo(200);
    assertThatThrownBy(() -> OBJECT_MAPPER.readTree(malformed.body()))
        .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
  }

  @Test
  void exposesAControllableTimeout() {
    assertThatThrownBy(() -> send("POST", "/tools/route", "timeout"))
        .isInstanceOf(HttpTimeoutException.class);
  }

  private static HttpResponse<byte[]> send(String method, String path, String scenario)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .timeout(Duration.ofSeconds(2))
            .header("Content-Type", "application/json");
    if (scenario != null) {
      request.header("X-QA-Scenario", scenario);
    }
    if ("POST".equals(method)) {
      request.POST(HttpRequest.BodyPublishers.ofString("{}"));
    } else {
      request.GET();
    }
    return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static Path findWireMockRoot() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      Path candidate = directory.resolve("qa/wiremock");
      if (Files.isDirectory(candidate.resolve("mappings"))) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Could not locate QA WireMock mappings");
  }
}
