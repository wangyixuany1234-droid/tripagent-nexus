import { useMemo } from "react";
import { useQueries } from "@tanstack/react-query";
import type { Place } from "@api-generated/models/Place";
import {
  ArrowRight,
  BookOpenCheck,
  BrainCircuit,
  CalendarDays,
  FileCheck2,
  MapPinned,
  Route,
  Sparkles,
} from "lucide-react";
import { Link, useNavigate } from "react-router-dom";
import { apiAdapter } from "@/api/client";
import { EmptyState } from "@/components/states/state-panel";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { MapContainer } from "@/components/travel/map-container";
import { StatusBadge } from "@/components/travel/status-badge";
import { useTripFlowStore } from "@/stores/trip-flow-store";

const exampleRoute = [
  { name: "故宫博物院", longitude: 116.397, latitude: 39.918 },
  { name: "景山公园", longitude: 116.397, latitude: 39.925 },
  { name: "南锣鼓巷", longitude: 116.404, latitude: 39.937 },
] satisfies Place[];

const valueItems = [
  {
    icon: BrainCircuit,
    title: "分工规划",
    description: "需求、交通、住宿、推荐和预算分步骤完成并相互核对。",
  },
  {
    icon: FileCheck2,
    title: "订单自动归档",
    description: "上传票据后提取时间、金额与地点，并保留证据。",
  },
  {
    icon: BookOpenCheck,
    title: "关键约束核验",
    description: "逐项核对金额、日期、时间安排和确认状态。",
  },
] as const;

function formatTripDate(date?: Date) {
  if (!date) {
    return "待确定日期";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric",
  }).format(date);
}

export function WorkspacePage() {
  const navigate = useNavigate();
  const recentTripIds = useTripFlowStore((state) => state.recentTripIds);
  const draft = useTripFlowStore((state) => state.draft);
  const activePlanByTrip = useTripFlowStore((state) => state.activePlanByTrip);
  const loadExampleDraft = useTripFlowStore((state) => state.loadExampleDraft);
  const rememberTrip = useTripFlowStore((state) => state.rememberTrip);

  const tripQueries = useQueries({
    queries: recentTripIds.map((tripId) => ({
      queryKey: ["trips", tripId],
      queryFn: () => apiAdapter.trips.get(tripId),
      retry: false,
      staleTime: 30_000,
    })),
  });
  const trips = useMemo(
    () => tripQueries.flatMap((query) => (query.data ? [query.data] : [])),
    [tripQueries],
  );
  const hasDraft =
    Boolean(draft.values.identity.title) ||
    Boolean(draft.values.requirement.origin.name) ||
    Boolean(draft.values.requirement.destinations[0]?.name);

  return (
    <>
      <section className="relative min-h-[420px] overflow-hidden rounded-md border border-border bg-surface sm:min-h-[440px]">
        <MapContainer
          places={exampleRoute}
          label="北京示例路线"
          showStatus={false}
          className="absolute inset-0 min-h-full rounded-none border-0"
        />
        <div className="pointer-events-none absolute inset-0 bg-[linear-gradient(90deg,var(--surface)_0%,var(--surface)_34%,color-mix(in_srgb,var(--surface)_70%,transparent)_52%,transparent_72%)] max-sm:bg-[linear-gradient(180deg,var(--surface)_0%,color-mix(in_srgb,var(--surface)_88%,transparent)_54%,transparent_88%)]" />
        <div className="relative z-10 flex min-h-[420px] max-w-xl flex-col justify-center p-6 sm:min-h-[440px] sm:p-10 lg:p-14">
          <p className="text-xs font-bold text-coral">复杂自由行规划</p>
          <h1 className="mt-3 max-w-lg text-3xl font-bold text-navy-strong sm:text-4xl">
            TripAgent Nexus
          </h1>
          <p className="mt-3 text-xl font-bold text-foreground sm:text-2xl">
            把复杂自由行，变成可执行的旅程
          </p>
          <p className="mt-4 max-w-md text-sm leading-7 text-muted sm:text-base">
            综合旅行需求、已确认订单与可靠依据，生成多个可比较、可调整的行程方案。
          </p>
          <div className="pointer-events-auto mt-7 flex flex-wrap gap-3">
            <Button asChild size="lg">
              <Link to="/trips/new">
                <Route />
                {hasDraft ? "继续规划" : "开始规划"}
              </Link>
            </Button>
            <Button
              variant="outline"
              size="lg"
              onClick={() => {
                loadExampleDraft();
                navigate("/trips/new");
              }}
            >
              <Sparkles />
              使用示例
            </Button>
          </div>
        </div>
        <span className="absolute right-4 bottom-4 z-10 rounded-full bg-surface/90 px-2.5 py-1 text-[11px] font-semibold text-muted backdrop-blur">
          示例路线
        </span>
      </section>

      <section className="grid border-b border-border sm:grid-cols-3" aria-label="核心能力">
        {valueItems.map((item) => (
          <div
            key={item.title}
            className="flex min-h-28 gap-3 border-b border-border px-1 py-6 last:border-b-0 sm:border-r sm:border-b-0 sm:px-5 sm:first:pl-0 sm:last:border-r-0"
          >
            <item.icon className="mt-0.5 size-5 shrink-0 text-sky" />
            <div>
              <h2 className="text-sm font-bold">{item.title}</h2>
              <p className="mt-1.5 text-xs leading-5 text-muted">{item.description}</p>
            </div>
          </div>
        ))}
      </section>

      <section className="py-7" aria-labelledby="recent-trips-title">
        <div className="mb-4 flex items-center justify-between gap-3">
          <div>
            <p className="text-xs font-bold text-coral">近期安排</p>
            <h2 id="recent-trips-title" className="mt-1 text-xl font-bold text-navy-strong">
              最近行程
            </h2>
          </div>
          <Button asChild variant="ghost">
            <Link to="/trips/new">
              新建
              <ArrowRight />
            </Link>
          </Button>
        </div>

        {tripQueries.some((query) => query.isLoading) ? (
          <div className="grid gap-3 md:grid-cols-3">
            <Skeleton className="h-36" />
            <Skeleton className="h-36" />
            <Skeleton className="h-36" />
          </div>
        ) : trips.length === 0 ? (
          <EmptyState
            compact
            icon={MapPinned}
            title="还没有旅行"
            description="完成需求向导后，旅行会保存在这里。"
          />
        ) : (
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {trips.map((trip) => (
              <Link
                key={trip.tripId}
                to={
                  activePlanByTrip[trip.tripId]
                    ? `/plans/${activePlanByTrip[trip.tripId]}`
                    : `/trips/${trip.tripId}/documents`
                }
                onClick={() => rememberTrip(trip.tripId)}
                className="group rounded-md border border-border bg-surface p-4 shadow-[var(--shadow)] transition-transform hover:-translate-y-0.5"
              >
                <div className="flex items-start justify-between gap-3">
                  <h3 className="min-w-0 truncate text-base font-bold">{trip.title}</h3>
                  <StatusBadge status={trip.status} />
                </div>
                <p className="mt-4 flex items-center gap-2 text-xs text-muted">
                  <CalendarDays className="size-3.5" />
                  {formatTripDate(trip.requirement?.startDate)}
                  <span>至</span>
                  {formatTripDate(trip.requirement?.endDate)}
                </p>
                <p className="mt-2 truncate text-xs text-muted">
                  {trip.requirement?.destinations.map((place) => place.name).join(" · ") ||
                    "需求待完善"}
                </p>
              </Link>
            ))}
          </div>
        )}
      </section>
    </>
  );
}
