import { useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { Conflict } from "@api-generated/models/Conflict";
import type { TravelPlan } from "@api-generated/models/TravelPlan";
import {
  AlertTriangle,
  ArrowRightLeft,
  BookOpen,
  CalendarDays,
  ChevronRight,
  GitCompareArrows,
  Map as MapIcon,
  RefreshCcw,
  Route,
  SlidersHorizontal,
  WalletCards,
} from "lucide-react";
import { useNavigate, useParams } from "react-router-dom";
import { apiAdapter } from "@/api/client";
import { PageHeader } from "@/components/app/page-header";
import { BudgetAnalysis } from "@/components/results/budget-analysis";
import {
  ConflictCenter,
  type HistoricalConflict,
} from "@/components/results/conflict-center";
import { ConfirmationPanel } from "@/components/results/confirmation-panel";
import { EvidenceDrawer } from "@/components/results/evidence-drawer";
import { PlanComparisonSheet } from "@/components/results/plan-comparison-sheet";
import { ReplanSheet } from "@/components/results/replan-sheet";
import { ResultsTimeline } from "@/components/results/results-timeline";
import { EmptyState, ErrorState } from "@/components/states/state-panel";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  MapContainer,
  type MapRouteGroup,
} from "@/components/travel/map-container";
import { StatusBadge } from "@/components/travel/status-badge";
import { cn, formatCurrency } from "@/lib/utils";
import {
  familyVersions,
  latestAlternatives,
  planMetrics,
  strategyFitDescription,
  strategyLabel,
} from "@/lib/plan-view";
import { useTripFlowStore } from "@/stores/trip-flow-store";

type MobilePanel = "map" | "timeline" | "budget" | "conflicts";

const dayColors = ["#247fae", "#d44b3e", "#2f8a64", "#b27614", "#7759a8"];
const paceLabels: Record<string, string> = {
  RELAXED: "舒缓",
  BALANCED: "均衡",
  INTENSIVE: "紧凑",
};

const mobilePanels: Array<{
  id: MobilePanel;
  label: string;
  icon: typeof MapIcon;
}> = [
  { id: "map", label: "地图", icon: MapIcon },
  { id: "timeline", label: "时间线", icon: Route },
  { id: "budget", label: "预算", icon: WalletCards },
  { id: "conflicts", label: "冲突", icon: AlertTriangle },
];

function dateRange(plan: TravelPlan) {
  const first = plan.days[0]?.date;
  const last = plan.days.at(-1)?.date;
  if (!first || !last) {
    return "日期待确定";
  }
  const formatter = new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric",
  });
  return `${formatter.format(first)} - ${formatter.format(last)}`;
}

function PlanSwitcher({
  plans,
  currentPlan,
  onSelect,
  compact = false,
}: {
  plans: TravelPlan[];
  currentPlan: TravelPlan;
  onSelect: (plan: TravelPlan) => void;
  compact?: boolean;
}) {
  return (
    <div
      className={cn(
        compact
          ? "flex gap-1.5 overflow-x-auto"
          : "divide-y divide-border border-y border-border",
      )}
    >
      {plans.map((plan) => {
        const metrics = planMetrics(plan);
        const active = plan.planFamilyId === currentPlan.planFamilyId;
        return (
          <button
            key={plan.planId}
            type="button"
            aria-pressed={active}
            onClick={() => onSelect(plan)}
            className={cn(
              "w-full border-l-2 border-transparent px-3 py-3 text-left transition-colors hover:bg-surface-subtle",
              compact &&
                "w-[122px] shrink-0 rounded-md border border-border px-2 py-2",
              active && "border-sky bg-sky-soft/55",
            )}
          >
            <span className="flex items-center justify-between gap-2">
              <span className="text-sm font-bold">{strategyLabel(plan.strategy)}</span>
              <span className="text-[10px] font-semibold text-muted">v{plan.version}</span>
            </span>
            <span className="mt-1 block text-[11px] leading-5 text-muted">
              {formatCurrency(
                plan.budgetSummary.estimatedTotal,
                plan.budgetSummary.currency,
              )}
              {" · "}
              {metrics.dailyIntensity}
              {!compact && ` · ${metrics.attractionCount} 个景点`}
            </span>
          </button>
        );
      })}
    </div>
  );
}

