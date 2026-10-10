import { render, screen } from "@testing-library/react";
import type { BudgetSummary } from "@api-generated/models/BudgetSummary";
import { BudgetAnalysis } from "./budget-analysis";

const chart = {
  setOption: vi.fn(),
  resize: vi.fn(),
  dispose: vi.fn(),
};

vi.mock("echarts/core", () => ({
  init: () => chart,
  use: vi.fn(),
}));
vi.mock("echarts/charts", () => ({ BarChart: {} }));
vi.mock("echarts/components", () => ({ GridComponent: {}, TooltipComponent: {} }));
vi.mock("echarts/renderers", () => ({ CanvasRenderer: {} }));

function budget(overrides: Partial<BudgetSummary> = {}): BudgetSummary {
  return {
    totalBudget: 5000,
    estimatedTotal: 3760,
    remainingBudget: 1240,
    currency: "CNY",
    items: [
      {
        category: "ACCOMMODATION",
        description: "住宿",
        amount: 3200,
        currency: "CNY",
      },
      {
        category: "TRANSPORT",
        description: "交通",
        amount: 560,
        currency: "CNY",
      },
    ],
    dailyBudgets: [],
    ...overrides,
  };
}

describe("BudgetAnalysis", () => {
  it("renders category totals and initializes the chart", () => {
    render(<BudgetAnalysis budget={budget()} />);

    expect(screen.getByRole("img", { name: "预算分类柱状图" })).toBeVisible();
    expect(screen.getByText("75%")).toBeVisible();
    expect(screen.getByText("¥3,200")).toBeVisible();
    expect(chart.setOption).toHaveBeenCalledOnce();
  });

  it("explains an over-budget state with the largest category", () => {
    render(
      <BudgetAnalysis
        budget={budget({
          estimatedTotal: 5400,
          remainingBudget: -400,
        })}
      />,
    );

    expect(screen.getByText("108%")).toBeVisible();
    expect(screen.getByText(/超出预算/)).toBeVisible();
    expect(screen.getByText(/住宿支出/)).toBeVisible();
  });

  it("disposes chart instances and observers across updates and unmount", () => {
    const disconnect = vi.spyOn(ResizeObserver.prototype, "disconnect");
    const firstBudget = budget();
    const { rerender, unmount } = render(
      <BudgetAnalysis budget={firstBudget} />,
    );

    rerender(
      <BudgetAnalysis
        budget={{
          ...firstBudget,
          items: [
            ...firstBudget.items,
            {
              category: "DINING",
              description: "餐饮",
              amount: 200,
              currency: "CNY",
            },
          ],
        }}
      />,
    );
    expect(chart.dispose).toHaveBeenCalledTimes(1);
    expect(disconnect).toHaveBeenCalledTimes(1);

    unmount();
    expect(chart.dispose).toHaveBeenCalledTimes(2);
    expect(disconnect).toHaveBeenCalledTimes(2);
    disconnect.mockRestore();
  });
});
