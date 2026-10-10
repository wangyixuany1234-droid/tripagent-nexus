import { useEffect, useMemo, useState } from "react";
import type { TravelPlan } from "@api-generated/models/TravelPlan";
import { ArrowRightLeft } from "lucide-react";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetTitle,
} from "@/components/ui/sheet";
import { cn, formatCurrency } from "@/lib/utils";
import {
  planMetrics,
  strategyFitDescription,
  strategyLabel,
} from "@/lib/plan-view";

interface MetricRow {
  label: string;
  left: string;
  right: string;
}

export function PlanComparisonSheet({
  open,
  onOpenChange,
  plans,
  currentPlan,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  plans: TravelPlan[];
  currentPlan: TravelPlan;
}) {
  const fallback = plans.find((plan) => plan.planId !== currentPlan.planId) ?? currentPlan;
  const [leftId, setLeftId] = useState(currentPlan.planId);
  const [rightId, setRightId] = useState(fallback.planId);

  useEffect(() => {
    setLeftId(currentPlan.planId);
    setRightId(
      plans.find((plan) => plan.planId !== currentPlan.planId)?.planId ??
        currentPlan.planId,
    );
  }, [currentPlan.planId, plans]);

  const left = plans.find((plan) => plan.planId === leftId) ?? currentPlan;
  const right = plans.find((plan) => plan.planId === rightId) ?? fallback;
  const leftMetrics = useMemo(() => planMetrics(left), [left]);
  const rightMetrics = useMemo(() => planMetrics(right), [right]);
  const rows: MetricRow[] = [
    {
      label: "预计总额",
      left: formatCurrency(left.budgetSummary.estimatedTotal, left.budgetSummary.currency),
      right: formatCurrency(
        right.budgetSummary.estimatedTotal,
        right.budgetSummary.currency,
      ),
    },
    {
      label: "每日强度",
      left: leftMetrics.dailyIntensity,
      right: rightMetrics.dailyIntensity,
    },
    {
      label: "交通耗时",
      left: `${leftMetrics.commuteMinutes} 分钟`,
      right: `${rightMetrics.commuteMinutes} 分钟`,
    },
    {
      label: "景点数量",
      left: `${leftMetrics.attractionCount} 个`,
      right: `${rightMetrics.attractionCount} 个`,
    },
    {
      label: "证据覆盖",
      left: `${leftMetrics.evidenceCoverage}%`,
      right: `${rightMetrics.evidenceCoverage}%`,
    },
  ];

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="overflow-y-auto p-5 sm:p-7">
        <div className="pr-10">
          <p className="text-xs font-bold text-coral">方案比较</p>
          <SheetTitle className="mt-1 flex items-center gap-2 text-xl font-bold text-navy-strong">
            <ArrowRightLeft className="size-5 text-sky" />
            方案并排比较
          </SheetTitle>
          <SheetDescription className="mt-2 text-sm leading-6 text-muted">
            比较数据来自各自完整方案。系统说明适配偏好，不宣称某一方案绝对更优。
          </SheetDescription>
        </div>

        <div className="mt-6 grid grid-cols-[88px_minmax(0,1fr)_minmax(0,1fr)] border-y border-border">
          <div className="border-r border-border p-3 text-xs font-semibold text-muted">
            方案
          </div>
          <label className="border-r border-border p-3">
            <span className="sr-only">左侧方案</span>
            <select
              value={left.planId}
              onChange={(event) => setLeftId(event.target.value)}
              className="h-9 w-full rounded-md border border-border bg-surface px-2 text-xs font-bold"
            >
              {plans.map((plan) => (
                <option key={plan.planId} value={plan.planId}>
                  {strategyLabel(plan.strategy)} · v{plan.version}
                </option>
              ))}
            </select>
          </label>
          <label className="p-3">
            <span className="sr-only">右侧方案</span>
            <select
              value={right.planId}
              onChange={(event) => setRightId(event.target.value)}
              className="h-9 w-full rounded-md border border-border bg-surface px-2 text-xs font-bold"
            >
              {plans.map((plan) => (
                <option key={plan.planId} value={plan.planId}>
                  {strategyLabel(plan.strategy)} · v{plan.version}
                </option>
              ))}
            </select>
          </label>

          {rows.map((row) => {
            const different = row.left !== row.right;
            return (
              <div key={row.label} className="contents">
                <div className="border-t border-r border-border p-3 text-xs text-muted">
                  {row.label}
                </div>
                <div
                  className={cn(
                    "border-t border-r border-border p-3 text-sm font-semibold",
                    different && "bg-sky-soft/60",
                  )}
                >
                  {row.left}
                </div>
                <div
                  className={cn(
                    "border-t border-border p-3 text-sm font-semibold",
                    different && "bg-sky-soft/60",
                  )}
                >
                  {row.right}
                </div>
              </div>
            );
          })}
        </div>

        <div className="mt-6 grid gap-5 sm:grid-cols-2">
          {[left, right].map((plan) => (
            <section key={plan.planId} className="border-l-2 border-sky pl-4">
              <p className="text-sm font-bold text-navy-strong">
                {strategyLabel(plan.strategy)}
              </p>
              <p className="mt-2 text-sm leading-6 text-muted">
                {strategyFitDescription(plan.strategy)}
              </p>
              <p className="mt-2 text-xs leading-5 text-muted">{plan.summary}</p>
            </section>
          ))}
        </div>
      </SheetContent>
    </Sheet>
  );
}
