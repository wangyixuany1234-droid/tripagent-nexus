package com.tripagent.nexus.application.service;

import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.guardrail.ExternalDomainGuard;
import com.tripagent.nexus.infrastructure.config.MapTileProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class MapTileService {

  static final String DEFAULT_CACHE_CONTROL =
      "public, max-age=86400, stale-while-revalidate=604800";

  private final RestClient restClient;
  private final MapTileProperties properties;
  private final ExternalDomainGuard domainGuard;

  public MapTileService(
      @Qualifier("mapTileRestClient") RestClient restClient,
      MapTileProperties properties,
      ExternalDomainGuard domainGuard) {
    this.restClient = restClient;
    this.properties = properties;
    this.domainGuard = domainGuard;
  }

  @PostConstruct
  void validateProvider() {
    domainGuard.requireAllowed(properties.getTileBaseUrl());
  }

  public MapTile get(int zoom, int x, int y) {
    requireValidCoordinates(zoom, x, y);
    if (!properties.isEnabled()) {
      throw new ApiException(
          "MAP_TILE_PROVIDER_UNAVAILABLE",
          HttpStatus.BAD_GATEWAY,
          "The map tile provider is disabled.");
    }

    try {
      ResponseEntity<byte[]> response =
          restClient.get().uri("/{zoom}/{x}/{y}.png", zoom, x, y).retrieve().toEntity(byte[].class);
      return validateResponse(response);
    } catch (ApiException exception) {
      throw exception;
    } catch (RestClientException exception) {
      throw new ApiException(
          "MAP_TILE_PROVIDER_UNAVAILABLE",
          HttpStatus.BAD_GATEWAY,
          "The map tile provider could not serve this tile.");
    }
  }

  private void requireValidCoordinates(int zoom, int x, int y) {
    int tilesPerAxis = zoom >= 0 && zoom <= properties.getMaxZoom() ? 1 << zoom : 0;
    if (tilesPerAxis == 0 || x < 0 || y < 0 || x >= tilesPerAxis || y >= tilesPerAxis) {
      throw new ApiException(
          "MAP_TILE_COORDINATES_INVALID",
          HttpStatus.BAD_REQUEST,
          "The map tile coordinates are outside the supported world grid.");
    }
  }

  private MapTile validateResponse(ResponseEntity<byte[]> response) {
    byte[] body = response.getBody();
    MediaType contentType = response.getHeaders().getContentType();
    if (body == null
        || body.length == 0
        || body.length > properties.getMaxTileBytes()
        || contentType == null
        || !MediaType.IMAGE_PNG.isCompatibleWith(contentType)) {
      throw new ApiException(
          "MAP_TILE_RESPONSE_INVALID",
          HttpStatus.BAD_GATEWAY,
          "The map tile provider returned an invalid tile.");
    }

    String cacheControl = response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
    return new MapTile(
        body,
        cacheControl == null || cacheControl.isBlank() ? DEFAULT_CACHE_CONTROL : cacheControl,
        response.getHeaders().getFirst(HttpHeaders.ETAG));
  }

  public record MapTile(byte[] body, String cacheControl, String etag) {}
}
