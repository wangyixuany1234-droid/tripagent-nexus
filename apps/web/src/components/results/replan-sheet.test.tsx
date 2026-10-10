import { fireEvent, screen, waitFor } from "@testing-library/react";
import { ReplanSheet } from "./replan-sheet";
import { apiAdapter } from "@/api/client";
import { makePlan, testIds } from "@/test/factories";
import { renderWithProviders } from "@/test/render";
import { useTripFlowStore } from "@/stores/trip-flow-store";

vi.mock("@/api/client", () => ({
  apiAdapter: {
    plans: {
      replan: vi.fn(),
    },
  },
}));

describe("ReplanSheet", () => {
  it("validates the budget before sending a replan request", async () => {
    renderWithProviders(
      <ReplanSheet
        open
        onOpenChange={vi.fn()}
        plan={makePlan()}
        lockedItemIds={new Set()}
        removedItemIds={new Set()}
      />,
    );

    fireEvent.change(screen.getByLabelText("调整总预算"), {
      target: { value: "0" },
    });
    fireEvent.click(screen.getByRole("button", { name: "生成新版本" }));

    expect(await screen.findByText("预算必须大于 0")).toBeVisible();
    expect(apiAdapter.plans.replan).not.toHaveBeenCalled();
  });

  it("preserves locked items and records the successor task", async () => {
    const accepted = {
      taskId: crypto.randomUUID(),
      resourceId: crypto.randomUUID(),
      status: "QUEUED" as const,
      traceId: "11111111111111111111111111111111",
      createdAt: new Date("2026-09-01T08:00:00Z"),
    };
    vi.mocked(apiAdapter.plans.replan).mockResolvedValue(accepted);
    useTripFlowStore.setState({ planningTasks: [] });
    const onOpenChange = vi.fn();
    const plan = makePlan();
    renderWithProviders(
      <ReplanSheet
        open
        onOpenChange={onOpenChange}
        plan={plan}
        lockedItemIds={new Set([testIds.item])}
        removedItemIds={new Set()}
        suggestedRequest="保留故宫并减少通勤"
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "生成新版本" }));

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
    expect(apiAdapter.plans.replan).toHaveBeenCalledWith(
      plan.planId,
      expect.objectContaining({
        naturalLanguageRequest: "保留故宫并减少通勤",
        lockedItemIds: new Set([testIds.item]),
      }),
      expect.stringMatching(/^plan-replan-/),
    );
    expect(useTripFlowStore.getState().planningTasks[0]?.taskId).toBe(
      accepted.taskId,
    );
  });
});