function ConstraintsPanel({
  trip,
  compact = false,
}: {
  trip: Awaited<ReturnType<typeof apiAdapter.trips.get>>;
  compact?: boolean;
}) {
  const requirement = trip.requirement;
  const content = (
    <>
      {!compact && (
      <h2 id="constraints-title" className="flex items-center gap-2 text-sm font-bold">
        <SlidersHorizontal className="size-4 text-sky" />
        旅行约束
      </h2>
      )}
      {!requirement ? (
        <p className="mt-3 text-xs text-warning">旅行约束尚未补充完整。</p>
      ) : (
        <dl className="mt-3 divide-y divide-border border-y border-border text-xs">
          <div className="py-2.5">
            <dt className="text-muted">路线</dt>
            <dd className="mt-1 font-semibold">
              {requirement.origin.name} →{" "}
              {requirement.destinations.map((place) => place.name).join(" · ")}
            </dd>
          </div>
          <div className="flex justify-between gap-3 py-2.5">
            <dt className="text-muted">日期 / 人数</dt>
            <dd className="text-right font-semibold">
              {new Intl.DateTimeFormat("zh-CN", {
                month: "numeric",
                day: "numeric",
              }).format(requirement.startDate)}
              {" - "}
              {new Intl.DateTimeFormat("zh-CN", {
                month: "numeric",
                day: "numeric",
              }).format(requirement.endDate)}
              {" · "}
              {requirement.travelers.length} 人
            </dd>
          </div>
          <div className="flex justify-between gap-3 py-2.5">
            <dt className="text-muted">预算 / 节奏</dt>
            <dd className="text-right font-semibold">
              {formatCurrency(requirement.totalBudget, requirement.currency)}
              {" · "}
              {paceLabels[requirement.pace] ?? "自定义"}
            </dd>
          </div>
          <div className="py-2.5">
            <dt className="text-muted">必去</dt>
            <dd className="mt-1 font-semibold">
              {[...requirement.mustVisit].join("、") || "无"}
            </dd>
          </div>
          <div className="py-2.5">
            <dt className="text-muted">避开</dt>
            <dd className="mt-1 font-semibold">
              {[...requirement.avoid].join("、") || "无"}
            </dd>
          </div>
        </dl>
      )}
    </>
  );

  if (compact) {
    return (
      <details className="border-y border-border">
        <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-3 py-2 text-sm font-bold">
          <span className="flex items-center gap-2">
            <SlidersHorizontal className="size-4 text-sky" />
            旅行约束
          </span>
          <span className="truncate text-xs font-semibold text-muted">
            {requirement
              ? `${requirement.origin.name} → ${requirement.destinations.map((item) => item.name).join(" · ")}`
              : "待载入"}
          </span>
        </summary>
        <div className="pb-3">{content}</div>
      </details>
    );
  }

  return (
    <section aria-labelledby="constraints-title">
      {content}
    </section>
  );
}

