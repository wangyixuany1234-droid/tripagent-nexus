import { useEffect, useMemo, useRef } from "react";
import { BarChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import * as echarts from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import type { BudgetSummary } from "@api-generated/models/BudgetSummary";
import { CircleAlert, WalletCards } from "lucide-react";
import { formatCurrency } from "@/lib/utils";
import { useAppStore } from "@/stores/app-store";

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);

const categories = [
  ["TRANSPORT", "交通"],
  ["ACCOMMODATION", "住宿"],
  ["DINING", "餐饮"],
  ["ATTRACTION", "门票"],
  ["FEES", "费用"],
  ["OTHER", "其他"],
] as const;

export function BudgetAnalysis({ budget }: { budget: BudgetSummary }) {
  const chartRef = useRef<HTMLDivElement>(null);
  const theme = useAppStore((state) => state.theme);
  const categoryTotals = useMemo(
    () =>
      categories.map(([category, label]) => ({
        category,
        label,
        amount: budget.items
          .filter((item) => item.category === category)
          .reduce((total, item) => total + item.amount, 0),
      })),
    [budget.items],
  );
  const utilization =
    budget.totalBudget > 0
      ? Math.round((budget.estimatedTotal / budget.totalBudget) * 100)
      : 0;

  useEffect(() => {
    if (!chartRef.current) {
      return;
    }
    const chart = echarts.init(chartRef.current, undefined, { renderer: "canvas" });
    const style = getComputedStyle(document.documentElement);
    chart.setOption({
      animationDuration: window.matchMedia("(prefers-reduced-motion: reduce)").matches
        ? 0
        : 450,
      grid: { top: 8, right: 8, bottom: 24, left: 50 },
      tooltip: {
        trigger: "axis",
        valueFormatter: (value: number) => formatCurrency(value, budget.currency),
      },
      xAxis: {
        type: "category",
        data: categoryTotals.map((item) => item.label),
        axisTick: { show: false },
        axisLine: { lineStyle: { color: style.getPropertyValue("--border").trim() } },
        axisLabel: { color: style.getPropertyValue("--muted").trim(), fontSize: 10 },
      },
      yAxis: {
        type: "value",
        splitLine: {
          lineStyle: { color: style.getPropertyValue("--border").trim(), opacity: 0.65 },
        },
        axisLabel: {
          color: style.getPropertyValue("--muted").trim(),
          fontSize: 10,
        },
      },
      series: [
        {
          type: "bar",
          data: categoryTotals.map((item) => item.amount),
          barMaxWidth: 28,
          itemStyle: {
            color: style.getPropertyValue("--sky").trim(),
            borderRadius: [3, 3, 0, 0],
          },
        },
      ],
    });
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(chartRef.current);
    return () => {
      observer.disconnect();
      chart.dispose();
    };
  }, [budget.currency, categoryTotals, theme]);

  const largest = [...categoryTotals].sort((left, right) => right.amount - left.amount)[0];

  return (
    <section aria-labelledby="budget-analysis-title">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="text-xs font-semibold text-muted">预计总额</p>
          <h2
            id="budget-analysis-title"
            className="mt-1 text-xl font-bold text-navy-strong"
          >
            {formatCurrency(budget.estimatedTotal, budget.currency)}
          </h2>
        </div>
        <span className="flex size-9 items-center justify-center rounded-md bg-coral-soft text-coral">
          <WalletCards className="size-4" />
        </span>
      </div>

      <div className="mt-4">
        <div className="flex items-center justify-between text-xs">
          <span className="text-muted">
            总预算 {formatCurrency(budget.totalBudget, budget.currency)}
          </span>
          <span className={utilization > 100 ? "font-bold text-danger" : "font-bold"}>
            {utilization}%
          </span>
        </div>
        <div className="mt-2 h-2 overflow-hidden rounded-full bg-surface-subtle">
          <div
            className={utilization > 100 ? "h-full bg-danger" : "h-full bg-sky"}
            style={{ width: `${Math.min(utilization, 100)}%` }}
          />
        </div>
      </div>

      <div
        ref={chartRef}
        className="mt-4 h-44 w-full"
        role="img"
        aria-label="预算分类柱状图"
      />

      <dl className="mt-3 border-t border-border pt-3 text-xs">
        {categoryTotals.map((item) => (
          <div key={item.category} className="flex justify-between gap-3 py-1.5">
            <dt className="text-muted">{item.label}</dt>
            <dd className="font-semibold">
              {formatCurrency(item.amount, budget.currency)}
            </dd>
          </div>
        ))}
      </dl>

      {budget.remainingBudget < 0 && (
        <div className="mt-4 border-l-2 border-danger bg-danger-soft/50 px-3 py-2">
          <p className="flex items-center gap-2 text-xs font-bold text-danger">
            <CircleAlert className="size-3.5" />
            超出预算 {formatCurrency(Math.abs(budget.remainingBudget), budget.currency)}
          </p>
          <p className="mt-1 text-xs leading-5 text-muted">
            当前占比最高的是{largest.label}支出，可在重规划中调整预算或相关安排。
          </p>
        </div>
      )}
    </section>
  );
}
