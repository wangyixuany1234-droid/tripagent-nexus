import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import type { TravelPlan } from "@api-generated/models/TravelPlan";
import type { Trip } from "@api-generated/models/Trip";
import { MemoryRouter } from "react-router-dom";
import { makePlan } from "@/test/factories";
import { useTripFlowStore } from "@/stores/trip-flow-store";

const mocks = vi.hoisted(() => ({
  getPlan: vi.fn<(planId: string, signal?: AbortSignal) => Promise<TravelPlan>>(),
  listPlans: vi.fn(),
  getTrip: vi.fn<(tripId: string, signal?: AbortSignal) => Promise<Trip>>(),
  navigate: vi.fn(),
  planId: "",
}));

vi.mock("@/api/client", () => ({
  apiAdapter: {
    plans: {
      get: mocks.getPlan,
      list: mocks.listPlans,
      compare: vi.fn(),
    },
    trips: {
      get: mocks.getTrip,
    },
  },
}));

vi.mock("@/components/travel/map-container", () => ({
  MapContainer: () => <div aria-label="路线地图测试替身" />,
}));

vi.mock("@/components/results/budget-analysis", () => ({
  BudgetAnalysis: () => <div aria-label="预算分析测试替身" />,
}));

vi.mock("react-router-dom", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router-dom")>();
  return {
    ...actual,
    useNavigate: () => mocks.navigate,
    useParams: () => ({ planId: mocks.planId }),
  };
});

import { PlanResultsPage } from "./plan-results-page";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

function providers(queryClient: QueryClient, children: ReactNode) {
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

describe("PlanResultsPage", () => {
  it("keeps the latest plan visible when an older route response completes late", async () => {
    const tripId = "018f0e31-6ec8-7a59-b871-0242ac121001";
    const oldPlan = makePlan({
      planId: "018f0e31-6ec8-7a59-b871-0242ac121011",
      tripId,
      title: "旧方案",
      version: 1,
    });
    const latestPlan = makePlan({
      planId: "018f0e31-6ec8-7a59-b871-0242ac121012",
      tripId,
      title: "最新方案",
      version: 2,
    });
    const oldResponse = deferred<TravelPlan>();
    const latestResponse = deferred<TravelPlan>();
    mocks.getPlan.mockImplementation((planId) =>
      planId === oldPlan.planId ? oldResponse.promise : latestResponse.promise,
    );
    mocks.listPlans.mockResolvedValue({ items: [oldPlan, latestPlan] });
    mocks.getTrip.mockResolvedValue({
      tripId,
      userId: "018f0e31-6ec8-7a59-b871-0242ac121013",
      title: "测试旅行",
      timeZone: "Asia/Shanghai",
      status: "READY",
      createdAt: new Date("2026-09-16T08:00:00Z"),
      updatedAt: new Date("2026-09-16T08:00:00Z"),
    });
    useTripFlowStore.setState({
      activeTripId: tripId,
      activePlanId: oldPlan.planId,
      activePlanByTrip: { [tripId]: oldPlan.planId },
    });
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    mocks.planId = oldPlan.planId;
    const { rerender } = render(providers(queryClient, <PlanResultsPage />));

    mocks.planId = latestPlan.planId;
    rerender(providers(queryClient, <PlanResultsPage />));
    await waitFor(() =>
      expect(mocks.getPlan).toHaveBeenCalledWith(
        latestPlan.planId,
        expect.any(AbortSignal),
      ),
    );
    act(() => {
      latestResponse.resolve(latestPlan);
    });
    expect(
      await screen.findByRole("heading", { level: 1, name: "最新方案" }),
    ).toBeVisible();

    await act(async () => {
      oldResponse.resolve(oldPlan);
      await oldResponse.promise;
    });
    expect(screen.getByRole("heading", { level: 1, name: "最新方案" })).toBeVisible();
    expect(
      screen.queryByRole("heading", { level: 1, name: "旧方案" }),
    ).not.toBeInTheDocument();
  });
});
