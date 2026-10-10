import { useEffect, useState } from "react";
import type { DayPlan } from "@api-generated/models/DayPlan";
import type { Evidence } from "@api-generated/models/Evidence";
import type { ItineraryItem } from "@api-generated/models/ItineraryItem";
import {
  BedDouble,
  ChevronDown,
  ChevronUp,
  Clock3,
  Coffee,
  Lock,
  MapPin,
  Route,
  Sparkles,
  Ticket,
  Trash2,
  Unlock,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn, formatCurrency, formatTime } from "@/lib/utils";
import {
  evidenceSourceMeta,
  itemDurationMinutes,
  itemEvidence,
} from "@/lib/plan-view";

const itemPageSize = 12;

const itemMeta: Record<
  ItineraryItem["type"],
  { label: string; icon: typeof Route; className: string }
> = {
  TRANSPORT: { label: "交通", icon: Route, className: "bg-sky-soft text-sky" },
  ACCOMMODATION: {
    label: "住宿",
    icon: BedDouble,
    className: "bg-success-soft text-success",
  },
  ATTRACTION: {
    label: "景点",
    icon: Ticket,
    className: "bg-coral-soft text-coral",
  },
  DINING: { label: "餐饮", icon: Coffee, className: "bg-warning-soft text-warning" },
  FREE_TIME: {
    label: "自由活动",
    icon: Sparkles,
    className: "bg-surface-subtle text-muted",
  },
  OTHER: { label: "其他", icon: MapPin, className: "bg-surface-subtle text-muted" },
};

function sourceLabel(evidence: Evidence[]) {
  if (evidence.some((item) => item.sourceType === "DOCUMENT")) {
    return "已确认订单";
  }
  if (evidence.some((item) => item.sourceType === "PROVIDER")) {
    return "实时信息";
  }
  return "智能推荐";
}

