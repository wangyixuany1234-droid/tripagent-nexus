import { useEffect, useRef } from "react";
import { PieChart } from "echarts/charts";
import { TooltipComponent } from "echarts/components";
import * as echarts from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import type { BudgetSummary } from "@api-generated/models/BudgetSummary";
import { WalletCards } from "lucide-react";
import { formatCurrency } from "@/lib/utils";
import { useAppStore } from "@/stores/app-store";

echarts.use([PieChart, TooltipComponent, CanvasRenderer]);

const categoryLabels: Record<string, string> = {
  TRANSPORT: "交通",
  ACCOMMODATION: "住宿",
  ATTRACTION: "景点",
  DINING: "餐饮",
  FEES: "费用",
  OTHER: "其他",
};

function BudgetChart({ budget }: { budget: BudgetSummary }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const theme = useAppStore((state) => state.theme);

  useEffect(() => {
    if (!containerRef.current) {
      return;
    }
    const chart = echarts.init(containerRef.current, undefined, { renderer: "canvas" });
    const style = getComputedStyle(document.documentElement);
    chart.setOption({
      animationDuration: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : 500,
      tooltip: { show: false },
      series: [
        {
          type: "pie",
          radius: ["72%", "93%"],
          silent: true,
          label: { show: false },
          data: [
            {
              value: Math.max(budget.estimatedTotal, 0),
              itemStyle: { color: style.getPropertyValue("--sky").trim() },
            },
            {
              value: Math.max(budget.remainingBudget, 0),
              itemStyle: { color: style.getPropertyValue("--surface-subtle").trim() },
            },
          ],
        },
      ],
    });

    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(containerRef.current);
    return () => {
      observer.disconnect();
      chart.dispose();
    };
  }, [budget, theme]);

  return <div ref={containerRef} className="size-24 shrink-0" aria-hidden="true" />;
}

export function BudgetCard({ budget }: { budget: BudgetSummary }) {
  const utilization =
    budget.totalBudget > 0
      ? Math.min(100, Math.round((budget.estimatedTotal / budget.totalBudget) * 100))
      : 0;
  const categories = budget.items.slice(0, 3);

  return (
    <section className="rounded-md border border-border bg-surface p-4 shadow-[var(--shadow)]">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="text-xs font-semibold text-muted">预算使用</p>
          <p className="mt-1 text-xl font-bold text-navy-strong">
            {formatCurrency(budget.estimatedTotal, budget.currency)}
          </p>
        </div>
        <span className="flex size-9 items-center justify-center rounded-md bg-coral-soft text-coral">
          <WalletCards className="size-4.5" />
        </span>
      </div>

      <div className="mt-4 flex items-center gap-4">
        <div className="relative">
          <BudgetChart budget={budget} />
          <span className="absolute inset-0 flex items-center justify-center text-sm font-bold">
            {utilization}%
          </span>
        </div>
        <dl className="min-w-0 flex-1 space-y-2 text-sm">
          <div className="flex items-center justify-between gap-3">
            <dt className="text-muted">总预算</dt>
            <dd className="font-semibold">
              {formatCurrency(budget.totalBudget, budget.currency)}
            </dd>
          </div>
          <div className="flex items-center justify-between gap-3">
            <dt className="text-muted">可用余额</dt>
            <dd className={budget.remainingBudget < 0 ? "font-bold text-danger" : "font-bold text-success"}>
              {formatCurrency(budget.remainingBudget, budget.currency)}
            </dd>
          </div>
        </dl>
      </div>

      {categories.length > 0 && (
        <div className="mt-4 border-t border-border pt-3">
          {categories.map((item, index) => (
            <div
              key={`${item.category}-${item.description}-${index}`}
              className="flex items-center justify-between gap-3 py-1 text-xs"
            >
              <span className="truncate text-muted">
                {categoryLabels[item.category] ?? item.category}
              </span>
              <span className="font-semibold">
                {formatCurrency(item.amount, item.currency)}
              </span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
