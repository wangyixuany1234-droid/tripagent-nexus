import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { MapContainer, type MapRouteGroup } from "./map-container";

const mapMocks = vi.hoisted(() => ({
  callbacks: new Map<string, () => void>(),
  container: undefined as HTMLElement | undefined,
  constructor: vi.fn(),
  easeTo: vi.fn(),
  fitBounds: vi.fn(),
  markerRemove: vi.fn(),
  remove: vi.fn(),
}));

vi.mock("maplibre-gl", () => {
  class MapMock {
    constructor(options: { container: HTMLElement }) {
      mapMocks.container = options.container;
      mapMocks.constructor(options);
    }

    addControl() {}

    getSource() {
      return { setData: vi.fn() };
    }

    on(name: string, callback: () => void) {
      mapMocks.callbacks.set(name, callback);
      if (name === "load") {
        queueMicrotask(callback);
      }
      return this;
    }

    fitBounds = mapMocks.fitBounds;
    easeTo = mapMocks.easeTo;
    resize = vi.fn();
    remove = mapMocks.remove;
  }

  class MarkerMock {
    private readonly element: HTMLElement;

    constructor(options: { element: HTMLElement }) {
      this.element = options.element;
    }

    setLngLat() {
      return this;
    }

    setPopup() {
      return this;
    }

    addTo() {
      mapMocks.container?.append(this.element);
      return this;
    }

    remove() {
      mapMocks.markerRemove();
      this.element.remove();
    }
  }

  class PopupMock {
    setText() {
      return this;
    }
  }

  class LngLatBoundsMock {
    extend() {
      return this;
    }
  }

  const moduleExports = {
    addProtocol: vi.fn(),
    Map: MapMock,
    Marker: MarkerMock,
    Popup: PopupMock,
    LngLatBounds: LngLatBoundsMock,
    NavigationControl: class {},
    AttributionControl: class {},
  };

  return {
    // maplibre-gl v6 exposes these as top-level ESM named exports, which is
    // what the dynamic namespace import in map-container.tsx resolves to.
    ...moduleExports,
    // Retain the default export for CJS/default-import interop.
    default: moduleExports,
  };
});

const routes: MapRouteGroup[] = [
  {
    dayNumber: 1,
    color: "#247fae",
    points: [
      {
        itemId: "item-station",
        place: {
          name: "北京站",
          latitude: 39.9028,
          longitude: 116.427,
        },
      },
      {
        itemId: "item-palace",
        place: {
          name: "故宫博物院",
          latitude: 39.9163,
          longitude: 116.3972,
        },
      },
    ],
  },
];

describe("MapContainer", () => {
  beforeEach(() => {
    mapMocks.callbacks.clear();
    mapMocks.container = undefined;
  });

  it("links map markers to itinerary selection", async () => {
    const onSelectItem = vi.fn();
    const { rerender } = render(
      <MapContainer routes={routes} onSelectItem={onSelectItem} />,
    );

    expect(await screen.findByText("2 个行程地点")).toBeVisible();
    fireEvent.click(
      screen.getByRole("button", { name: "查看地点：故宫博物院" }),
    );
    expect(onSelectItem).toHaveBeenCalledWith("item-palace");

    rerender(
      <MapContainer
        routes={routes}
        selectedItemId="item-palace"
        onSelectItem={onSelectItem}
      />,
    );
    expect(mapMocks.easeTo).toHaveBeenCalledWith(
      expect.objectContaining({ center: [116.3972, 39.9163] }),
    );
  });

  it("offers recovery when the map provider fails", async () => {
    render(<MapContainer routes={routes} />);
    expect(await screen.findByText("2 个行程地点")).toBeVisible();

    act(() => mapMocks.callbacks.get("error")?.());
    expect(screen.getByText("地图暂时不可用")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(mapMocks.constructor).toHaveBeenCalledTimes(2));
  });

  it("disposes every map and marker across rerenders and unmount", async () => {
    const disconnect = vi.spyOn(ResizeObserver.prototype, "disconnect");
    const { rerender, unmount } = render(<MapContainer routes={routes} />);
    expect(await screen.findByText("2 个行程地点")).toBeVisible();

    rerender(<MapContainer routes={[...routes]} />);
    await waitFor(() => expect(mapMocks.constructor).toHaveBeenCalledTimes(2));
    expect(mapMocks.remove).toHaveBeenCalledTimes(1);
    expect(mapMocks.markerRemove).toHaveBeenCalledTimes(2);
    expect(disconnect).toHaveBeenCalledTimes(1);

    unmount();
    expect(mapMocks.remove).toHaveBeenCalledTimes(2);
    expect(mapMocks.markerRemove).toHaveBeenCalledTimes(4);
    expect(disconnect).toHaveBeenCalledTimes(2);
    disconnect.mockRestore();
  });
});
