import { render, screen } from "@testing-library/react";
import { makePlan } from "@/test/factories";

const chartMocks = vi.hoisted(() => ({
  chart: {
    setOption: vi.fn(),
    resize: vi.fn(),
    dispose: vi.fn(),
  },
  init: vi.fn(),
}));

chartMocks.init.mockImplementation(() => chartMocks.chart);

vi.mock("echarts/core", () => ({
  init: chartMocks.init,
  use: vi.fn(),
}));
vi.mock("echarts/charts", () => ({ PieChart: {} }));
vi.mock("echarts/components", () => ({ TooltipComponent: {} }));
vi.mock("echarts/renderers", () => ({ CanvasRenderer: {} }));

import { BudgetCard } from "./budget-card";

describe("BudgetCard", () => {
  it("disposes chart instances and observers across updates and unmount", () => {
    const disconnect = vi.spyOn(ResizeObserver.prototype, "disconnect");
    const firstBudget = makePlan().budgetSummary;
    const { rerender, unmount } = render(<BudgetCard budget={firstBudget} />);

    expect(screen.getByText("75%")).toBeVisible();
    expect(chartMocks.init).toHaveBeenCalledTimes(1);

    rerender(
      <BudgetCard
        budget={{
          ...firstBudget,
          estimatedTotal: 4000,
          remainingBudget: 1000,
        }}
      />,
    );
    expect(screen.getByText("80%")).toBeVisible();
    expect(chartMocks.init).toHaveBeenCalledTimes(2);
    expect(chartMocks.chart.dispose).toHaveBeenCalledTimes(1);
    expect(disconnect).toHaveBeenCalledTimes(1);

    unmount();
    expect(chartMocks.chart.dispose).toHaveBeenCalledTimes(2);
    expect(disconnect).toHaveBeenCalledTimes(2);
    disconnect.mockRestore();
  });
});
