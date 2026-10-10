import { fireEvent, screen, waitFor } from "@testing-library/react";
import type { GuardedAction } from "@api-generated/models/GuardedAction";
import { ConfirmationPanel } from "./confirmation-panel";
import { apiAdapter } from "@/api/client";
import { makePlan } from "@/test/factories";
import { renderWithProviders } from "@/test/render";

vi.mock("@/api/client", () => ({
  apiAdapter: {
    plans: { confirm: vi.fn() },
    actions: {
      propose: vi.fn(),
      confirm: vi.fn(),
      reject: vi.fn(),
    },
  },
}));

function action(status: GuardedAction["status"]): GuardedAction {
  return {
    actionId: crypto.randomUUID(),
    type: "ORDER_CREATE",
    resourceId: makePlan().planId,
    status,
    riskDescription: "合成费用确认",
    amount: 3760,
    currency: "CNY",
    expiresAt: new Date("2026-10-01T08:15:00Z"),
    proposedBy: "synthetic-user",
    version: status === "PENDING_CONFIRMATION" ? 0 : 1,
    auditTrail: [],
    createdAt: new Date("2026-10-01T08:00:00Z"),
    updatedAt: new Date("2026-10-01T08:00:00Z"),
  };
}

describe("ConfirmationPanel", () => {
  it("confirms a plan without implying payment", async () => {
    const plan = makePlan();
    vi.mocked(apiAdapter.plans.confirm).mockResolvedValue({
      ...plan,
      status: "CONFIRMED",
    });
    const onPlanConfirmed = vi.fn();
    renderWithProviders(
      <ConfirmationPanel plan={plan} onPlanConfirmed={onPlanConfirmed} />,
    );

    fireEvent.click(screen.getByRole("button", { name: "确认候选行程" }));

    await waitFor(() => expect(onPlanConfirmed).toHaveBeenCalledOnce());
    expect(apiAdapter.plans.confirm).toHaveBeenCalledWith(
      plan.planId,
      { expectedVersion: 1 },
      expect.stringMatching(/^plan-confirm-/),
    );
    expect(screen.getByText(/不会创建订单或发起支付/)).toBeVisible();
  });

  it("requires explicit acknowledgement before confirming a guarded action", async () => {
    const pending = action("PENDING_CONFIRMATION");
    vi.mocked(apiAdapter.actions.propose).mockResolvedValue(pending);
    vi.mocked(apiAdapter.actions.confirm).mockResolvedValue({
      ...pending,
      status: "CONFIRMED",
      version: 1,
      confirmedBy: "synthetic-user",
    });
    renderWithProviders(
      <ConfirmationPanel plan={makePlan()} onPlanConfirmed={vi.fn()} />,
    );

    fireEvent.click(screen.getByRole("button", { name: "费用操作确认" }));
    fireEvent.click(screen.getByRole("button", { name: "继续核对费用" }));
    const confirm = await screen.findByRole("button", { name: "确认授权" });
    expect(confirm).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(confirm);

    await screen.findByText("费用授权已确认");
    expect(apiAdapter.actions.confirm).toHaveBeenCalledWith(
      pending.actionId,
      expect.objectContaining({
        acknowledgedRisk: true,
        acknowledgedAmount: 3760,
      }),
      expect.stringMatching(/^action-confirm-/),
    );
  });
});
