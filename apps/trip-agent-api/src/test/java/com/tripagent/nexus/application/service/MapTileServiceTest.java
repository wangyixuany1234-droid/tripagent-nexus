package com.tripagent.nexus.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.guardrail.ExternalDomainGuard;
import com.tripagent.nexus.infrastructure.config.AiServiceProperties;
import com.tripagent.nexus.infrastructure.config.GuardrailProperties;
import com.tripagent.nexus.infrastructure.config.MapTileProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MapTileServiceTest {

  private MockRestServiceServer server;
  private MapTileProperties properties;
  private MapTileService service;

  @BeforeEach
  void setUp() {
    properties = new MapTileProperties();
    properties.setTileBaseUrl(URI.create("https://tiles.example.test"));

    GuardrailProperties guardrailProperties = new GuardrailProperties();
    guardrailProperties.setAllowedExternalHosts(List.of("tiles.example.test"));
    ExternalDomainGuard domainGuard =
        new ExternalDomainGuard(guardrailProperties, new AiServiceProperties());

    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    service =
        new MapTileService(
            builder.baseUrl(properties.getTileBaseUrl()).build(), properties, domainGuard);
    service.validateProvider();
  }

  @Test
  void returnsValidatedPngWithProviderCacheMetadata() {
    byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47};
    server
        .expect(requestTo("https://tiles.example.test/1/1/1.png"))
        .andRespond(
            withSuccess(png, MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .header(HttpHeaders.ETAG, "\"tile-1\""));

    MapTileService.MapTile tile = service.get(1, 1, 1);

    assertThat(tile.body()).containsExactly(png);
    assertThat(tile.cacheControl()).isEqualTo("public, max-age=3600");
    assertThat(tile.etag()).isEqualTo("\"tile-1\"");
    server.verify();
  }

  @Test
  void rejectsCoordinatesOutsideTheZoomGridBeforeCallingProvider() {
    assertThatThrownBy(() -> service.get(2, 4, 0))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> {
              assertThat(exception.getCode()).isEqualTo("MAP_TILE_COORDINATES_INVALID");
              assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
  }

  @ParameterizedTest
  @CsvSource({"-1, 0, 0", "20, 0, 0", "2, -1, 0", "2, 0, -1", "2, 0, 4"})
  void rejectsEveryUnsupportedCoordinateBoundary(int zoom, int x, int y) {
    assertThatThrownBy(() -> service.get(zoom, x, y))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MAP_TILE_COORDINATES_INVALID"));
  }

  @Test
  void rejectsRequestsWhenProviderIsDisabled() {
    properties.setEnabled(false);

    assertThatThrownBy(() -> service.get(0, 0, 0))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception ->
                assertThat(exception.getCode()).isEqualTo("MAP_TILE_PROVIDER_UNAVAILABLE"));
  }

  @Test
  void rejectsNonPngProviderResponse() {
    server
        .expect(requestTo("https://tiles.example.test/0/0/0.png"))
        .andRespond(withSuccess("not an image", MediaType.TEXT_PLAIN));

    assertThatThrownBy(() -> service.get(0, 0, 0))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> {
              assertThat(exception.getCode()).isEqualTo("MAP_TILE_RESPONSE_INVALID");
              assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            });
  }

  @Test
  void rejectsEmptyProviderResponse() {
    server
        .expect(requestTo("https://tiles.example.test/0/0/0.png"))
        .andRespond(withSuccess(new byte[0], MediaType.IMAGE_PNG));

    assertInvalidProviderResponse();
  }

  @Test
  void rejectsProviderResponseWithoutContentType() {
    server
        .expect(requestTo("https://tiles.example.test/0/0/0.png"))
        .andRespond(withStatus(HttpStatus.OK).body(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47}));

    assertInvalidProviderResponse();
  }

  @Test
  void rejectsProviderResponseAboveConfiguredByteLimit() {
    properties.setMaxTileBytes(3);
    server
        .expect(requestTo("https://tiles.example.test/0/0/0.png"))
        .andRespond(withSuccess(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47}, MediaType.IMAGE_PNG));

    assertInvalidProviderResponse();
  }

  @Test
  void appliesDefaultCachePolicyWhenProviderOmitsCacheControl() {
    byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47};
    server
        .expect(requestTo("https://tiles.example.test/0/0/0.png"))
        .andRespond(withSuccess(png, MediaType.IMAGE_PNG));

    MapTileService.MapTile tile = service.get(0, 0, 0);

    assertThat(tile.cacheControl()).isEqualTo(MapTileService.DEFAULT_CACHE_CONTROL);
  }

  @Test
  void appliesDefaultCachePolicyWhenProviderReturnsBlankCacheControl() {
    byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47};
    server
        .expect(requestTo("https://tiles.example.test/0/0/0.png"))
        .andRespond(withSuccess(png, MediaType.IMAGE_PNG).header(HttpHeaders.CACHE_CONTROL, " "));

    MapTileService.MapTile tile = service.get(0, 0, 0);

    assertThat(tile.cacheControl()).isEqualTo(MapTileService.DEFAULT_CACHE_CONTROL);
  }

  @Test
  void mapsProviderFailureToStableApiError() {
    server.expect(requestTo("https://tiles.example.test/0/0/0.png")).andRespond(withServerError());

    assertThatThrownBy(() -> service.get(0, 0, 0))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> {
              assertThat(exception.getCode()).isEqualTo("MAP_TILE_PROVIDER_UNAVAILABLE");
              assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            });
  }

  private void assertInvalidProviderResponse() {
    assertThatThrownBy(() -> service.get(0, 0, 0))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> {
              assertThat(exception.getCode()).isEqualTo("MAP_TILE_RESPONSE_INVALID");
              assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            });
  }
}
