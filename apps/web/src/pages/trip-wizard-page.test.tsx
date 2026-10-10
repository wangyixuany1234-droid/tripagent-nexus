import { fireEvent, screen, waitFor } from "@testing-library/react";
import type { TripWizardValues } from "@/stores/trip-flow-store";
import { apiAdapter } from "@/api/client";
import { renderWithProviders } from "@/test/render";
import { useTripFlowStore } from "@/stores/trip-flow-store";
import { TripWizardPage } from "./trip-wizard-page";

vi.mock("@/api/client", () => ({
  apiAdapter: {
    trips: {
      create: vi.fn(),
      saveRequirement: vi.fn(),
    },
  },
}));

function values(): TripWizardValues {
  return {
    identity: {
      userId: "018f0e31-6ec8-7a59-b871-0242ac120001",
      title: "北京三日游",
      timeZone: "Asia/Shanghai",
    },
    requirement: {
      origin: { name: "上海" },
      destinations: [{ name: "北京" }],
      startDate: "2026-10-01",
      endDate: "2026-10-03",
      travelers: [
        {
          travelerId: "018f0e31-6ec8-7a59-b871-0242ac120002",
          displayName: "旅行者 1",
          type: "ADULT",
          accessibilityNeeds: [],
        },
      ],
      totalBudget: 6000,
      currency: "CNY",
      interests: ["历史文化"],
      pace: "BALANCED",
      mustVisit: ["故宫博物院"],
      avoid: [],
      freeTextConstraints: "",
    },
    travelerCount: 1,
  };
}

function setDraft(currentStep: number, draftValues = values()) {
  useTripFlowStore.setState((state) => ({
    activeTripId: undefined,
    recentTripIds: [],
    draft: {
      ...state.draft,
      values: draftValues,
      currentStep,
      tripId: undefined,
    },
  }));
}

describe("TripWizardPage", () => {
  it("advances the Stepper only after the current step is valid", () => {
    setDraft(0);
    renderWithProviders(<TripWizardPage />);

    const routeStep = screen
      .getAllByText("路线")
      .find((element) => element.closest("li"));
    expect(routeStep?.closest("li")).toHaveAttribute(
      "aria-current",
      "step",
    );
    fireEvent.click(screen.getByRole("button", { name: "下一步" }));

    expect(screen.getByRole("heading", { name: "日期人数" })).toBeVisible();
    const dateStep = screen
      .getAllByText("日期人数")
      .find((element) => element.closest("li"));
    expect(dateStep?.closest("li")).toHaveAttribute(
      "aria-current",
      "step",
    );
  });

  it("keeps the user on the date step when the date range is inverted", () => {
    const invalid = values();
    invalid.requirement.endDate = "2026-09-30";
    setDraft(1, invalid);
    renderWithProviders(<TripWizardPage />);

    fireEvent.click(screen.getByRole("button", { name: "下一步" }));

    expect(screen.getByText("返程日期不能早于出发日期")).toBeVisible();
    expect(screen.getByRole("heading", { name: "日期人数" })).toBeVisible();
  });

  it("rejects a zero budget before advancing", () => {
    const invalid = values();
    invalid.requirement.totalBudget = 0;
    setDraft(2, invalid);
    renderWithProviders(<TripWizardPage />);

    fireEvent.click(screen.getByRole("button", { name: "下一步" }));

    expect(screen.getByText("总预算必须大于 0")).toBeVisible();
    expect(screen.getByRole("heading", { name: "预算" })).toBeVisible();
  });

  it("creates one trip when the submit button is clicked repeatedly", async () => {
    setDraft(7);
    let resolveCreate:
      | ((trip: Awaited<ReturnType<typeof apiAdapter.trips.create>>) => void)
      | undefined;
    vi.mocked(apiAdapter.trips.create).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveCreate = resolve;
        }),
    );
    vi.mocked(apiAdapter.trips.saveRequirement).mockResolvedValue({
      tripId: "018f0e31-6ec8-7a59-b871-0242ac121001",
      ...values().requirement,
      startDate: new Date("2026-10-01T00:00:00Z"),
      endDate: new Date("2026-10-03T00:00:00Z"),
      interests: new Set(["历史文化"]),
      mustVisit: new Set(["故宫博物院"]),
      avoid: new Set(),
      updatedAt: new Date("2026-09-01T08:00:00Z"),
    });
    renderWithProviders(<TripWizardPage />);
    const submit = screen.getByRole("button", { name: "创建旅行" });

    fireEvent.click(submit);
    fireEvent.click(submit);
    await waitFor(() => expect(apiAdapter.trips.create).toHaveBeenCalledOnce());

    resolveCreate?.({
      tripId: "018f0e31-6ec8-7a59-b871-0242ac121001",
      ...values().identity,
      status: "DRAFT",
      createdAt: new Date("2026-09-01T08:00:00Z"),
      updatedAt: new Date("2026-09-01T08:00:00Z"),
    });

    await waitFor(() =>
      expect(apiAdapter.trips.saveRequirement).toHaveBeenCalledOnce(),
    );
    expect(apiAdapter.trips.create).toHaveBeenCalledOnce();
  });
});
