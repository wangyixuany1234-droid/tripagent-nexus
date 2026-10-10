package com.tripagent.nexus.application.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.guardrail.ExternalDomainGuard;
import com.tripagent.nexus.infrastructure.config.AiServiceProperties;
import com.tripagent.nexus.infrastructure.config.GuardrailProperties;
import com.tripagent.nexus.infrastructure.config.MapTileProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class MapTileWireMockIntegrationTest {

  private WireMockServer server;
  private MapTileService service;

  @BeforeEach
  void setUp() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    server.stubFor(
        get(urlEqualTo("/2/1/2.png"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/png")
                    .withHeader("Cache-Control", "public, max-age=60")
                    .withBody(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47})));

    MapTileProperties properties = new MapTileProperties();
    properties.setTileBaseUrl(URI.create(server.baseUrl()));
    GuardrailProperties guardrails = new GuardrailProperties();
    guardrails.setAllowedExternalHosts(List.of("127.0.0.1", "localhost"));
    service =
        new MapTileService(
            RestClient.builder().baseUrl(server.baseUrl()).build(),
            properties,
            new ExternalDomainGuard(guardrails, new AiServiceProperties()));
  }

  @AfterEach
  void tearDown() {
    server.stop();
  }

  @Test
  void mapsARealHttpStubWithoutContactingTheMapProvider() {
    MapTileService.MapTile tile = service.get(2, 1, 2);

    assertThat(tile.body()).containsExactly((byte) 0x89, 0x50, 0x4e, 0x47);
    assertThat(tile.cacheControl()).isEqualTo("public, max-age=60");
    server.verify(getRequestedFor(urlEqualTo("/2/1/2.png")));
  }

  @Test
  void rejectsUnexpectedProviderContentFromTheHttpBoundary() {
    server.stubFor(
        get(urlEqualTo("/0/0/0.png"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html")
                    .withBody("<html>synthetic error</html>")));

    assertThatThrownBy(() -> service.get(0, 0, 0))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MAP_TILE_RESPONSE_INVALID"));
  }
}
