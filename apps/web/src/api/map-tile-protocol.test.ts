import { describe, expect, it } from "vitest";
import { parseMapTileUrl } from "./map-tile-protocol";

describe("map tile protocol", () => {
  it("extracts generated SDK path parameters", () => {
    expect(parseMapTileUrl("tripagent-map://tiles/13/6743/3102")).toEqual({
      zoom: 13,
      x: 6743,
      y: 3102,
    });
  });

  it("rejects an unrelated URL", () => {
    expect(() => parseMapTileUrl("https://tile.openstreetmap.org/0/0/0.png")).toThrow(
      "地图瓦片地址无效",
    );
  });
});
