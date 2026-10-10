import { fireEvent, render, screen, within } from "@testing-library/react";
import { ConflictCenter } from "./conflict-center";
import { EvidenceDrawer } from "./evidence-drawer";
import { PlanComparisonSheet } from "./plan-comparison-sheet";
import { ResultsTimeline } from "./results-timeline";
import { makePlan } from "@/test/factories";

describe("result components", () => {
  it("opens a conflict in the replanning workflow", () => {
    const plan = makePlan();
    const onReplan = vi.fn();
    render(
      <ConflictCenter
        conflicts={plan.conflicts}
        history={[]}
        onReplan={onReplan}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "带入重规划" }));
    expect(onReplan).toHaveBeenCalledWith(plan.conflicts[0]);
    expect(screen.getByText("1 个待处理")).toBeVisible();
  });

  it("renders the evidence drawer without inventing missing sources", () => {
    render(
      <EvidenceDrawer
        open
        onOpenChange={vi.fn()}
        evidence={[]}
      />,
    );

    expect(screen.getByRole("dialog")).toHaveTextContent("当前方案证据不足");
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("rejects unsafe evidence links while preserving source text", () => {
    const evidence = makePlan().evidence.map((item) => ({
      ...item,
      url: "javascript:alert(1)",
    }));
    render(
      <EvidenceDrawer
        open
        onOpenChange={vi.fn()}
        evidence={evidence}
      />,
    );

    expect(screen.getByText("故宫开放与参观信息")).toBeVisible();
    expect(screen.queryByRole("link", { name: "查看原始来源" })).not.toBeInTheDocument();
  });

  it("compares budget and commute metrics from two plans", () => {
    const balanced = makePlan();
    const lowTransit = makePlan({
      planId: crypto.randomUUID(),
      planFamilyId: crypto.randomUUID(),
      alternativeKey: "low-transit",
      strategy: "LOW_TRANSIT",
      budgetSummary: {
        ...balanced.budgetSummary,
        estimatedTotal: 3500,
        remainingBudget: 1500,
      },
      days: [
        {
          ...balanced.days[0],
          items: balanced.days[0].items.filter((item) => item.type !== "TRANSPORT"),
        },
      ],
    });
    render(
      <PlanComparisonSheet
        open
        onOpenChange={vi.fn()}
        plans={[balanced, lowTransit]}
        currentPlan={balanced}
      />,
    );

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("预计总额")).toBeVisible();
    expect(within(dialog).getByText("交通耗时")).toBeVisible();
    expect(within(dialog).getByText("90 分钟")).toBeVisible();
    expect(within(dialog).getByText("0 分钟")).toBeVisible();
  });

  it("links timeline selection, locking, and evidence actions", () => {
    const plan = makePlan();
    const onSelectItem = vi.fn();
    const onToggleLock = vi.fn();
    const onOpenEvidence = vi.fn();
    render(
      <ResultsTimeline
        days={plan.days}
        activeDay={1}
        onDayChange={vi.fn()}
        selectedItemId={undefined}
        onSelectItem={onSelectItem}
        lockedItemIds={new Set()}
        removedItemIds={new Set()}
        onToggleLock={onToggleLock}
        onToggleRemove={vi.fn()}
        evidenceById={new Map(plan.evidence.map((item) => [item.evidenceId, item]))}
        onOpenEvidence={onOpenEvidence}
      />,
    );

    fireEvent.click(
      screen.getByRole("button", { name: "故宫博物院 故宫博物院" }),
    );
    expect(onSelectItem).toHaveBeenCalledWith(plan.days[0].items[1].itemId);
    fireEvent.click(screen.getAllByRole("button", { name: "锁定" })[1]);
    expect(onToggleLock).toHaveBeenCalledWith(plan.days[0].items[1].itemId);
    fireEvent.click(screen.getAllByRole("button", { name: "原因与来源" })[1]);
    fireEvent.click(screen.getByRole("button", { name: "公共知识" }));
    expect(onOpenEvidence).toHaveBeenCalledWith(plan.evidence[0].evidenceId);
  });
});