export function ResultsTimeline({
  days,
  activeDay,
  onDayChange,
  selectedItemId,
  onSelectItem,
  lockedItemIds,
  removedItemIds,
  onToggleLock,
  onToggleRemove,
  evidenceById,
  onOpenEvidence,
}: {
  days: DayPlan[];
  activeDay: number;
  onDayChange: (dayNumber: number) => void;
  selectedItemId?: string;
  onSelectItem: (itemId: string) => void;
  lockedItemIds: Set<string>;
  removedItemIds: Set<string>;
  onToggleLock: (itemId: string) => void;
  onToggleRemove: (itemId: string) => void;
  evidenceById: Map<string, Evidence>;
  onOpenEvidence: (evidenceId?: string) => void;
}) {
  const [expandedItemIds, setExpandedItemIds] = useState<Set<string>>(new Set());
  const [visibleItemLimit, setVisibleItemLimit] = useState(itemPageSize);
  const day = days.find((item) => item.dayNumber === activeDay) ?? days[0];
  const selectedIndex = day?.items.findIndex((item) => item.itemId === selectedItemId) ?? -1;
  const effectiveVisibleLimit =
    selectedIndex >= visibleItemLimit ? selectedIndex + 1 : visibleItemLimit;
  const visibleItems = day?.items.slice(0, effectiveVisibleLimit) ?? [];
  const hiddenCount = day ? Math.max(day.items.length - visibleItems.length, 0) : 0;

  useEffect(() => {
    setVisibleItemLimit(itemPageSize);
  }, [day?.dayNumber]);

  useEffect(() => {
    if (selectedItemId) {
      document
        .getElementById(`timeline-item-${selectedItemId}`)
        ?.scrollIntoView({
          block: "nearest",
          behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches
            ? "auto"
            : "smooth",
        });
    }
  }, [activeDay, selectedItemId]);

  if (!day) {
    return <p className="py-10 text-center text-sm text-muted">暂无每日行程</p>;
  }

  return (
    <section aria-labelledby="daily-timeline-title">
      <div className="flex items-end justify-between gap-3 border-b border-border pb-3">
        <div>
          <p className="text-xs font-bold text-coral">每日安排</p>
          <h2 id="daily-timeline-title" className="mt-1 text-lg font-bold">
            每日时间线
          </h2>
        </div>
        <time className="text-xs text-muted">
          {new Intl.DateTimeFormat("zh-CN", {
            month: "long",
            day: "numeric",
            weekday: "short",
          }).format(day.date)}
        </time>
      </div>

      <div
        className="mt-3 flex gap-1 overflow-x-auto pb-1"
        role="group"
        aria-label="选择行程日期"
      >
        {days.map((item) => (
          <button
            key={item.dayNumber}
            type="button"
            aria-pressed={item.dayNumber === day.dayNumber}
            onClick={() => onDayChange(item.dayNumber)}
            className={cn(
              "h-9 shrink-0 border-b-2 px-3 text-xs font-bold",
              item.dayNumber === day.dayNumber
                ? "border-coral text-navy-strong"
                : "border-transparent text-muted hover:text-foreground",
            )}
          >
            第 {item.dayNumber} 天
          </button>
        ))}
      </div>

      <ol className="mt-4">
        {visibleItems.map((item, index) => {
          const meta = itemMeta[item.type];
          const Icon = meta.icon;
          const evidence = itemEvidence(item, evidenceById);
          const expanded = expandedItemIds.has(item.itemId);
          const locked = lockedItemIds.has(item.itemId);
          const removed = removedItemIds.has(item.itemId);
          return (
            <li
              key={item.itemId}
              className={cn(
                "relative grid min-w-0 grid-cols-[48px_30px_minmax(0,1fr)] gap-3 pb-5 sm:grid-cols-[64px_34px_minmax(0,1fr)]",
                removed && "opacity-55",
              )}
            >
              {index < visibleItems.length - 1 && (
                <span className="absolute top-8 bottom-0 left-[61px] w-px bg-border sm:left-[80px]" />
              )}
              <time className="pt-1 text-xs font-bold text-navy-strong">
                {formatTime(item.startTime)}
              </time>
              <span
                className={cn(
                  "relative z-10 flex size-7 items-center justify-center rounded-full",
                  meta.className,
                  selectedItemId === item.itemId && "ring-2 ring-sky ring-offset-2",
                )}
                title={meta.label}
              >
                <Icon className="size-3.5" />
              </span>

              <article
                id={`timeline-item-${item.itemId}`}
                className={cn(
                  "min-w-0 border-b border-border pb-4",
                  selectedItemId === item.itemId && "border-sky",
                )}
              >
                <div className="flex min-w-0 items-start justify-between gap-2">
                  <button
                    type="button"
                    className="min-w-0 text-left"
                    onClick={() => onSelectItem(item.itemId)}
                  >
                    <span className={cn("block truncate text-sm font-bold", removed && "line-through")}>
                      {item.title}
                    </span>
                    <span className="mt-1 flex items-center gap-1 text-xs text-muted">
                      <MapPin className="size-3 shrink-0" />
                      <span className="truncate">{item.location.name}</span>
                    </span>
                  </button>
                  <span className="shrink-0 text-xs font-semibold">
                    {formatCurrency(item.amount, item.currency)}
                  </span>
                </div>

                <div className="mt-2 flex flex-wrap items-center gap-1.5">
                  <span className="rounded bg-surface-subtle px-1.5 py-0.5 text-[10px] font-semibold text-muted">
                    {sourceLabel(evidence)}
                  </span>
                  <span className="flex items-center gap-1 text-[11px] text-muted">
                    <Clock3 className="size-3" />
                    停留 {itemDurationMinutes(item)} 分钟
                  </span>
                  {item.type === "TRANSPORT" && (
                    <span className="text-[11px] font-semibold text-sky">
                      交通 {itemDurationMinutes(item)} 分钟
                    </span>
                  )}
                </div>

                {expanded && (
                  <div className="mt-3 border-l-2 border-border pl-3 text-xs leading-5 text-muted">
                    <p>{item.description || "暂无额外推荐说明。"}</p>
                    {evidence.length > 0 ? (
                      <div className="mt-2 flex flex-wrap gap-1">
                        {evidence.map((entry) => (
                          <button
                            key={entry.evidenceId}
                            type="button"
                            className={cn(
                              "rounded px-1.5 py-0.5 text-[10px] font-semibold",
                              evidenceSourceMeta[entry.sourceType].tone,
                            )}
                            onClick={() => onOpenEvidence(entry.evidenceId)}
                          >
                            {evidenceSourceMeta[entry.sourceType].label}
                          </button>
                        ))}
                      </div>
                    ) : (
                      <p className="mt-2 font-semibold text-warning">该行程项证据不足</p>
                    )}
                  </div>
                )}

                <div className="mt-2 flex flex-wrap items-center gap-1">
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() =>
                      setExpandedItemIds((current) => {
                        const next = new Set(current);
                        if (next.has(item.itemId)) {
                          next.delete(item.itemId);
                        } else {
                          next.add(item.itemId);
                        }
                        return next;
                      })
                    }
                  >
                    {expanded ? <ChevronUp /> : <ChevronDown />}
                    {expanded ? "收起" : "原因与来源"}
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    disabled={removed}
                    onClick={() => onToggleLock(item.itemId)}
                  >
                    {locked ? <Unlock /> : <Lock />}
                    {locked ? "取消锁定" : "锁定"}
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    disabled={locked}
                    onClick={() => onToggleRemove(item.itemId)}
                    className={removed ? "text-danger" : undefined}
                  >
                    <Trash2 />
                    {removed ? "撤销删除" : "删除"}
                  </Button>
                </div>
              </article>
            </li>
          );
        })}
      </ol>
      {hiddenCount > 0 && (
        <Button
          type="button"
          variant="outline"
          className="mt-1 w-full"
          onClick={() =>
            setVisibleItemLimit((current) =>
              Math.min(current + itemPageSize, day?.items.length ?? current),
            )
          }
        >
          显示更多行程
          <span className="text-xs text-muted">剩余 {hiddenCount} 项</span>
        </Button>
      )}
    </section>
  );
}
