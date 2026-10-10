import { useEffect, useMemo, useRef, useState } from "react";
import type { Place } from "@api-generated/models/Place";
import { MapPin, RotateCw } from "lucide-react";
import type {
  GeoJSONSource,
  LngLatBoundsLike,
  Map as MapLibreMap,
  Marker,
  StyleSpecification,
} from "maplibre-gl";
import {
  MAP_TILE_URL_TEMPLATE,
  registerMapTileProtocol,
} from "@/api/map-tile-protocol";
import { cn } from "@/lib/utils";
import { useAppStore } from "@/stores/app-store";

type LocatedPlace = Place & Required<Pick<Place, "latitude" | "longitude">>;
const emptyPlaces: Place[] = [];

export interface MapRoutePoint {
  itemId: string;
  place: Place;
}

export interface MapRouteGroup {
  dayNumber: number;
  color: string;
  points: MapRoutePoint[];
}

function hasCoordinates(place: Place): place is LocatedPlace {
  return typeof place.longitude === "number" && typeof place.latitude === "number";
}

export function MapContainer({
  places = emptyPlaces,
  label = "城市探索地图",
  className,
  showStatus = true,
  routes,
  selectedItemId,
  onSelectItem,
}: {
  places?: Place[];
  label?: string;
  className?: string;
  showStatus?: boolean;
  routes?: MapRouteGroup[];
  selectedItemId?: string;
  onSelectItem?: (itemId: string) => void;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | undefined>(undefined);
  const markerElementsRef = useRef(new Map<string, HTMLButtonElement>());
  const onSelectItemRef = useRef(onSelectItem);
  const theme = useAppStore((state) => state.theme);
  const [mapState, setMapState] = useState<"loading" | "ready" | "error">("loading");
  const [retryKey, setRetryKey] = useState(0);
  onSelectItemRef.current = onSelectItem;
  const locatedGroups = useMemo(
    () =>
      routes?.map((route) => ({
        ...route,
        points: route.points.filter(
          (
            point,
          ): point is MapRoutePoint & {
            place: LocatedPlace;
          } => hasCoordinates(point.place),
        ),
      })) ??
      [
        {
          dayNumber: 1,
          color: "#247fae",
          points: places.filter(hasCoordinates).map((place, index) => ({
            itemId: `place-${index}`,
            place,
          })),
        },
      ],
    [places, routes],
  );
  const locatedPoints = useMemo(
    () =>
      locatedGroups.flatMap((route) =>
        route.points.map((point) => ({
          ...point,
          color: route.color,
          dayNumber: route.dayNumber,
        })),
      ),
    [locatedGroups],
  );
  const coordinates = useMemo(
    () =>
      locatedPoints.map(
        (point) =>
          [point.place.longitude, point.place.latitude] as [number, number],
      ),
    [locatedPoints],
  );
  const hasRouteGeometry = locatedGroups.some((route) => route.points.length > 1);

  useEffect(() => {
    if (!containerRef.current) {
      return;
    }

    let disposed = false;
    let map: MapLibreMap | undefined;
    let markers: Marker[] = [];
    let resizeObserver: ResizeObserver | undefined;
    markerElementsRef.current.clear();
    setMapState("loading");
    const dark = document.documentElement.classList.contains("dark");
    const routeData = {
      type: "FeatureCollection" as const,
      features: locatedGroups
        .filter((route) => route.points.length > 1)
        .map((route) => ({
          type: "Feature" as const,
          properties: { color: route.color, dayNumber: route.dayNumber },
          geometry: {
            type: "LineString" as const,
            coordinates: route.points.map(
              (point) =>
                [point.place.longitude, point.place.latitude] as [number, number],
            ),
          },
        })),
    };
    const style: StyleSpecification = {
      version: 8,
      sources: {
        basemap: {
          type: "raster",
          tiles: [MAP_TILE_URL_TEMPLATE],
          tileSize: 256,
          minzoom: 0,
          maxzoom: 19,
          attribution:
            '<a href="https://www.openstreetmap.org/copyright" target="_blank">© OpenStreetMap contributors</a>',
        },
        route: {
          type: "geojson",
          data: routeData,
        },
      },
      layers: [
        {
          id: "background",
          type: "background",
          paint: { "background-color": dark ? "#172126" : "#dce7e9" },
        },
        {
          id: "basemap",
          type: "raster",
          source: "basemap",
          paint: dark
            ? {
                "raster-brightness-max": 0.82,
                "raster-contrast": 0.08,
                "raster-saturation": -0.18,
              }
            : {
                "raster-brightness-max": 1,
                "raster-contrast": 0.04,
                "raster-saturation": -0.05,
              },
        },
        {
          id: "route-shadow",
          type: "line",
          source: "route",
          paint: {
            "line-color": dark ? "#0e1518" : "#ffffff",
            "line-width": 8,
            "line-opacity": 0.65,
          },
        },
        {
          id: "route-line",
          type: "line",
          source: "route",
          paint: {
            "line-color": ["get", "color"],
            "line-width": 4,
            "line-dasharray": [2, 2],
          },
        },
      ],
    };

    void import("maplibre-gl").then((maplibregl) => {
      if (disposed || !containerRef.current) {
        return;
      }

      registerMapTileProtocol(maplibregl.addProtocol);
      map = new maplibregl.Map({
        container: containerRef.current,
        style,
        center: coordinates[0] ?? [12, 20],
        zoom: coordinates.length > 0 ? 12 : 1.25,
        minZoom: 1,
        maxZoom: 18,
        attributionControl: false,
        cooperativeGestures: true,
        locale: {
          "AttributionControl.ToggleAttribution": "显示或隐藏地图来源",
          "NavigationControl.ZoomIn": "放大地图",
          "NavigationControl.ZoomOut": "缩小地图",
          "CooperativeGesturesHandler.WindowsHelpText":
            "按住 Ctrl 键并滚动以缩放地图",
          "CooperativeGesturesHandler.MacHelpText":
            "按住 Command 键并滚动以缩放地图",
          "CooperativeGesturesHandler.MobileHelpText":
            "使用两根手指移动地图",
        },
      });
      mapRef.current = map;

      map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
      map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-right");
      resizeObserver = new ResizeObserver(() => map?.resize());
      resizeObserver.observe(containerRef.current);

      markers = locatedPoints.map((point, index) => {
        const coordinate: [number, number] = [
          point.place.longitude,
          point.place.latitude,
        ];
        const element = document.createElement("button");
        element.type = "button";
        element.className =
          "group flex min-w-max flex-col items-center border-0 bg-transparent p-0 text-left";
        element.setAttribute("aria-label", `查看地点：${point.place.name}`);
        element.setAttribute("aria-pressed", String(point.itemId === selectedItemId));
        element.addEventListener("click", () => onSelectItemRef.current?.(point.itemId));
        markerElementsRef.current.set(point.itemId, element);

        const number = document.createElement("span");
        number.className =
          "flex size-7 items-center justify-center rounded-full border-2 border-white text-xs font-bold text-white shadow-md";
        number.style.backgroundColor = point.color;
        number.style.boxShadow =
          point.itemId === selectedItemId ? "0 0 0 4px rgba(255,255,255,.9)" : "";
        number.textContent = String(index + 1);

        const name = document.createElement("span");
        name.className =
          "mt-1 max-w-36 rounded border border-border bg-surface/95 px-2 py-1 text-xs font-bold text-navy-strong shadow-md backdrop-blur-sm";
        name.textContent = point.place.name;
        element.append(number, name);

        const description = [point.place.name, point.place.address]
          .filter(Boolean)
          .join("\n");
        return new maplibregl.Marker({ element, anchor: "top", offset: [0, -14] })
          .setLngLat(coordinate)
          .setPopup(
            new maplibregl.Popup({ offset: 20, closeButton: false }).setText(description),
          )
          .addTo(map!);
      });

      map.on("load", () => {
        const source = map?.getSource("route") as GeoJSONSource | undefined;
        source?.setData(routeData);
        if (coordinates.length > 1) {
          const bounds = coordinates.reduce(
            (current, coordinate) => current.extend(coordinate),
            new maplibregl.LngLatBounds(coordinates[0], coordinates[0]),
          );
          map?.fitBounds(bounds as LngLatBoundsLike, {
            padding: { top: 72, right: 48, bottom: 56, left: 48 },
            maxZoom: 15,
            duration: window.matchMedia("(prefers-reduced-motion: reduce)").matches
              ? 0
              : 600,
          });
        }
        if (!disposed) {
          setMapState("ready");
        }
      });
      map.on("error", () => {
        if (!disposed) {
          setMapState("error");
        }
      });
    });

    return () => {
      disposed = true;
      resizeObserver?.disconnect();
      markers.forEach((marker) => marker.remove());
      map?.remove();
      mapRef.current = undefined;
      markerElementsRef.current.clear();
    };
  }, [places, retryKey, routes, theme]);

  useEffect(() => {
    markerElementsRef.current.forEach((element, itemId) => {
      const active = itemId === selectedItemId;
      element.setAttribute("aria-pressed", String(active));
      const marker = element.firstElementChild as HTMLElement | null;
      if (marker) {
        marker.style.boxShadow = active ? "0 0 0 4px rgba(255,255,255,.9)" : "";
      }
    });
    const selected = locatedPoints.find((point) => point.itemId === selectedItemId);
    if (selected) {
      mapRef.current?.easeTo({
        center: [selected.place.longitude, selected.place.latitude],
        duration: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : 450,
      });
    }
  }, [locatedPoints, selectedItemId]);

  return (
    <section
      className={cn(
        "map-grid relative min-h-[320px] w-full overflow-hidden rounded-md border border-border",
        className,
      )}
      aria-label={label}
    >
      <div
        ref={containerRef}
        className="absolute inset-0"
        style={{ position: "absolute" }}
      />
      {showStatus && (
        <div className="pointer-events-none absolute top-3 left-3 z-10 rounded-md border border-white/50 bg-surface/95 px-3 py-2 shadow-sm backdrop-blur">
          <p className="text-xs font-bold text-navy-strong">{label}</p>
          <p className="mt-0.5 text-[11px] text-muted">
            {mapState === "loading"
              ? "正在加载世界地图"
              : coordinates.length > 0
                ? `${coordinates.length} 个行程地点`
                : "全球地图"}
          </p>
        </div>
      )}
      {routes && !hasRouteGeometry && mapState === "ready" && (
        <div className="pointer-events-none absolute bottom-3 left-3 z-10 max-w-[70%] rounded-md border border-warning/30 bg-warning-soft px-3 py-2 text-[11px] font-semibold text-warning shadow-sm">
          路线坐标不足，已降级为地点视图
        </div>
      )}
      {mapState === "error" && (
        <div className="absolute inset-0 z-20 flex items-center justify-center bg-surface/90 p-5">
          <div className="text-center">
            <MapPin className="mx-auto size-5 text-danger" />
            <p className="mt-2 text-sm font-bold text-navy-strong">地图暂时不可用</p>
            <button
              type="button"
              className="mt-3 inline-flex h-9 items-center gap-2 rounded-md border border-border bg-surface px-3 text-xs font-bold text-navy-strong shadow-sm"
              onClick={() => setRetryKey((value) => value + 1)}
            >
              <RotateCw className="size-3.5" />
              重试
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
