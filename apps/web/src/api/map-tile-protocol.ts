import type { AddProtocolAction } from "maplibre-gl";
import { apiAdapter } from "./client";

export const MAP_TILE_PROTOCOL = "tripagent-map";
export const MAP_TILE_URL_TEMPLATE = `${MAP_TILE_PROTOCOL}://tiles/{z}/{x}/{y}`;

const tileUrlPattern = /^tripagent-map:\/\/tiles\/(\d+)\/(\d+)\/(\d+)$/;
let registered = false;

export function parseMapTileUrl(url: string) {
  const match = tileUrlPattern.exec(url);
  if (!match) {
    throw new Error("地图瓦片地址无效。");
  }
  return {
    zoom: Number(match[1]),
    x: Number(match[2]),
    y: Number(match[3]),
  };
}

export function registerMapTileProtocol(
  addProtocol: (customProtocol: string, loadFn: AddProtocolAction) => void,
) {
  if (registered) {
    return;
  }

  addProtocol(MAP_TILE_PROTOCOL, async (request, abortController) => {
    const tile = parseMapTileUrl(request.url);
    return apiAdapter.maps.getTile(tile.zoom, tile.x, tile.y, abortController.signal);
  });
  registered = true;
}
