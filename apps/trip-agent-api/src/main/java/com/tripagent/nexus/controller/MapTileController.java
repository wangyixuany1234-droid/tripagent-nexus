package com.tripagent.nexus.controller;

import com.tripagent.nexus.application.service.MapTileService;
import com.tripagent.nexus.application.service.MapTileService.MapTile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/maps/tiles")
public class MapTileController {

  private final MapTileService mapTileService;

  public MapTileController(MapTileService mapTileService) {
    this.mapTileService = mapTileService;
  }

  @GetMapping(value = "/{zoom}/{x}/{y}.png", produces = MediaType.IMAGE_PNG_VALUE)
  public ResponseEntity<byte[]> get(
      @PathVariable int zoom, @PathVariable int x, @PathVariable int y) {
    MapTile tile = mapTileService.get(zoom, x, y);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .header(HttpHeaders.CACHE_CONTROL, tile.cacheControl())
            .header("X-Content-Type-Options", "nosniff");
    if (tile.etag() != null && !tile.etag().isBlank()) {
      response.header(HttpHeaders.ETAG, tile.etag());
    }
    return response.body(tile.body());
  }
}
