package com.tripagent.nexus.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tripagent.nexus.application.service.MapTileService;
import com.tripagent.nexus.application.service.MapTileService.MapTile;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class MapTileControllerTest {

  @Test
  void exposesTileCacheAndContentHeaders() {
    MapTileService service = mock(MapTileService.class);
    byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47};
    when(service.get(1, 1, 1)).thenReturn(new MapTile(png, "public, max-age=3600", "\"tile-1\""));

    ResponseEntity<byte[]> response = new MapTileController(service).get(1, 1, 1);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
        .isEqualTo("public, max-age=3600");
    assertThat(response.getHeaders().getETag()).isEqualTo("\"tile-1\"");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getBody()).containsExactly(png);
  }
}