export function PlanResultsPage() {
  const params = useParams<{ planId?: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const activeTripId = useTripFlowStore((state) => state.activeTripId);
  const activePlanId = useTripFlowStore((state) => state.activePlanId);
  const rememberPlan = useTripFlowStore((state) => state.rememberPlan);
  const requestedPlanId = params.planId ?? activePlanId;
  const [mobilePanel, setMobilePanel] = useState<MobilePanel>("map");
  const [selectedItemId, setSelectedItemId] = useState<string>();
  const [activeDay, setActiveDay] = useState(1);
  const [lockedItemIds, setLockedItemIds] = useState<Set<string>>(new Set());
  const [removedItemIds, setRemovedItemIds] = useState<Set<string>>(new Set());
  const [compareOpen, setCompareOpen] = useState(false);
  const [evidenceOpen, setEvidenceOpen] = useState(false);
  const [focusedEvidenceId, setFocusedEvidenceId] = useState<string>();
  const [replanOpen, setReplanOpen] = useState(false);
  const [suggestedReplan, setSuggestedReplan] = useState<string>();

  const planQuery = useQuery({
    queryKey: ["plans", requestedPlanId],
    queryFn: ({ signal }) => apiAdapter.plans.get(requestedPlanId!, signal),
    enabled: Boolean(requestedPlanId),
  });
  const tripId = planQuery.data?.tripId ?? activeTripId;
  const plansQuery = useQuery({
    queryKey: ["trip-plans", tripId],
    queryFn: ({ signal }) => apiAdapter.plans.list(tripId!, signal),
    enabled: Boolean(tripId),
  });
  const tripQuery = useQuery({
    queryKey: ["trips", tripId],
    queryFn: ({ signal }) => apiAdapter.trips.get(tripId!, signal),
    enabled: Boolean(tripId),
  });
  const plans = plansQuery.data?.items ?? [];
  const alternatives = useMemo(() => latestAlternatives(plans), [plans]);
  const comparisonPlans = useMemo(
    () =>
      planQuery.data
        ? [
            planQuery.data,
            ...alternatives.filter((item) => item.planId !== planQuery.data?.planId),
          ]
        : alternatives,
    [alternatives, planQuery.data],
  );

  useEffect(() => {
    if (!requestedPlanId && alternatives[0]) {
      rememberPlan(alternatives[0].tripId, alternatives[0].planId);
      void navigate(`/plans/${alternatives[0].planId}`, { replace: true });
    }
  }, [alternatives, navigate, rememberPlan, requestedPlanId]);

  useEffect(() => {
    if (!planQuery.data) {
      return;
    }
    rememberPlan(planQuery.data.tripId, planQuery.data.planId);
    setSelectedItemId(undefined);
    setActiveDay(planQuery.data.days[0]?.dayNumber ?? 1);
    setLockedItemIds(new Set(planQuery.data.replanInput.lockedItemIds));
    setRemovedItemIds(new Set());
  }, [planQuery.data, rememberPlan]);

  const plan = planQuery.data;
  const versions = useMemo(
    () => (plan ? familyVersions(plans, plan.planFamilyId) : []),
    [plan, plans],
  );
  const versionDiffQuery = useQuery({
    queryKey: ["plan-comparison", plan?.previousPlanId, plan?.planId],
    queryFn: ({ signal }) =>
      apiAdapter.plans.compare(plan!.previousPlanId!, plan!.planId, signal),
    enabled: Boolean(plan?.previousPlanId),
  });
  const routeGroups = useMemo<MapRouteGroup[]>(
    () =>
      plan?.days.map((day, index) => ({
        dayNumber: day.dayNumber,
        color: dayColors[index % dayColors.length],
        points: day.items.map((item) => ({
          itemId: item.itemId,
          place: item.location,
        })),
      })) ?? [],
    [plan],
  );
  const evidenceById = useMemo(
    () => new Map(plan?.evidence.map((item) => [item.evidenceId, item]) ?? []),
    [plan],
  );
  const conflictHistory = useMemo<HistoricalConflict[]>(
    () =>
      plan
        ? plans
            .filter((item) => item.planFamilyId === plan.planFamilyId)
            .flatMap((item) =>
              item.conflicts
                .filter((conflict) => conflict.status !== "OPEN")
                .map((conflict) => ({ conflict, version: item.version })),
            )
        : [],
    [plan, plans],
  );

  if (!requestedPlanId && plansQuery.isLoading) {
    return <Skeleton className="h-[680px] w-full" />;
  }
  if (!requestedPlanId && !activeTripId) {
    return (
      <>
        <PageHeader title="核心结果页" breadcrumbs={[{ label: "城市探索" }]} />
        <EmptyState
          icon={Route}
          title="暂无可恢复方案"
          description="完成一次旅行规划后，当前旅行和方案会在此自动恢复。"
          actionLabel="查看规划进度"
          onAction={() => void navigate("/tasks")}
        />
      </>
    );
  }
  if (!requestedPlanId && plansQuery.data && alternatives.length === 0) {
    return (
      <>
        <PageHeader title="核心结果页" breadcrumbs={[{ label: "城市探索" }]} />
        <EmptyState
          icon={Route}
          title="当前旅行还没有可用方案"
          description="规划完成后，候选方案会出现在这里。"
          actionLabel="查看规划进度"
          onAction={() => void navigate("/tasks")}
        />
      </>
    );
  }
  if (planQuery.isLoading || (tripId && (plansQuery.isLoading || tripQuery.isLoading))) {
    return <Skeleton className="h-[720px] w-full" />;
  }
  if (planQuery.isError || !plan) {
    return (
      <ErrorState
        title="无法加载旅行方案"
        description="当前内容暂时无法显示，已保存的选择不会受到影响。"
        onAction={() => void planQuery.refetch()}
      />
    );
  }

  const selectPlan = (nextPlan: TravelPlan) => {
    rememberPlan(nextPlan.tripId, nextPlan.planId);
    void navigate(`/plans/${nextPlan.planId}`);
  };
  const selectMapItem = (itemId: string) => {
    const day = plan.days.find((item) =>
      item.items.some((entry) => entry.itemId === itemId),
    );
    setSelectedItemId(itemId);
    if (day) {
      setActiveDay(day.dayNumber);
    }
    setMobilePanel("timeline");
  };
  const openEvidence = (evidenceId?: string) => {
    setFocusedEvidenceId(evidenceId);
    setEvidenceOpen(true);
  };
  const openConflictReplan = (conflict: Conflict) => {
    setSuggestedReplan(
      `请处理以下行程冲突：${conflict.message}。保留已锁定项目，并仅修改必要范围。`,
    );
    setReplanOpen(true);
  };

  return (
    <>
      <PageHeader
        eyebrow="行程方案"
        title={plan.title}
        description={`${strategyFitDescription(plan.strategy)} ${plan.summary}`}
        breadcrumbs={[
          { label: "城市探索", to: "/explore" },
          { label: strategyLabel(plan.strategy) },
        ]}
        compactOnMobile
        actions={
          <>
            <Button
              type="button"
              variant="outline"
              disabled={alternatives.length < 2}
              onClick={() => setCompareOpen(true)}
            >
              <ArrowRightLeft />
              并排比较
            </Button>
            <Button type="button" onClick={() => setReplanOpen(true)}>
              <RefreshCcw />
              重规划
            </Button>
          </>
        }
      />

      <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2 border-y border-border py-3 text-xs">
        <StatusBadge status={plan.status} />
        <span className="flex items-center gap-1 text-muted">
          <CalendarDays className="size-3.5" />
          {dateRange(plan)}
        </span>
        <span className="font-semibold">{strategyLabel(plan.strategy)}</span>
        <span className="text-muted">版本 v{plan.version}</span>
        {plan.autoReplanned && (
          <span className="rounded bg-sky-soft px-2 py-1 font-semibold text-sky max-sm:hidden">
            已自动调整
          </span>
        )}
      </div>

      <div className="mb-3 lg:hidden">
        <section
          className="flex min-w-0 items-stretch gap-2 border-y border-border py-2"
          aria-labelledby="mobile-plan-switch-title"
        >
          <div className="min-w-0 flex-1">
            <div className="mb-1 flex items-center justify-between gap-2">
              <h2
                id="mobile-plan-switch-title"
                className="text-[11px] font-bold text-muted"
              >
                候选方案
              </h2>
              <span className="text-[10px] text-muted">
                {alternatives.length} 个
              </span>
            </div>
            <PlanSwitcher
              plans={alternatives}
              currentPlan={plan}
              onSelect={selectPlan}
              compact
            />
          </div>
          <label className="flex w-[72px] shrink-0 flex-col border-l border-border pl-2 text-[10px] font-bold text-muted">
            版本
            <select
              aria-label="版本历史"
              value={plan.planId}
              onChange={(event) => {
                const version = versions.find(
                  (item) => item.planId === event.target.value,
                );
                if (version) {
                  selectPlan(version);
                }
              }}
              className="mt-1 h-9 w-full rounded-md border border-border bg-surface px-1 text-xs font-semibold text-navy-strong"
            >
              {versions.map((version) => (
                <option key={version.planId} value={version.planId}>
                  v{version.version}
                </option>
              ))}
            </select>
          </label>
        </section>
        {tripQuery.data && <ConstraintsPanel trip={tripQuery.data} compact />}
      </div>

      <div
        className="sticky top-16 z-30 -mx-4 mb-3 grid grid-cols-4 border-y border-border bg-surface/95 px-2 backdrop-blur lg:hidden"
        role="group"
        aria-label="结果模块"
      >
        {mobilePanels.map((panel) => (
          <button
            key={panel.id}
            type="button"
            aria-pressed={mobilePanel === panel.id}
            onClick={() => setMobilePanel(panel.id)}
            className={cn(
              "flex h-12 items-center justify-center gap-1.5 border-b-2 text-xs font-semibold",
              mobilePanel === panel.id
                ? "border-coral text-navy-strong"
                : "border-transparent text-muted",
            )}
          >
            <panel.icon className="size-4" />
            {panel.label}
          </button>
        ))}
      </div>

      <div className="grid min-w-0 gap-5 lg:grid-cols-[230px_minmax(0,1fr)_310px] lg:gap-0 lg:border-y lg:border-border">
        <aside className="hidden min-w-0 lg:block lg:border-r lg:border-border lg:py-5 lg:pr-5">
          {tripQuery.data && (
            <ConstraintsPanel trip={tripQuery.data} />
          )}

          <section className="mt-6" aria-labelledby="plan-switch-title">
            <div className="mb-3 flex items-center justify-between gap-2">
              <h2 id="plan-switch-title" className="text-sm font-bold">
                方案切换
              </h2>
              <span className="text-[10px] font-semibold text-muted">
                {alternatives.length} 个候选
              </span>
            </div>
            <PlanSwitcher
              plans={alternatives}
              currentPlan={plan}
              onSelect={selectPlan}
            />
          </section>

          <section className="mt-6" aria-labelledby="version-switch-title">
            <h2 id="version-switch-title" className="text-sm font-bold">
              版本历史
            </h2>
            <select
              aria-label="版本历史"
              value={plan.planId}
              onChange={(event) => {
                const version = versions.find(
                  (item) => item.planId === event.target.value,
                );
                if (version) {
                  selectPlan(version);
                }
              }}
              className="mt-3 h-10 w-full rounded-md border border-border bg-surface px-3 text-xs font-semibold"
            >
              {versions.map((version) => (
                <option key={version.planId} value={version.planId}>
                  版本 {version.version}
                </option>
              ))}
            </select>
            {versionDiffQuery.data && (
              <div className="mt-3 border-l-2 border-sky pl-3 text-xs leading-5">
                <p className="font-semibold">相对上一版本</p>
                <p className="mt-1 text-muted">
                  预算变化{" "}
                  {formatCurrency(
                    versionDiffQuery.data.budgetDelta,
                    plan.budgetSummary.currency,
                  )}
                  ，新增 {versionDiffQuery.data.addedItemIds.size} 项，删除{" "}
                  {versionDiffQuery.data.removedItemIds.size} 项。
                </p>
              </div>
            )}
          </section>
        </aside>

        <main className="min-w-0 lg:px-5 lg:py-5">
          <section className={cn(mobilePanel !== "map" && "hidden lg:block")}>
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <div>
                <h2 className="text-base font-bold">路线地图</h2>
                <p className="mt-1 text-xs text-muted">
                  颜色按日期区分，仅显示已有准确位置的地点。
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                {plan.days.map((day, index) => (
                  <span
                    key={day.dayNumber}
                    className="flex items-center gap-1 text-[10px] font-semibold text-muted"
                  >
                    <span
                      className="size-2.5 rounded-full"
                      style={{ backgroundColor: dayColors[index % dayColors.length] }}
                    />
                    第 {day.dayNumber} 天
                  </span>
                ))}
              </div>
            </div>
            <MapContainer
              routes={routeGroups}
              label={`${strategyLabel(plan.strategy)}路线`}
              selectedItemId={selectedItemId}
              onSelectItem={selectMapItem}
              className="min-h-[420px] lg:min-h-[470px]"
            />
          </section>

          <div className={cn("mt-6", mobilePanel !== "timeline" && "hidden lg:block")}>
            <ResultsTimeline
              days={plan.days}
              activeDay={activeDay}
              onDayChange={setActiveDay}
              selectedItemId={selectedItemId}
              onSelectItem={setSelectedItemId}
              lockedItemIds={lockedItemIds}
              removedItemIds={removedItemIds}
              onToggleLock={(itemId) => {
                setLockedItemIds((current) => {
                  const next = new Set(current);
                  if (next.has(itemId)) {
                    next.delete(itemId);
                  } else {
                    next.add(itemId);
                  }
                  return next;
                });
              }}
              onToggleRemove={(itemId) => {
                setRemovedItemIds((current) => {
                  const next = new Set(current);
                  if (next.has(itemId)) {
                    next.delete(itemId);
                  } else {
                    next.add(itemId);
                  }
                  return next;
                });
              }}
              evidenceById={evidenceById}
              onOpenEvidence={openEvidence}
            />
          </div>
        </main>

        <aside className="min-w-0 lg:border-l lg:border-border lg:pl-5 lg:py-5">
          <div className={cn(mobilePanel !== "budget" && "hidden lg:block")}>
            <BudgetAnalysis budget={plan.budgetSummary} />
          </div>

          <div
            className={cn(
              "lg:mt-7 lg:border-t lg:border-border lg:pt-6",
              mobilePanel !== "conflicts" && "hidden lg:block",
            )}
          >
            <ConflictCenter
              conflicts={plan.conflicts}
              history={conflictHistory}
              onReplan={openConflictReplan}
            />

            <section className="mt-6 border-t border-border pt-5">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <p className="text-xs font-semibold text-muted">来源与依据</p>
                  <h2 className="mt-1 text-base font-bold">证据</h2>
                </div>
                <span className="text-lg font-bold">{plan.evidence.length}</span>
              </div>
              <Button
                type="button"
                variant="outline"
                className="mt-3 w-full"
                onClick={() => openEvidence()}
              >
                <BookOpen />
                打开证据抽屉
                <ChevronRight />
              </Button>
              {plan.evidence.length === 0 && (
                <p className="mt-2 text-xs font-semibold text-warning">
                  当前方案证据不足
                </p>
              )}
            </section>
          </div>

          <div className="mt-6 border-t border-border pt-5 max-lg:hidden">
            <ConfirmationPanel
              plan={plan}
              onPlanConfirmed={() => {
                void queryClient.invalidateQueries({ queryKey: ["plans", plan.planId] });
                void queryClient.invalidateQueries({ queryKey: ["trip-plans", plan.tripId] });
              }}
            />
          </div>
        </aside>
      </div>

      <div className="sticky bottom-0 z-30 -mx-4 mt-5 flex gap-2 border-t border-border bg-surface/95 p-3 backdrop-blur lg:hidden">
        <Button
          type="button"
          variant="outline"
          className="flex-1"
          disabled={comparisonPlans.length < 2}
          onClick={() => setCompareOpen(true)}
        >
          <GitCompareArrows />
          方案比较
        </Button>
        <Button type="button" className="flex-1" onClick={() => setReplanOpen(true)}>
          <RefreshCcw />
          重规划
        </Button>
      </div>

      <div className="mt-5 border-t border-border pt-5 lg:hidden">
        <ConfirmationPanel
          plan={plan}
          onPlanConfirmed={() => {
            void queryClient.invalidateQueries({ queryKey: ["plans", plan.planId] });
            void queryClient.invalidateQueries({ queryKey: ["trip-plans", plan.tripId] });
          }}
        />
      </div>

      <PlanComparisonSheet
        open={compareOpen}
        onOpenChange={setCompareOpen}
        plans={comparisonPlans}
        currentPlan={plan}
      />
      <EvidenceDrawer
        open={evidenceOpen}
        onOpenChange={setEvidenceOpen}
        evidence={plan.evidence}
        focusedEvidenceId={focusedEvidenceId}
      />
      <ReplanSheet
        open={replanOpen}
        onOpenChange={setReplanOpen}
        plan={plan}
        lockedItemIds={lockedItemIds}
        removedItemIds={removedItemIds}
        suggestedRequest={suggestedReplan}
      />
    </>
  );
}
